"""Discover registered app bundles from disk; no dependency on FREYJA or its gateway."""
import copy
import hashlib
import json
import random
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .models import ASPECT_RATIOS, ServiceError, canvas_size


class Binding(BaseModel):
    model_config = ConfigDict(extra="forbid")
    node: str
    input: str


class ModeConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")
    template: str
    bindings: dict[str, Binding]
    model_node: str
    output_node: str
    prompt_preview_node: str
    conditioning_node: str | None = None
    expansion_node: str | None = None
    expansion_template_node: str | None = None


class Profile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    description: str
    loader: Literal["UNETLoader", "UnetLoaderGGUF"]
    inputs: dict


class Manifest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal[1] = 1
    id: str = Field(pattern=r"^[a-z0-9][a-z0-9-]{0,63}$")
    title: str
    description: str
    modes: dict[Literal["t2i", "i2i"], ModeConfig]
    profiles: dict[Literal["pro", "standard", "fast"], Profile]

    @model_validator(mode="after")
    def required_capabilities(self):
        if not self.modes or not self.profiles:
            raise ValueError("An app must expose at least one mode and one profile.")
        for mode, config in self.modes.items():
            if config.conditioning_node and not (config.expansion_node and config.expansion_template_node):
                raise ValueError("H3 canvas modes require expansion_node and expansion_template_node.")
            required = {"prompt", "seed", "expand_prompt"}
            required.update({"width", "height"} if config.conditioning_node else {"megapixels"})
            if mode == "i2i":
                required.add("image")
            elif not config.conditioning_node:
                required.add("aspect_ratio")
            if not required.issubset(config.bindings):
                raise ValueError(f"{mode} is missing required bindings: {sorted(required - set(config.bindings))}")
            if set(config.bindings) - {"prompt", "seed", "megapixels", "expand_prompt", "image", "aspect_ratio", "width", "height"}:
                raise ValueError("Unknown binding parameter.")
        return self


def contained(root: Path, relative: str):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("A template must stay within its app bundle directory.")
    return path


class Registry:
    def __init__(self, directory):
        self.directory = Path(directory)

    def scan(self):
        apps, errors, duplicates = {}, [], set()
        for path in sorted(self.directory.glob("*/app.json")):
            try:
                if not path.resolve().is_relative_to(self.directory.resolve()):
                    raise ValueError("App bundle symlinks cannot escape the registry directory.")
                raw = path.read_bytes()
                manifest = Manifest.model_validate_json(raw)
                graphs = {}
                digest = hashlib.sha256(raw)
                for mode, spec in manifest.modes.items():
                    data = contained(path.parent, spec.template).read_bytes()
                    digest.update(data)
                    graph = json.loads(data)
                    if not isinstance(graph, dict) or "nodes" in graph or not graph:
                        raise ValueError("Use an API-format workflow, not a UI nodes/links export.")
                    for key, node in graph.items():
                        if not isinstance(key, str) or not isinstance(node, dict) or not isinstance(node.get("class_type"), str) or not isinstance(node.get("inputs"), dict):
                            raise ValueError("Invalid API workflow node.")
                    for binding in spec.bindings.values():
                        if binding.node not in graph or binding.input not in graph[binding.node]["inputs"]:
                            raise ValueError(f"Invalid {mode} binding: {binding.node}.{binding.input}")
                    if graph.get(spec.output_node, {}).get("class_type") != "SaveImage":
                        raise ValueError("output_node must identify a SaveImage node.")
                    if graph.get(spec.prompt_preview_node, {}).get("class_type") != "PreviewAny":
                        raise ValueError("prompt_preview_node must identify a PreviewAny after the expansion switch.")
                    if spec.model_node not in graph:
                        raise ValueError("Model node does not exist.")
                    for node_id, kind in ((spec.conditioning_node, "MiniMaxH3ImageConditioning"),
                                          (spec.expansion_node, "TextGenerateV2"),
                                          (spec.expansion_template_node, "PrimitiveStringMultiline")):
                        if node_id and graph.get(node_id, {}).get("class_type") != kind:
                            raise ValueError(f"Expected {kind} at {node_id}.")
                    graphs[mode] = graph
                if manifest.id in apps or manifest.id in duplicates:
                    apps.pop(manifest.id, None)
                    duplicates.add(manifest.id)
                    raise ValueError("Duplicate app ID; all conflicting registrations are disabled.")
                apps[manifest.id] = (manifest, graphs, digest.hexdigest())
            except (ValueError, OSError, KeyError, TypeError) as error:
                errors.append({"bundle": path.parent.name, "error": str(error)})
        return apps, errors

    def get(self, app_id):
        apps, _ = self.scan()
        if app_id not in apps:
            raise ServiceError("app_not_found", "App is missing or invalid; inspect /v1/apps diagnostics.", 404)
        return apps[app_id]

    def assemble(self, values, reference_sizes=()):
        manifest, graphs, revision = self.get(values["app"])
        mode, tier = values["mode"], values["tier"]
        if mode not in manifest.modes or tier not in manifest.profiles:
            raise ServiceError("unsupported_capability", "The app does not support this mode or tier.", 422)
        spec = manifest.modes[mode]
        graph = copy.deepcopy(graphs[mode])
        profile = manifest.profiles[tier]
        graph[spec.model_node].update(class_type=profile.loader, inputs=copy.deepcopy(profile.inputs))
        resolved = {**values, "seed": values["seed"] if values["seed"] is not None else random.SystemRandom().randrange(9007199254740992)}
        for name, binding in spec.bindings.items():
            value = resolved.get(name)
            if name == "aspect_ratio" and mode == "t2i":
                if value not in ASPECT_RATIOS:
                    raise ServiceError("unsupported_capability", "This app bundle requires a preset aspect ratio.", 422)
                value = ASPECT_RATIOS[value]
            graph[binding.node]["inputs"][binding.input] = value
        image_binding = spec.bindings["image"].model_dump() if mode == "i2i" else None
        image_bindings = []
        if spec.conditioning_node:
            width, height = canvas_size(values, reference_sizes[0] if reference_sizes else None)
            resolved.update(width=width, height=height, actual_megapixels=width * height / 1024 ** 2)
            condition = graph[spec.conditioning_node]["inputs"]
            condition.update(width=width, height=height)
            if mode == "i2i":
                references = values.get("images") or [values["image"]]
                if values.get("reference_mode", "latent") == "vision":
                    condition.pop("vae", None)
                expansion = graph[spec.expansion_node]["inputs"]
                source = graph[image_binding["node"]]
                for index, reference in enumerate(references, 1):
                    node_id = image_binding["node"] if index == 1 else f"sif_reference_{index}"
                    graph[node_id] = copy.deepcopy(source)
                    graph[node_id]["inputs"][image_binding["input"]] = reference
                    image_bindings.append({"node": node_id, "input": image_binding["input"]})
                    condition[f"images.image_{index}"] = [node_id, 0]
                    expansion[f"images.image{index}"] = [node_id, 0]
            expansion = graph[spec.expansion_node]["inputs"]
            options = values.get("prompt_expansion") or {}
            for name, value in options.items():
                if name in {"thinking", "max_length"}:
                    expansion[name] = value
                elif name != "instructions":
                    expansion["sampling_mode." + name] = value
            template = graph[spec.expansion_template_node]["inputs"]
            context = f"User Aspect Ratio Selection: {width}:{height}. Output canvas: {width}x{height}."
            for index, (ref_width, ref_height) in enumerate(reference_sizes, 1):
                context += f"\nReference Media <Picture {index}>: aspect_ratio {ref_width}:{ref_height}."
            if options.get("instructions"):
                context += "\nAdditional user instructions: " + options["instructions"]
            template["value"] = context + "\n\n" + template["value"]
        elif (values.get("images") or values.get("width") or values.get("prompt_expansion")
              or values.get("reference_mode", "latent") != "latent"
              or (mode == "i2i" and values["aspect_ratio"] != "original")):
            raise ServiceError("unsupported_capability", "This app bundle needs canvas and expansion bindings for these controls.", 422)
        output_nodes = [spec.output_node]
        seeds = [resolved["seed"]]
        sampler_id = spec.bindings["seed"].node
        decode_id = graph[spec.output_node]["inputs"]["images"][0]
        for index in range(1, values.get("n", 1)):
            seed = (resolved["seed"] + index) % 9007199254740992
            clones = {sampler_id: f"sif_sample_{index}_sampler", decode_id: f"sif_sample_{index}_decode",
                      spec.output_node: f"sif_sample_{index}_save"}
            for original, node_id in clones.items():
                graph[node_id] = copy.deepcopy(graph[original])
                for name, value in graph[node_id]["inputs"].items():
                    if isinstance(value, list) and len(value) == 2 and value[0] in clones:
                        graph[node_id]["inputs"][name] = [clones[value[0]], value[1]]
            graph[clones[sampler_id]]["inputs"]["seed"] = seed
            output_nodes.append(clones[spec.output_node])
            seeds.append(seed)
        resolved["seeds"] = seeds
        return graph, resolved, {"revision": revision, "output_node": spec.output_node, "output_nodes": output_nodes,
                                 "prompt_preview_node": spec.prompt_preview_node,
                                 "image_binding": image_binding, "image_bindings": image_bindings}
