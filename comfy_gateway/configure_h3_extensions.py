"""Generate explicit bindings from checked-in API graphs; no runtime node guessing.

Run once when adding/changing a template. This leaves online acceptance UNVERIFIED.
"""
import json
import re
from pathlib import Path
import yaml


def bindings(model, graph):
    condition=model["mappings"]["prompt"][0]
    def binding(node):return {"id":node,"class_type":graph[node]["class_type"]}
    guiders=[(key,node) for key,node in graph.items() if node["class_type"] in ("BasicGuider","CFGGuider")]
    main=next((key,node) for key,node in guiders if node["inputs"].get("conditioning")==[condition,0] or node["inputs"].get("positive")==[condition,0])
    model_source=main[1]["inputs"]["model"]
    consumers=[dict(binding(key),input="model") for key,node in graph.items() if node["class_type"] in ("BasicGuider","CFGGuider","BasicScheduler") and node.get("inputs",{}).get("model")==model_source]
    vae=model["mappings"]["video_vae_name"][0]
    return {"status":"UNVERIFIED","continuation":True,"context_windows":True,"control_net":True,"context_windows_with_control_net":False,
            "control_version":"2.0",
            "control_patch":"minimax_h3_fun_controlnet_union_2.0_pruned_int8_convrot.safetensors",
            "model_output":binding(model_source[0]),"conditioning":binding(condition),"video_vae":binding(vae),"audio_vae":binding(model["mappings"]["audio_vae_name"][0]),
            "model_consumers":consumers,"conditioning_consumers":[dict(binding(main[0]),input="conditioning" if main[1]["class_type"]=="BasicGuider" else "positive")],
            "preprocessors":{"CANNY":{"class_type":"Canny","inputs":{"low_threshold":0.4,"high_threshold":0.8}},
                             "POSE":{"class_type":"DWPreprocessor","inputs":{"detect_hand":"enable","detect_body":"enable","detect_face":"enable","resolution":512,"bbox_detector":"yolox_l.onnx","pose_estimator":"dw-ll_ucoco_384.onnx"}},
                             "DEPTH":{"class_type":"DepthAnythingV2Preprocessor","inputs":{"ckpt_name":"depth_anything_v2_vitl.pth","resolution":512}},
                             "HED":{"class_type":"HEDPreprocessor","inputs":{"safe":"enable","resolution":512}},
                             "MLSD":{"class_type":"M-LSDPreprocessor","inputs":{"score_threshold":0.1,"dist_threshold":0.1,"resolution":512}},
                             "SCRIBBLE":{"class_type":"FakeScribblePreprocessor","inputs":{"safe":"enable","resolution":512}}},
            "prepared_control_types":["LAYOUT","PREPARED"]}


def configure(path, refresh=False):
    base=Path(__file__).parent;text=path.read_text(encoding="utf-8");data=yaml.safe_load(text)
    for model in data["models"]:
        if model["handler"] not in {"minimax_h3_fl2va","minimax_h3_ref2va","minimax_h3_face_refine"}:continue
        graph=json.loads((base/model["template_file"]).read_text(encoding="utf-8"))
        cfg=bindings(model,graph)
        # Keep crop-refinement on its original model: global control frames cannot be injected into a cropped face pass.
        if model["handler"]=="minimax_h3_face_refine":cfg["refine_pass_policy"]="UNCHANGED_CROP_REFINEMENT"
        block="\n"+"\n".join("    "+line for line in yaml.safe_dump({"h3_extensions":cfg},sort_keys=False,allow_unicode=True).splitlines())
        pattern=r'(  - model_name: "'+re.escape(model["model_name"])+r'"[\s\S]*?    handler: "[^"\n]+")'
        if refresh:
            text=re.sub(r'('+pattern+r')\n    h3_extensions:[\s\S]*?(?=\n    [a-zA-Z_]+\s*:|\n  - model_name:|\Z)',lambda m:m.group(1),text,count=1)
        if "h3_extensions" not in re.search(pattern+r'[\s\S]*?(?=\n  - model_name:|\Z)',text).group():text=re.sub(pattern,lambda m:m.group()+block,text,count=1)
    path.write_text(text,encoding="utf-8")


if __name__=="__main__":
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument("path",nargs="?",type=Path,default=Path(__file__).parent/"nodes/machine_default.yaml");parser.add_argument("--refresh",action="store_true")
    args=parser.parse_args();configure(args.path,args.refresh)
