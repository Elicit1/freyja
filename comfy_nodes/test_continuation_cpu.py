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
