"""Validate the loaded native patch, independently of checkpoint filenames."""


def require_union2(model_patch):
    inner=model_patch.model
    if (tuple(getattr(inner,"injection_layers",()))!=tuple(range(0,50,5))
            or len(getattr(inner,"control_blocks",()))!=10
            or getattr(inner,"inpaint_post_norm",False) is not True):
        raise ValueError("H3_UNION2_INCOMPATIBLE: expected 10 control blocks, injection layers 0,5,...45 and post_norm; update ComfyUI and use the Union 2.0 checkpoint")
