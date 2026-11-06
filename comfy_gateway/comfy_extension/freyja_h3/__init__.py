"""FREYJA H3 window alignment. Install this directory under ComfyUI/custom_nodes/."""
import torch
from collections import OrderedDict
import comfy.nested_tensor
import node_helpers
from comfy_api.latest import io, ComfyExtension
from comfy_extras.nodes_minimax_h3 import MiniMaxH3FunControlNetApply, MiniMaxH3FunControlPatch, MiniMaxH3AddGuide, _encode_ref_audio, _resize
from comfy.ldm.minimax.model import FRAME_PER_TOKEN
from .control_validation import require_union2


def window_pixel_range(indices):
    if not indices or list(indices)!=list(range(indices[0],indices[-1]+1)):
        raise ValueError("FREYJA H3 supports contiguous, non-looping context windows")
    first=sum(FRAME_PER_TOKEN[i%5] for i in range(indices[0]))
    end=sum(FRAME_PER_TOKEN[i%5] for i in range(indices[-1]+1))
    return first,end


class WindowControlPatch(MiniMaxH3FunControlPatch):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        self.full_control=self.control_video
        self.full_mask=self.mask
        self.full_source=self.source_video
        self.window_key=None
        self.window_cache=OrderedDict()

    def diffusion_model_wrapper(self,executor,x,timestep,context,transformer_options=None,**kwargs):
        options=transformer_options or {}
        window=options.get("context_window")
        if window is None:
            key=None;first=0;end=self.full_control.shape[0] if self.full_control is not None else 0
        else:
            first,end=window_pixel_range(window.index_list);key=(first,end)
        cache_key=(key,tuple(x[0].shape))
        if self.window_key!=cache_key:
            self.control_latent=self.window_cache.get(cache_key)
            self.control_latent_shape=tuple(x[0].shape) if self.control_latent is not None else None
            self.window_key=cache_key
        if self.full_control is not None:
            if end>self.full_control.shape[0]:raise ValueError("Control video does not cover the current H3 window")
            self.control_video=self.full_control[first:end]
        if self.full_source is not None:self.source_video=self.full_source[first:end]
        if self.full_mask is not None:self.mask=self.full_mask[first:end]
        result=super().diffusion_model_wrapper(executor,x,timestep,context,options,**kwargs)
        if self.control_latent is not None:
            self.window_cache[cache_key]=self.control_latent.detach().to("cpu");self.window_cache.move_to_end(cache_key)
            while len(self.window_cache)>4:self.window_cache.popitem(last=False)
        return result
    def cleanup(self):
        super().cleanup();self.window_cache.clear();self.window_key=None


class FreyjaMiniMaxH3FunControlNetApply(MiniMaxH3FunControlNetApply):
    @classmethod
    def define_schema(cls):
        schema=super().define_schema();schema.node_id="FreyjaMiniMaxH3FunControlNetApply";schema.display_name="FREYJA H3 ControlNet (Window Aligned)";return schema
    @classmethod
    def execute(cls,model,model_patch,vae,strength,start_percent,end_percent,control_video=None,mask=None,source_video=None):
        if not strength:return io.NodeOutput(model)
        if control_video is None and mask is None:raise ValueError("Missing H3 control input")
        patched=model.clone();sampling=model.get_model_object("model_sampling")
        patch=WindowControlPatch(model_patch,vae,control_video[...,:3].movedim(-1,1) if control_video is not None else None,
                                 mask,source_video[...,:3].movedim(-1,1) if source_video is not None else None,strength,
                                 float(sampling.percent_to_sigma(start_percent)),float(sampling.percent_to_sigma(end_percent)))
        patch.register(patched);return io.NodeOutput(patched)


class FreyjaH3Extension(ComfyExtension):
    async def get_node_list(self):return [FreyjaMiniMaxH3FunControlNetApply,FreyjaH3ControlFrames,FreyjaH3Continuation,FreyjaH3Union2Patch,FreyjaH3GrayFrames]


class FreyjaH3Union2Patch(io.ComfyNode):
    @classmethod
    def define_schema(cls):
        return io.Schema(node_id="FreyjaH3Union2Patch",display_name="FREYJA H3 Union 2.0 Check",category="model/patch/minimax",
                         inputs=[io.ModelPatch.Input("model_patch")],outputs=[io.ModelPatch.Output()])
    @classmethod
    def execute(cls,model_patch):
        require_union2(model_patch)
        return io.NodeOutput(model_patch)


class FreyjaH3GrayFrames(io.ComfyNode):
    @classmethod
    def define_schema(cls):
        return io.Schema(node_id="FreyjaH3GrayFrames",display_name="FREYJA H3 Gray Control",category="image/preprocessors",
                         inputs=[io.Image.Input("image")],outputs=[io.Image.Output()])
    @classmethod
    def execute(cls,image):
        weights=image.new_tensor([0.299,0.587,0.114])
        gray=(image[...,:3]*weights).sum(dim=-1,keepdim=True)
        return io.NodeOutput(gray.expand(*gray.shape[:-1],3).contiguous())


class FreyjaH3ControlFrames(io.ComfyNode):
    @classmethod
    def define_schema(cls):
        return io.Schema(node_id="FreyjaH3ControlFrames",display_name="FREYJA H3 Control Frame Coverage",category="model/patch/minimax",
                         inputs=[io.Image.Input("images"),io.Int.Input("target_frames",min=22,max=362,default=124),io.Int.Input("required_frames",min=1,max=362,default=124)],outputs=[io.Image.Output()])
    @classmethod
    def execute(cls,images,target_frames,required_frames):
        if required_frames>target_frames or images.shape[0]<required_frames:raise ValueError("Control video does not cover the requested output interval")
        indices=torch.arange(target_frames,device=images.device).clamp(max=images.shape[0]-1)
        return io.NodeOutput(images[indices])


class FreyjaH3Continuation(MiniMaxH3AddGuide):
    @classmethod
    def define_schema(cls):
        schema=super().define_schema();schema.node_id="FreyjaH3Continuation";schema.display_name="FREYJA H3 Locked AV Context"
        schema.outputs=[io.Conditioning.Output(display_name="positive"),io.Latent.Output()];return schema
    @classmethod
    def execute(cls,positive,latent,frame_idx,vae=None,audio_vae=None,image=None,audio=None):
        if frame_idx!=0 or vae is None or image is None or image.shape[0]<5 or (image.shape[0]-5)%17:
            raise ValueError("H3 continuation requires a legal multi-frame prefix at frame zero")
        samples=latent["samples"]
        if not samples.is_nested or len(samples.tensors)!=2:raise ValueError("Expected H3 joint AV latent")
        video=samples.tensors[0].clone();sound=samples.tensors[1].clone()
        encoded=vae.encode(_resize(image,video.shape[-1]*16,video.shape[-2]*16,"center"))
        count=encoded.shape[2]
        if count>=video.shape[2]:raise ValueError("Context must leave room for new video frames")
        video[:,:,:count]=encoded.to(video.device,video.dtype)
        video_mask=torch.ones_like(video);video_mask[:,:,:count]=0
        audio_mask=torch.ones_like(sound)
        guide={"resolved_frame_index":0,"latent":encoded}
        if audio is not None:
            if audio_vae is None:raise ValueError("Missing audio VAE")
            encoded_audio,audio_count=_encode_ref_audio(audio_vae,audio)
            audio_count=min(audio_count,sound.shape[-1])
            sound[...,:audio_count]=encoded_audio[...,:audio_count].to(sound.device,sound.dtype);audio_mask[...,:audio_count]=0
            guide["audio_latent"]=encoded_audio[...,:audio_count]
        values=dict(latent);values["samples"]=comfy.nested_tensor.NestedTensor((video,sound));values["noise_mask"]=comfy.nested_tensor.NestedTensor((video_mask,audio_mask))
        conditioning=node_helpers.conditioning_set_values(positive,{"minimax_keyframes":list(positive[0][1].get("minimax_keyframes",[]))+[guide]})
        return io.NodeOutput(conditioning,values)

async def comfy_entrypoint():return FreyjaH3Extension()
