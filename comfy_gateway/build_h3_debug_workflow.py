"""Generate the editable UI companion for the existing Turbo8 workflow.

New modules are bypassed by default. This never changes deployment acceptance.
"""
import copy
import json
from pathlib import Path


def build(root):
    graph=json.loads((root/"comfyUI_workflow/video_minimax_h3_r2v_official_turbo8.json").read_text(encoding="utf-8"))
    nodes={node["id"]:node for node in graph["nodes"]}
    def add(nid,kind,inputs,outputs,widgets,pos,title,mode=0):
        node={"id":nid,"type":kind,"pos":pos,"size":[360,180],"flags":{},"order":len(graph["nodes"]),"mode":mode,
              "inputs":[{"name":name,"type":typ,"link":None} for name,typ in inputs],
              "outputs":[{"name":name,"type":typ,"links":[]} for name,typ in outputs],"properties":{"Node name for S&R":kind},"widgets_values":widgets,"title":title}
        graph["nodes"].append(node);nodes[nid]=node;return node
    def connect(source,slot,target,index,typ):
        old=nodes[target]["inputs"][index].get("link")
        if old is not None:
            link=next(link for link in graph["links"] if link[0]==old)
            links=nodes[link[1]]["outputs"][link[2]].get("links") or []
            if old in links:links.remove(old)
            graph["links"].remove(link)
        graph["last_link_id"]+=1;lid=graph["last_link_id"]
        graph["links"].append([lid,source,slot,target,index,typ]);nodes[target]["inputs"][index]["link"]=lid
        output=nodes[source]["outputs"][slot]
        if output.get("links") is None:output["links"]=[]
        output["links"].append(lid)
    add(200,"MiniMaxH3ContextWindows",[("model","MODEL")],[("MODEL","MODEL")],[124,39,"pyramid",True,False],[-150,5650],"上下文窗口（默认旁路，Ctrl+B 启用）",4)
    add(201,"FreyjaMiniMaxH3FunControlNetApply",[("model","MODEL"),("model_patch","MODEL_PATCH"),("vae","VAE"),("control_video","IMAGE")],[("MODEL","MODEL")],[1.0,0.0,1.0],[-550,5850],"可选 ControlNet（先上传视频，Ctrl+B 启用）",4)
    add(202,"ModelPatchLoader",[],[("MODEL_PATCH","MODEL_PATCH")],["minimax_h3_fun_controlnet_union_pruned_int8_convrot.safetensors"],[-1500,6250],"Union 补丁权重")
    add(203,"Canny",[("image","IMAGE")],[("IMAGE","IMAGE")],[0.4,0.8],[-1050,6400],"控制信号预处理：Canny")
    add(204,"FreyjaH3ControlFrames",[("images","IMAGE")],[("IMAGE","IMAGE")],[124,120],[-1050,6150],"控制帧覆盖（示例生成 124 帧，保留 120 帧）")
    add(205,"FreyjaH3Continuation",[("positive","CONDITIONING"),("latent","LATENT"),("vae","VAE"),("image","IMAGE"),("audio_vae","VAE"),("audio","AUDIO")],[("positive","CONDITIONING"),("LATENT","LATENT")],[0],[-150,6000],"共享上下文锁定（首段旁路，后续段启用）",4)
    context=copy.deepcopy(nodes[147]);context.update(id=206,title="上一段真实尾部上下文（上传后 Ctrl+M 启用）",pos=[-1500,6900],order=len(graph["nodes"]),mode=2)
    context["widgets_values"].update(video="",custom_width=0,custom_height=0,frame_load_cap=39)
    for inp in context["inputs"]:inp["link"]=None
    for output in context["outputs"]:output["links"]=[]
    graph["nodes"].append(context);nodes[206]=context
    for args in ((145,0,201,0,"MODEL"),(202,0,201,1,"MODEL_PATCH"),(119,0,201,2,"VAE"),(147,0,204,0,"IMAGE"),(204,0,203,0,"IMAGE"),(203,0,201,3,"IMAGE"),(201,0,200,0,"MODEL"),(200,0,126,0,"MODEL"),(200,0,124,0,"MODEL"),(136,0,205,0,"CONDITIONING"),(136,1,205,1,"LATENT"),(119,0,205,2,"VAE"),(206,0,205,3,"IMAGE"),(120,0,205,4,"VAE"),(206,2,205,5,"AUDIO"),(205,0,126,1,"CONDITIONING"),(205,1,125,4,"LATENT")):connect(*args)
    graph["last_node_id"]=max(nodes);graph["id"]="8ac239c5-4271-4c80-9c91-8661f94c6d30";graph["revision"]=1
    graph.setdefault("extra",{})["freyja"]={"purpose":"H3 optional-module acceptance; default 5-second segment; not a whole-film concatenator","gateway_model":"minimax-h3-ref-turbo8"}
    target=root/"comfyUI_workflow/minimax_h3_continuation_control_debug.json"
    target.write_text(json.dumps(graph,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return target


if __name__=="__main__":build(Path(__file__).resolve().parent.parent)
