"""Small CPU tensors only. No models, GPU allocation, network or video generation."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest

import torch

ROOT=Path(__file__).parent


class Nested:
    is_nested=True
    def __init__(self,parts):self.parts=tuple(parts)
    def unbind(self):return self.parts


class FakeModel:
    def __init__(self):self.model_options={};self.wrappers={}
    def clone(self):
        out=FakeModel();out.model_options=dict(self.model_options);out.wrappers=dict(self.wrappers);return out
    def set_model_denoise_mask_function(self,fn):self.model_options['denoise_mask_function']=fn
    def add_wrapper_with_key(self,kind,key,fn):self.wrappers[key]=fn
    def set_model_unet_function_wrapper(self,fn):self.model_options['model_function_wrapper']=fn


def load_nodes(folder):
    paths=types.ModuleType('folder_paths')
    paths.get_input_directory=lambda:str(folder)
    paths.get_output_directory=lambda:str(folder)
    sys.modules['folder_paths']=paths
    comfy=types.ModuleType('comfy');comfy.__path__=[]
    nested=types.ModuleType('comfy.nested_tensor');nested.NestedTensor=Nested
    patch=types.ModuleType('comfy.patcher_extension');patch.WrappersMP=types.SimpleNamespace(APPLY_MODEL='apply_model')
    comfy.nested_tensor=nested;comfy.patcher_extension=patch
    sys.modules.update({'comfy':comfy,'comfy.nested_tensor':nested,'comfy.patcher_extension':patch})
    spec=importlib.util.spec_from_file_location('freyja_nodes',ROOT/'freyja_h3_continuation/__init__.py',submodule_search_locations=[str(ROOT/'freyja_h3_continuation')])
    module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module)
    return sys.modules['freyja_nodes.nodes']


class ContinuationCpuTest(unittest.TestCase):
    def test_second_pass_handoff_pins_video_tail_without_carrying_audio_or_changing_source(self):
        from freyja_nodes.upscale import FreyjaH3UpscaleContinue
        from unittest.mock import patch
        previous=torch.arange(24*37*2*4,dtype=torch.float32).reshape(1,24,37,2,4)
        current=torch.full_like(previous,-1)
        audio=torch.full((1,32,2,207),3.)
        latent={'samples':Nested((current,audio))}
        with patch('freyja_nodes.nodes.read_artifact',return_value=(previous,torch.zeros_like(audio))) as read:
            carried,restore=FreyjaH3UpscaleContinue().apply(latent,'previous.safetensors','model',22)
            read.assert_called_with('previous.safetensors','model',stage='SECOND_PASS')
            video,sound=carried['samples'].unbind()
            self.assertTrue(torch.equal(video[:,:,:7],previous[:,:,30:]))
            self.assertTrue(torch.equal(video[:,:,7:],current[:,:,7:]))
            self.assertIs(sound,audio)
            self.assertTrue(torch.all(current==-1))
            vm,am=carried['noise_mask'].unbind()
            self.assertTrue(torch.all(vm[:,:,:7]==0));self.assertTrue(torch.all(vm[:,:,7:]==1));self.assertTrue(torch.all(am==1))
            damaged={'samples':Nested((video+1,audio))}
            restored=self.nodes.FreyjaH3Restore().apply(damaged,restore)[0]['samples'].unbind()[0]
            self.assertTrue(torch.equal(restored[:,:,:7],previous[:,:,30:]))
            with self.assertRaisesRegex(ValueError,'CONTEXT_TOO_LONG'):FreyjaH3UpscaleContinue().apply(latent,'previous.safetensors','model',124)
        with patch('freyja_nodes.nodes.read_artifact',return_value=(previous[:,:,:,:,::2],audio)):
            with self.assertRaisesRegex(ValueError,'CANVAS_MISMATCH'):FreyjaH3UpscaleContinue().apply(latent,'previous.safetensors','model',22)

    def test_second_pass_artifact_is_distinct_from_first_pass(self):
        path=self.nodes.FreyjaH3SaveLatent().save(self.latent(2),'compatible','upscale',stage='SECOND_PASS')['ui']['latents'][0]
        filename=path['subfolder']+'/'+path['filename']
        with self.assertRaisesRegex(ValueError,'STAGE_MISMATCH'):self.nodes.read_artifact(filename,'compatible')
        video,_=self.nodes.read_artifact(filename,'compatible',stage='SECOND_PASS')
        self.assertTrue(torch.all(video==2))

    def test_native_upscale_import_validates_compatibility_frame_count_and_source_canvas(self):
        from freyja_nodes.upscale import FreyjaH3LoadLatent
        from unittest.mock import patch
        video=torch.zeros(1,24,7,2,4);audio=torch.ones(1,32,2,37)
        with patch('freyja_nodes.nodes.read_artifact',return_value=(video,audio)) as read:
            output=FreyjaH3LoadLatent().apply('base.safetensors','same-model',64,32,22)[0]
            read.assert_called_with('base.safetensors','same-model')
            self.assertIs(video,output['samples'].unbind()[0]);self.assertIs(audio,output['samples'].unbind()[1])
            for width,height,frames in ((32,32,22),(64,64,22),(64,32,39)):
                with self.assertRaisesRegex(ValueError,'SOURCE_LAYOUT_MISMATCH'):FreyjaH3LoadLatent().apply('base.safetensors','same-model',width,height,frames)

    def test_source_encode_uses_original_size_and_validates_temporal_grid(self):
        from freyja_nodes.upscale import FreyjaH3UpscaleEncode
        calls=[]
        class Vae:
            def encode(self,images):
                calls.append(tuple(images.shape));return torch.ones(1,24,7,2,4)
        latent={'samples':Nested((torch.zeros(1,24,7,4,8),torch.ones(1,32,2,37)))}
        out=FreyjaH3UpscaleEncode().apply(torch.zeros(22,32,64,3),Vae(),latent)[0]
        self.assertEqual([(22,32,64,3)],calls);self.assertEqual((1,24,7,2,4),tuple(out['samples'].unbind()[0].shape))
        with self.assertRaisesRegex(ValueError,'GRID_INVALID'):FreyjaH3UpscaleEncode().apply(torch.zeros(23,32,64,3),Vae(),latent)

    def test_spatial_overlap_preserves_every_pixel_and_uses_first_audio_prediction(self):
        from freyja_nodes.upscale import tiled_prediction,regions
        video=torch.arange(24*7*8*12,dtype=torch.float32).reshape(1,24,7,8,12)/1000
        audio=torch.ones(1,32,2,37);calls=[]
        def predict(block,sound,ys,xs):
            calls.append((ys,xs));return block*2+3,sound*len(calls)
        out,sound=tiled_prediction(video,audio,predict,4,6,2)
        self.assertTrue(torch.allclose(out,video*2+3,atol=1e-5));self.assertTrue(torch.equal(sound,audio));self.assertGreater(len(calls),1)
        self.assertEqual([(0,8)],regions(8,12,2))
        with self.assertRaisesRegex(ValueError,'GRID_INVALID'):regions(12,6,6)

    def test_finish_size_crops_center_without_scaling_frames(self):
        from freyja_nodes.upscale import FreyjaH3FinishSize
        images=torch.arange(2*32*64*3).reshape(2,32,64,3)
        output=FreyjaH3FinishSize().apply(images,64,24)[0]
        self.assertTrue(torch.equal(output,images[:,4:28]));self.assertEqual(2,len(output))
        with self.assertRaisesRegex(ValueError,'CROP_INVALID'):FreyjaH3FinishSize().apply(images,32,24)

    def test_tile_payload_keeps_global_positions_and_full_references(self):
        from freyja_nodes.upscale import tile_payload
        from unittest.mock import patch
        class Layout:
            def __init__(self,context,t,h,w,a,keyframes=None,refs=None,frame_count=None):
                positions=[];self.segments=[];offset=0
                for frames,kind in ((1,'cond'),(0,'refs'),(t,'video'),(0,'audio')):
                    count=3 if kind=='refs' else a if kind=='audio' else frames*(h//2)*(w//2)
                    self.segments.append((offset,offset+count,kind));offset+=count
                    if frames:
                        grid=torch.stack(torch.meshgrid(torch.arange(frames),torch.arange(h//2),torch.arange(w//2),indexing='ij'),-1).reshape(-1,3)
                    else:grid=torch.full((count,3),7)
                    positions.append(grid)
                self.position_ids=torch.cat(positions)
        module=types.ModuleType('comfy.ldm.minimax.model');module.PackedLayout=Layout
        dit=types.ModuleType('comfy.ldm.common_dit');dit.pad_to_patch_size=lambda x,p:x
        video=torch.zeros(1,24,7,8,12);audio=torch.zeros(1,32,2,37)
        keyframe=torch.arange(24*8*12).reshape(1,24,1,8,12)
        refs=[{'latent':torch.ones(1,24,7,4,6)}]
        with patch.dict(sys.modules,{'comfy.ldm.minimax.model':module,'comfy.ldm.common_dit':dit}):
            original={'keyframes':[{'latent':keyframe}], 'refs':refs,'frame_count':22}
            payload=tile_payload(original,torch.zeros(1,5,3),video,audio,(2,6),(4,10))
        self.assertIs(refs,payload['refs']);self.assertIs(keyframe,original['keyframes'][0]['latent'])
        self.assertTrue(torch.equal(payload['keyframes'][0]['latent'],keyframe[...,2:6,4:10]))
        start,end,_=payload['layout'].segments[2]
        coords=payload['layout'].position_ids[start:end]
        self.assertEqual(1,int(coords[:,1].min()));self.assertEqual(2,int(coords[:,2].min()));self.assertEqual(4,int(coords[:,2].max()))

    def test_memory_plan_counts_full_av_buffers_and_references(self):
        from freyja_nodes.upscale import prepare_tiles
        video=(1,24,7,8,12);audio=(1,32,2,37);total=24*7*8*12+32*2*37
        model=types.SimpleNamespace(model=types.SimpleNamespace(memory_required=lambda _:100))
        reference=torch.zeros(1,24,7,4,6);captured=[]
        def execute(m,shape,conds,**kwargs):captured.append((shape,kwargs));return 'planned'
        self.assertEqual('planned',prepare_tiles(execute,model,(1,1,total),{'positive':[{'minimax_refs':[{'latent':reference}]}]},tile_height=4,tile_width=6,overlap=2,latent_shapes=(video,audio)))
        minimum=24*7*4*6+32*2*37+reference.numel()
        self.assertGreater(captured[0][0][-1],minimum)
        with self.assertRaisesRegex(ValueError,'LAYOUT_MISMATCH'):prepare_tiles(execute,model,(1,1,total+1),{},latent_shapes=(video,audio))

    def test_custom_model_hooks_are_cloned_scoped_and_reject_mask_conflicts(self):
        from freyja_nodes.upscale import FreyjaH3TileModel
        from unittest.mock import patch
        base=types.ModuleType('comfy.model_base');base.MiniMaxH3=type('MiniMaxH3',(),{})
        model=FakeModel();model.model=base.MiniMaxH3()
        latent=self.latent()
        with patch.dict(sys.modules,{'comfy.model_base':base}),patch.object(sys.modules['comfy'],'model_base',base,create=True),patch.object(sys.modules['comfy.patcher_extension'],'WrappersMP',types.SimpleNamespace(DIFFUSION_MODEL='diffusion',PREPARE_SAMPLING='prepare')):
            cloned=FreyjaH3TileModel().apply(model,latent,768,512,128)[0]
            self.assertIsNot(model,cloned);self.assertEqual({},model.wrappers)
            self.assertEqual({'freyja_h3_upscale_v1','freyja_h3_upscale_budget_v1'},set(cloned.wrappers))
            self.assertEqual((1,24,37,2,2),cloned.wrappers['freyja_h3_upscale_budget_v1'].keywords['latent_shapes'][0])
            with self.assertRaisesRegex(ValueError,'GRID_INVALID'):FreyjaH3TileModel().apply(model,latent,768,512,512)
            model.model_options['denoise_mask_function']=object()
            with self.assertRaisesRegex(ValueError,'MASK_OWNER_CONFLICT'):FreyjaH3TileModel().apply(model,latent,768,512,128)

    def test_stage_rejects_wrong_frame_count_and_output_size(self):
        images=torch.zeros(22,16,32,3)
        node=self.nodes.FreyjaH3CheckFrames()
        self.assertIs(images,node.apply(images,22,32,16)[0])
        with self.assertRaisesRegex(ValueError,"FRAME_MISMATCH"):node.apply(images,39)
        with self.assertRaisesRegex(ValueError,"SIZE_MISMATCH"):node.apply(images,22,64,16)
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.folder=Path(cls.temp.name);cls.nodes=load_nodes(cls.folder)
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def latent(self,fill=0):
        return {'samples':Nested((torch.full((1,24,37,2,2),float(fill)),torch.full((1,32,2,207),float(fill))))}
    def artifact(self,fill=1):
        result=self.nodes.FreyjaH3SaveLatent().save(self.latent(fill),'compatible','source-'+str(fill))
        item=result['ui']['latents'][0]
        return item['subfolder']+'/'+item['filename']

    def test_chroma_is_reproducible_tapers_and_does_not_mutate_source(self):
        source=torch.full((22,24,24,3),.5);before=source.clone()
        fn=self.nodes.FreyjaH3ChromaNoise().apply
        out=fn(source,.45,0,3,17)[0]
        self.assertTrue(torch.equal(out,fn(source,.45,0,3,17)[0]))
        self.assertTrue(torch.equal(source,before));self.assertTrue(torch.equal(out[-1],source[-1]))
        self.assertGreater(float((out[0]-source[0]).square().mean()),float((out[-2]-source[-2]).square().mean()))
        self.assertGreaterEqual(float(out.min()),0);self.assertLessEqual(float(out.max()),1)

    def test_native_tail_is_phase_aligned_and_audio_independently_masked(self):
        source=self.artifact(2)
        before=self.latent()
        out,model,restore=self.nodes.FreyjaH3Continue().apply(before,FakeModel(),torch.tensor([1.,.5,0.]),source,'compatible',22,'LATENT_REDRAW',.1,False)
        video,audio=out['samples'].unbind()
        self.assertTrue(torch.all(video[:,:,:7]==2));self.assertTrue(torch.all(video[:,:,7:]==0))
        self.assertTrue(torch.all(audio[...,:37]==2));self.assertTrue(torch.all(before['samples'].unbind()[0]==0))
        self.assertIsNone(restore['video'])
        vm,am=out['noise_mask'].unbind();self.assertAlmostEqual(float(vm[0,0,6,0,0]),.1,places=5)
        self.assertEqual(float(am[0,0,0,0]),0);self.assertEqual(float(am[0,0,0,36]),1)

    def test_protection_restores_context_and_keeps_original_artifact_clean(self):
        source=self.artifact(2);anchor=self.artifact(3)
        out,_,restore=self.nodes.FreyjaH3Continue().apply(self.latent(),FakeModel(),torch.tensor([1.,.5,0.]),source,'compatible',22,'LATENT_PROTECTED',1,True,False,.75,anchor)
        self.assertTrue(torch.all(out['samples'].unbind()[0][:,:,:1]==3))
        vm=out['noise_mask'].unbind()[0];self.assertEqual(float(vm[0,0,0,0,0]),0);self.assertEqual(float(vm[0,0,6,0,0]),0)
        fixed=self.nodes.FreyjaH3Restore().apply(out,restore)[0]
        self.assertTrue(torch.all(fixed['samples'].unbind()[0][:,:,:7]==2));self.assertNotIn('noise_mask',fixed)
        self.assertTrue(torch.all(self.nodes.read_artifact(source,'compatible')[0]==2))

    def test_sampler_and_model_receive_the_same_live_mask(self):
        source=self.artifact(2)
        out,model,_=self.nodes.FreyjaH3Continue().apply(self.latent(),FakeModel(),torch.tensor([1.,.5,0.]),source,'compatible',22,'LATENT_REDRAW',.1,False)
        v,a=out['samples'].unbind();size=v.numel()
        packed=torch.ones((1,1,size+a.numel()))
        actual=model.model_options['denoise_mask_function'](torch.tensor([1.]),packed)
        captured={}
        def apply(*args,**kwargs):captured.update(kwargs);return 7
        wrapper=model.wrappers['freyja_h3_prefix_v1']
        self.assertEqual(wrapper(apply,None,torch.tensor([1.]),denoise_mask=torch.ones((1,1,37,2,2))),7)
        sampler=actual[...,:size].reshape(v.shape)
        self.assertTrue(torch.equal(sampler[:,:1],captured['denoise_mask']))

    def test_artifact_is_safe_and_checks_compatibility_and_phase(self):
        source=self.artifact(2)
        with self.assertRaisesRegex(ValueError,'INCOMPATIBLE'):self.nodes.read_artifact(source,'different')
        with self.assertRaisesRegex(ValueError,'PATH_INVALID'):self.nodes.input_path('../outside.safetensors')
        from freyja_nodes.policy import tail_start,pixel_frames,latent_steps
        for frames in (5,22,39,56,124,192,243,362):self.assertEqual(pixel_frames(latent_steps(frames)),frames)
        self.assertEqual(tail_start(37,22),(30,7))
        with self.assertRaisesRegex(ValueError,'PHASE'):tail_start(36,22)

    def test_existing_mask_owner_is_not_overwritten(self):
        source=self.artifact(2);model=FakeModel();model.model_options['denoise_mask_function']=lambda *a:None
        with self.assertRaisesRegex(ValueError,'MASK_CONFLICT'):
            self.nodes.FreyjaH3Continue().apply(self.latent(),model,torch.tensor([1.,0.]),source,'compatible',22,'LATENT_REDRAW',.1,False)

    def test_tail_anchor_enters_only_late_model_calls_and_never_enters_output(self):
        utils=types.ModuleType('comfy.utils')
        def pack(parts):return torch.cat([p.reshape(1,1,-1) for p in parts],dim=-1),[p.shape for p in parts]
        def unpack(tensor,shapes):
            result=[];offset=0
            for shape in shapes:
                count=shape.numel();result.append(tensor[...,offset:offset+count].reshape(shape));offset+=count
            return result
        utils.pack_latents=pack;utils.unpack_latents=unpack
        sys.modules['comfy.utils']=utils;sys.modules['comfy'].utils=utils
        model=FakeModel();sigmas=torch.tensor([1.,.8,.6,.4,.2,0.])
        self.nodes.install_tail_anchor(model,torch.full((1,24,1,2,2),3.),sigmas,.75)
        parts=self.latent(2)['samples'].unbind();packed,shapes=pack(parts);seen=[]
        def executor(x,t,**cond):
            decoded=unpack(x,cond['latent_shapes']);seen.append((decoded[0].shape[2],cond['denoise_mask'][:,:,-1].clone()))
            return x
        arguments={'input':packed,'c':{'latent_shapes':shapes,'denoise_mask':torch.ones(1,1,37,2,2)}}
        wrapped=model.model_options['model_function_wrapper']
        early=wrapped(executor,{**arguments,'timestep':torch.tensor([1.])})
        late=wrapped(executor,{**arguments,'timestep':torch.tensor([.2])})
        self.assertEqual(seen[0][0],37);self.assertEqual(seen[1][0],38)
        self.assertTrue(torch.all(seen[1][1]==0));self.assertTrue(torch.equal(late,packed));self.assertTrue(torch.equal(early,packed))


if __name__=='__main__':unittest.main(verbosity=2)
