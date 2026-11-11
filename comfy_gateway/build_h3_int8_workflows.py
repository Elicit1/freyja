"""Build unaccelerated H3 INT8 API templates and editable ComfyUI workflows."""
import json
import uuid
from pathlib import Path


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build(root):
    for kind, loader, lora, schedule in (("fl2va", 6, 121, 9), ("ref2va", 127, 145, 124)):
        api = json.loads((root / "comfy_gateway/comfyUIApi" /
                          f"minimax_h3_{kind}_official_turbo8.json").read_text(encoding="utf-8"))
        del api[str(lora)]
        for node in api.values():
            for key, value in node["inputs"].items():
                if value == [str(lora), 0]:
                    node["inputs"][key] = [str(loader), 0]
            if node["class_type"] == "SaveVideo":
                node["inputs"]["filename_prefix"] = f"MiniMax-H3/{kind.upper()}-INT8-20step"
        api[str(schedule)]["inputs"]["steps"] = 20
        cond = next(node for node in api.values()
                    if node["class_type"] in {"MiniMaxH3ImageToVideo", "MiniMaxH3ReferenceToVideo"})
        # Standalone API files work without gateway injection or auxiliary size/math nodes.
        cond["inputs"].update(prompt="A yellow ball rolls across a blue floor. Quiet room tone. No background music.",
                              width=832, height=480, length=124)
        for node_id in ("115", "131", "132", "138"):
            api.pop(node_id, None)
        write_json(root / "comfy_gateway/comfyUIApi" / f"minimax_h3_{kind}_int8.json", api)

        ui = json.loads((root / "comfyUI_workflow" /
                         f"video_pika_h3_flex_{kind}.json").read_text(encoding="utf-8"))
        ui["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, f"freyja/minimax-h3-{kind}-int8"))
        ui["revision"] = 1
        ui["nodes"] = [node for node in ui["nodes"] if node["id"] != lora]
        nodes = {node["id"]: node for node in ui["nodes"]}
        ui["links"] = [link for link in ui["links"] if link[3] != lora]
        for link in ui["links"]:
            if link[1] == lora:
                link[1], link[2] = loader, 0
        scheduler = nodes[schedule]
        scheduler.update(type="BasicScheduler", title="基础采样 · 默认20步 · 无加速",
                         inputs=[{"name": "model", "type": "MODEL", "link": None}],
                         widgets_values=["simple", 20, 1.0],
                         properties={"Node name for S&R": "BasicScheduler"})
        lid = max(link[0] for link in ui["links"]) + 1
        ui["links"].append([lid, loader, 0, schedule, 0, "MODEL"])
        for node in nodes.values():
            for inp in node.get("inputs", []):
                inp["link"] = None
            for output in node.get("outputs", []):
                output["links"] = []
            if node["type"] == "KSamplerSelect":
                node["widgets_values"] = ["res_multistep"]
            elif node["type"] == "SaveVideo":
                node["widgets_values"][0] = f"MiniMax-H3/{kind.upper()}-INT8-20step"
            elif node["type"] == "MarkdownNote":
                node["widgets_values"] = [
                    f"MiniMax H3 {kind.upper()} INT8 基础工作流\n\n"
                    "INT8 底模直接进入 BasicGuider / BasicScheduler，res_multistep + simple，默认20步。\n"
                    "不加载 LoRA、EasyCache、Sol Attention、Fused Modulation 或 Chunk FeedForward。\n\n"
                    "默认832×480 / 124帧 / 24fps。宽高为32的倍数，帧数为17k+5。\n"
                    "示例图在 examples/，导入后请上传示例图或选择自己的图片。\n"
                    "FL2VA可断开尾帧或全部图片；REF2VA支持图像、音频及平台动态参考视频。\n\n"
                    "FREYJA Studio支持可选二采与人脸修复，均继承本基础模型，不叠加加速LoRA。"]
        for lid, origin, slot, target, target_slot, _ in ui["links"]:
            nodes[origin]["outputs"][slot]["links"].append(lid)
            nodes[target]["inputs"][target_slot]["link"] = lid
        for order, node in enumerate(ui["nodes"]):
            node["order"] = order
        ui["last_node_id"] = max(nodes)
        ui["last_link_id"] = max(link[0] for link in ui["links"])
        ui.setdefault("extra", {})["freyja"] = {"gateway_model": f"minimax-h3-{kind}-int8"}
        write_json(root / "comfyUI_workflow" / f"video_minimax_h3_{kind}_int8.json", ui)


if __name__ == "__main__":
    build(Path(__file__).resolve().parent.parent)
