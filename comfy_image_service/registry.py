"""Discover registered app bundles from disk; no dependency on FREYJA or its gateway."""
import copy
import hashlib
import json
import random
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .models import ASPECT_RATIOS, ServiceError


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
            required = {"prompt", "seed", "megapixels", "expand_prompt"}
            required.add("image" if mode == "i2i" else "aspect_ratio")
            if not required.issubset(config.bindings):
                raise ValueError(f"{mode} is missing required bindings: {sorted(required - set(config.bindings))}")
            if set(config.bindings) - {"prompt", "seed", "megapixels", "expand_prompt", "image", "aspect_ratio"}:
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

    def assemble(self, values):
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
                value = ASPECT_RATIOS[value]
            graph[binding.node]["inputs"][binding.input] = value
        return graph, resolved, {"revision": revision, "output_node": spec.output_node,
                                 "prompt_preview_node": spec.prompt_preview_node,
                                 "image_binding": spec.bindings["image"].model_dump() if mode == "i2i" else None}
