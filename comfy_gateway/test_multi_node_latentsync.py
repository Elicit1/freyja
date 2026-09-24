import os
import sys
import json
from pathlib import Path

# Ensure comfy_gateway directory is in sys.path
gateway_dir = Path(__file__).resolve().parent
sys.path.insert(0, str(gateway_dir))

from config import load_config
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine


def test_multi_node_and_latentsync():
    print("==================================================")
    print("Testing Comfy Gateway Multi-Node & LatentSync...")
    print("==================================================")

    # 1. Test Config Loading
    print("\n[Test 1] Testing dynamic config & node scanning...")
    config = load_config()
    print(f"Loaded {len(config.nodes)} node(s): {list(config.nodes.keys())}")
    assert "default" in config.nodes, "Default node missing!"
    assert "machine_5060" in config.nodes, "machine_5060 node missing!"

    default_node = config.get_node("default")
    node_5060 = config.get_node("machine_5060")
    print(f"  - Default Node: {default_node.name} ({default_node.http_url}, ws: {default_node.get_ws_url()})")
    print(f"  - 5060 Node: {node_5060.name} ({node_5060.http_url}, ws: {node_5060.get_ws_url()})")

    loaded_model_names = [m.model_name for m in config.models]
    print(f"Loaded {len(loaded_model_names)} model(s): {loaded_model_names}")
    assert "latentsync-fp8-5060" in loaded_model_names, "latentsync-fp8-5060 model missing!"
    assert "flux-2-klein-9b" in loaded_model_names, "flux-2-klein-9b model missing!"

    # 2. Test Model-to-Node Routing
    print("\n[Test 2] Testing model-to-node routing...")
    routed_5060 = config.get_node_for_model("latentsync-fp8-5060")
    assert routed_5060 is not None, "Failed to route latentsync-fp8-5060"
    assert routed_5060.node_id == "machine_5060", f"Expected machine_5060, got {routed_5060.node_id}"
    print(f"  ✅ 'latentsync-fp8-5060' correctly routed to node '{routed_5060.node_id}' ({routed_5060.http_url})")

    routed_flux = config.get_node_for_model("flux-2-klein-9b")
    assert routed_flux is not None, "Failed to route flux-2-klein-9b"
    assert routed_flux.node_id == "default", f"Expected default, got {routed_flux.node_id}"
    print(f"  ✅ 'flux-2-klein-9b' correctly routed to node '{routed_flux.node_id}' ({routed_flux.http_url})")

    # 3. Test OpenAI Request Schema Parsing with LatentSync parameters
    print("\n[Test 3] Testing OpenAI request schema parsing...")
    req_payload = {
        "model": "latentsync-fp8-5060",
        "prompt": "lip sync test dialogue",
        "response_format": "url",
        "extra_body": {
            "video_url": "http://192.168.1.100:9000/test/face_clip.mp4",
            "audio_url": "http://192.168.1.100:9000/test/speech.wav",
            "inference_steps": 25,
            "lips_expression": 1.8,
            "vram_usage": "high",
            "seed": 888888,
            "node_id": "machine_5060"
        }
    }
    request = ImageGenerationRequest(**req_payload)
    assert request.video_url == "http://192.168.1.100:9000/test/face_clip.mp4"
    assert request.audio_url == "http://192.168.1.100:9000/test/speech.wav"
    assert request.inference_steps == 25
    assert request.lips_expression == 1.8
    assert request.vram_usage == "high"
    assert request.seed == 888888
    assert request.node_id == "machine_5060"
    print("  ✅ ImageGenerationRequest successfully parsed all LatentSync & node routing fields from extra_body:")
    print(f"     video_url={request.video_url}")
    print(f"     audio_url={request.audio_url}")
    print(f"     inference_steps={request.inference_steps}")
    print(f"     lips_expression={request.lips_expression}")
    print(f"     vram_usage={request.vram_usage}")
    print(f"     seed={request.seed}")

    # 4. Test LatentSync Workflow Assembly
    print("\n[Test 4] Testing LatentSync workflow assembly...")
    engine = WorkflowEngine(config)
    mock_video_name = "uploaded_face_544p.mp4"
    mock_audio_name = "uploaded_dialogue_24k.wav"

    assembled = engine.assemble_workflow(
        request=request,
        uploaded_video=mock_video_name,
        uploaded_audio=mock_audio_name
    )

    print("  Checking assembled node graph:")
    # Check Node 1 (Video)
    assert "1" in assembled, "Node 1 missing in assembled workflow"
    assert assembled["1"]["inputs"]["video"] == mock_video_name, f"Expected {mock_video_name}, got {assembled['1']['inputs']['video']}"
    print(f"  ✅ Node 1 (VHS_LoadVideo): video='{assembled['1']['inputs']['video']}'")

    # Check Node 2 (Audio)
    assert "2" in assembled, "Node 2 missing in assembled workflow"
    assert assembled["2"]["inputs"]["audio"] == mock_audio_name, f"Expected {mock_audio_name}, got {assembled['2']['inputs']['audio']}"
    print(f"  ✅ Node 2 (VHS_LoadAudio): audio='{assembled['2']['inputs']['audio']}'")

    # Check Node 4 (GeekyLatentSyncNode)
    assert "4" in assembled, "Node 4 missing in assembled workflow"
    node4_inputs = assembled["4"]["inputs"]
    assert node4_inputs["seed"] == 888888, f"Expected seed 888888, got {node4_inputs['seed']}"
    assert node4_inputs["inference_steps"] == 25, f"Expected steps 25, got {node4_inputs['inference_steps']}"
    assert node4_inputs["lips_expression"] == 1.8, f"Expected lips_expression 1.8, got {node4_inputs['lips_expression']}"
    assert node4_inputs["vram_usage"] == "high", f"Expected vram_usage 'high', got {node4_inputs['vram_usage']}"
    print(f"  ✅ Node 4 (GeekyLatentSyncNode): seed={node4_inputs['seed']}, inference_steps={node4_inputs['inference_steps']}, lips_expression={node4_inputs['lips_expression']}, vram_usage='{node4_inputs['vram_usage']}'")

    # Check Node 5 (VHS_VideoCombine)
    assert "5" in assembled, "Node 5 missing in assembled workflow"
    print(f"  ✅ Node 5 (VHS_VideoCombine): prefix='{assembled['5']['inputs']['filename_prefix']}', format='{assembled['5']['inputs']['format']}'")

    # 5. Test Direct Target Backend Override
    print("\n[Test 5] Testing direct backend override...")
    req_override = ImageGenerationRequest(
        model="latentsync-fp8-5060",
        prompt="lip sync override test",
        extra_body={"target_backend": "http://10.20.30.40:8188"}
    )
    assert req_override.target_backend == "http://10.20.30.40:8188"
    print(f"  ✅ target_backend successfully parsed: {req_override.target_backend}")

    print("\n==================================================")
    print("🎉 ALL TESTS PASSED SUCCESSFULLY!")
    print("==================================================")


if __name__ == "__main__":
    test_multi_node_and_latentsync()
