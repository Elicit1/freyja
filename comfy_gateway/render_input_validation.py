"""Validate Java's ordered binding contract against transport inputs and the assembled graph.

No prose semantics, no label renumbering, no silently discarded references.
"""
import hashlib
from video_reference import video_reference_bindings, control_inputs

VERSION = "h3-render-input/1"
FORMATS = {"MINIMAX_H3_REF2VA_V1", "MINIMAX_H3_REF2VA_VIDEO_V1", "MINIMAX_H3_FL2VA_V1"}


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def fail(message):
    raise ValueError("RENDER_INPUT_MISMATCH: " + message)


def validate_transport(request):
    contract = request.render_input_contract
    if not contract:
        if request.prompt_format in FORMATS:
            fail("missing render_input_contract; update the caller")
        return
    if request.prompt_format not in FORMATS or contract.get("version") != VERSION:
        fail("unsupported input contract or prompt format")
    if contract.get("promptHash") != digest(request.prompt):
        fail("prompt changed after validation")
    # The legacy request normalizer aliases the first standalone audio into audio_url.
    standalone = [b.audio_url for b in (request.references or []) if b.reference_type.value == "REFERENCE_AUDIO"]
    if request.ref_images or request.video_url or (request.audio_url and request.audio_url != next(iter(standalone), None)):
        fail("ambiguous legacy media fields alongside binding contract")
    actual = []
    images = request.get_all_ref_images()
    fl = request.prompt_format == "MINIMAX_H3_FL2VA_V1"
    if fl:
        if request.references or (not request.h3_options and (not request.first_frame or not request.last_frame)):
            fail("FL2VA requires exactly its first and last frame")
    elif request.first_frame or request.last_frame:
        fail("reference mode cannot implicitly append frame images")
    image_bindings = [b for b in (request.references or []) if b.images]
    flat_ids = [b.reference_id for b in image_bindings for _ in b.images]
    for index, source in enumerate(images):
        frame_roles=[role for role,source in (("FIRST_FRAME",request.first_frame),("LAST_FRAME",request.last_frame)) if source]
        role = frame_roles[index] if fl else "REFERENCE_IMAGE"
        ref_id = role if fl else flat_ids[index]
        label=f"<Picture {2 if role=='LAST_FRAME' else 1}>" if fl else f"<Picture {index+1}>"
        actual.append((label, "IMAGE", role, ref_id, digest(source)))
    audio_index = 0
    visual_index = 0
    videos = request.get_all_ref_videos()
    bindings = video_reference_bindings(request, videos)
    for source_index, video in enumerate(videos):
        for bound_source, control in bindings:
            if bound_source != source_index: continue
            visual_index += 1
            actual.append((f"<Video {visual_index}>", "VIDEO", "REFERENCE_VIDEO" if control is None else "CONTROL_VIDEO", video.reference_id, digest(video.video_url)))
        if video.include_audio:
            audio_index += 1
            actual.append((f"<Audio {audio_index}>", "AUDIO", "VIDEO_AUDIO", video.reference_id, digest(video.video_url)))
    for binding in (request.references or []):
        if binding.reference_type.value == "REFERENCE_AUDIO" and binding.audio_url:
            audio_index += 1
            actual.append((f"<Audio {audio_index}>", "AUDIO", "REFERENCE_AUDIO", binding.reference_id, digest(binding.audio_url)))
    slots = contract.get("slots")
    if not isinstance(slots, list):
        fail("missing slot list")
    expected = [(s.get("label"), s.get("kind"), s.get("role"), s.get("referenceId"), s.get("mediaHash")) for s in slots]
    if expected != actual:
        fail("ordered media labels do not match the submitted payload")


def _image_file(workflow, inputs, key):
    link = inputs.get(key)
    if not isinstance(link, list) or len(link) != 2 or link[1] != 0:
        fail(f"missing image connection {key}")
    loader = workflow.get(str(link[0]), {})
    if loader.get("class_type") != "LoadImage":
        fail(f"unexpected image loader at {key}")
    return loader.get("inputs", {}).get("image")


def _slots(inputs, prefix):
    keys = [k for k in inputs if k.startswith(prefix)]
    if any(not k[len(prefix):].isdigit() for k in keys):
        fail(f"invalid slot name under {prefix}")
    keys.sort(key=lambda k: int(k[len(prefix):]))
    if keys != [f"{prefix}{i}" for i in range(len(keys))]:
        fail(f"non-contiguous slots under {prefix}")
    return keys


def validate_workflow(request, workflow, model, images, audios, videos):
    if not request.render_input_contract:
        return
    validate_transport(request)
    if len(images) != len(request.get_all_ref_images()) or len(audios) != len(request.get_all_ref_audios()) or len(videos) != len(request.get_all_ref_videos()):
        fail("media upload changed the number of slots")
    mapping = (model.mappings or {}).get("prompt", [])
    if len(mapping) != 3 or mapping[1:] != ["inputs", "prompt"]:
        fail("unsupported prompt mapping")
    main_node = mapping[0]
    # Only known output nodes count: a detached conditioning node proves nothing.
    outputs = [nid for nid, node in workflow.items()
               if node.get("class_type") in {"VHS_VideoCombine", "SaveVideo", "SaveAnimatedWEBP"}]
    reachable = set()
    def visit(nid):
        if nid in reachable or nid not in workflow:
            return
        reachable.add(nid)
        for value in workflow[nid].get("inputs", {}).values():
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
                visit(value[0])
    for nid in outputs:
        visit(nid)
    nodes = [main_node]
    for nid in nodes:
        if nid not in reachable:
            fail(f"conditioning node {nid} is not connected to video output")
        node = workflow.get(nid, {})
        if node.get("class_type") not in {"MiniMaxH3ImageToVideo", "MiniMaxH3FirstLastFrameToVideo", "MiniMaxH3ReferenceToVideo", "MiniMaxVideoConditioning", "MiniMaxVideoRefConditioning"}:
            fail(f"unknown conditioning node {nid}")
        inputs = node.get("inputs", {})
        if inputs.get("prompt") != request.prompt:
            fail(f"prompt differs at conditioning node {nid}")
        if request.prompt_format == "MINIMAX_H3_FL2VA_V1":
            if request.dynamic_workflow and request.dynamic_workflow.stage == "FACE" and nid == "dynamic_face_158":
                keys = _slots(inputs, "ref_images.ref_image_")
            else:
                keys=[key for key,source in (("first_frame",request.first_frame),("last_frame",request.last_frame)) if source]
            if [_image_file(workflow, inputs, key) for key in keys] != images:
                fail("first/last frame order changed")
            continue
        image_keys = _slots(inputs, "ref_images.ref_image_")
        if [_image_file(workflow, inputs, key) for key in image_keys] != images:
            fail(f"image slots differ at conditioning node {nid}")
        audio_keys = _slots(inputs, "ref_audios.ref_audio_")
        actual_audios = []
        for key in audio_keys:
            link = inputs[key]
            loader = workflow.get(str(link[0]), {}) if isinstance(link, list) and len(link) == 2 else {}
            if loader.get("class_type") != "LoadAudio" or link[1] != 0:
                fail(f"missing audio loader at {key}")
            actual_audios.append(loader.get("inputs", {}).get("audio"))
        if actual_audios != audios:
            fail(f"audio slots were truncated or changed at {nid}")
        controls = control_inputs(request)
        bindings = video_reference_bindings(request, videos)
        video_keys = _slots(inputs, "ref_videos.ref_video_")
        if len(video_keys) != len(bindings):
            fail("video slot count changed")
        expected_audio_keys = []
        for i, key in enumerate(video_keys):
            source_index,control_index=bindings[i]
            link = inputs[key]
            if control_index is not None:
                from h3_extensions import control_frame_link
                if link!=control_frame_link(control_index,controls[control_index],model.h3_extensions):fail("control reference binding changed")
                suffix="" if control_index==0 else "_"+str(control_index)
                source=workflow.get("h3_control_video"+suffix,{})
                control=controls[control_index]
                expected=videos[source_index].get("control_filename") if control.control_type=="GRAY" and control.input_mode=="RAW" else videos[source_index]["filename"]
                if source.get("class_type")!="VHS_LoadVideo" or source.get("inputs",{}).get("video")!=expected:fail("control source changed")
                if link[0]!="h3_control_video"+suffix:
                    preset=model.h3_extensions["preprocessors"][control.control_type]
                    preprocessing=workflow.get(link[0],{})
                    if preprocessing.get("class_type")!=preset["class_type"] or preprocessing.get("inputs",{}).get(preset.get("image_input","image"))!=["h3_control_video"+suffix,0]:
                        fail("control preprocessing path changed")
                continue
            loader = workflow.get(str(link[0]), {}) if isinstance(link, list) and len(link) == 2 else {}
            if loader.get("class_type") != "VHS_LoadVideo" or link[1] != 0 or loader.get("inputs", {}).get("video") != videos[source_index]["filename"]:
                fail("video order changed")
        for i, video in enumerate(videos):
            if video["include_audio"]:
                audio_key = f"ref_video_audios.ref_video_audio_{i}"
                expected_audio_keys.append(audio_key)
                audio_link = inputs.get(audio_key)
                loader = workflow.get(str(audio_link[0]), {}) if isinstance(audio_link, list) and len(audio_link) == 2 else {}
                if loader.get("class_type") != "VHS_LoadVideo" or audio_link[1] != 2 or loader.get("inputs", {}).get("video") != video["filename"]:
                    fail("video soundtrack binding changed")
        if set(k for k in inputs if k.startswith("ref_video_audios.ref_video_audio_")) != set(expected_audio_keys):
            fail("video soundtrack slots changed")
