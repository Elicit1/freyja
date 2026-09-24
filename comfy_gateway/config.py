import os
import glob
import logging
import yaml
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
from pydantic import BaseModel, Field

logger = logging.getLogger("config")


class ServerConfig(BaseModel):
    host: str = Field(default="0.0.0.0", description="Gateway bind host")
    port: int = Field(default=8000, description="Gateway bind port")
    comfyui_http_url: str = Field(default="http://127.0.0.1:8188", description="Default/Fallback ComfyUI HTTP base URL")
    comfyui_ws_url: str = Field(default="ws://127.0.0.1:8188/ws", description="Default/Fallback ComfyUI WebSocket base URL")
    timeout_seconds: float = Field(default=1800.0, description="Execution timeout in seconds")
    output_dir: str = Field(default="static/outputs", description="Directory to store rendered images/videos")
    base_url: str = Field(default="http://127.0.0.1:8000", description="Base URL for media download links")


class HandlerConfig(BaseModel):
    vae_node_id: Optional[str] = Field(default="8", description="Node ID of VAE Loader")
    sampler_node_id: Optional[str] = Field(default="7", description="Node ID of KSampler")
    sampler_cond_param: Optional[str] = Field(default="positive", description="Conditioning parameter name in sampler")
    base_conditioning_source: Optional[List[Any]] = Field(
        default_factory=lambda: ["4", 0],
        description="Base conditioning connection [node_id, slot]"
    )
    reference_node_type: Optional[str] = Field(default="ReferenceLatent", description="Reference node class type")


class ModelConfig(BaseModel):
    model_name: str = Field(..., description="OpenAI model identifier")
    description: Optional[str] = Field(default="", description="Model description")
    template_file: str = Field(..., description="Relative or absolute path to workflow JSON template")
    handler: str = Field(default="standard", description="Handler identifier: flux2_reference_chain, latentsync_lipsync, etc.")
    default_steps: Optional[int] = Field(default=20, description="Default sampling steps if not provided in request")
    default_cfg: Optional[float] = Field(default=1.0, description="Default CFG scale")
    mappings: Dict[str, List[str]] = Field(
        default_factory=dict,
        description="Mapping of parameter names to [NodeID, key1, key2...]"
    )
    handler_config: Optional[HandlerConfig] = Field(default=None, description="Handler specific parameters")
    timeout_seconds: Optional[float] = Field(default=None, description="Per-model execution timeout override in seconds")
    node_id: Optional[str] = Field(default=None, description="Bound compute node ID")


class NodeConfig(BaseModel):
    node_id: str = Field(..., description="Unique compute node identifier, e.g. machine_5060")
    name: Optional[str] = Field(default="", description="Human-readable node description")
    enabled: bool = Field(default=True, description="Whether this node is active")
    http_url: str = Field(..., description="ComfyUI HTTP base URL, e.g. http://192.168.1.120:8188")
    ws_url: Optional[str] = Field(default=None, description="ComfyUI WebSocket URL")
    timeout_seconds: Optional[float] = Field(default=300.0, description="Node default timeout")
    models: List[ModelConfig] = Field(default_factory=list, description="Models hosted on this node")

    def get_ws_url(self) -> str:
        if self.ws_url and self.ws_url.strip():
            return self.ws_url.strip().rstrip("/")
        # Derive WebSocket URL automatically from http_url
        base = self.http_url.strip().rstrip("/")
        if base.startswith("https://"):
            return base.replace("https://", "wss://") + "/ws"
        elif base.startswith("http://"):
            return base.replace("http://", "ws://") + "/ws"
        return f"ws://{base}/ws"


class GatewayConfig(BaseModel):
    server: ServerConfig = Field(default_factory=ServerConfig)
    nodes_dir: str = Field(default="nodes", description="Directory to scan for node YAML configurations")
    nodes: Dict[str, NodeConfig] = Field(default_factory=dict)
    models: List[ModelConfig] = Field(default_factory=list)
    model_to_nodes: Dict[str, List[str]] = Field(default_factory=dict)

    def get_model(self, model_name: str) -> Optional[ModelConfig]:
        """Find model configuration by name (case-insensitive with fallback partial matching)"""
        target = model_name.strip().lower()
        for m in self.models:
            if m.model_name.lower() == target:
                return m
        for m in self.models:
            if target in m.model_name.lower() or m.model_name.lower() in target:
                return m
        return None

    def get_node(self, node_id: str) -> Optional[NodeConfig]:
        """Get compute node by node_id"""
        return self.nodes.get(node_id)

    def get_node_for_model(self, model_name: str, preferred_node: Optional[str] = None) -> Optional[NodeConfig]:
        """Find active compute node for a model, with support for preferred node selection"""
        target = model_name.strip().lower()
        matched_model = self.get_model(model_name)
        if not matched_model:
            return None

        actual_name = matched_model.model_name.lower()
        candidate_node_ids = self.model_to_nodes.get(actual_name, [])

        if not candidate_node_ids:
            # Check if model has a node_id set
            if matched_model.node_id and matched_model.node_id in self.nodes:
                return self.nodes[matched_model.node_id]
            # Fallback to default node or first available node
            if "default" in self.nodes:
                return self.nodes["default"]
            if self.nodes:
                return next(iter(self.nodes.values()))
            return None

        # 1. Preferred node specified
        if preferred_node and preferred_node in candidate_node_ids and preferred_node in self.nodes:
            node = self.nodes[preferred_node]
            if node.enabled:
                return node

        # 2. Return first enabled node
        for nid in candidate_node_ids:
            if nid in self.nodes and self.nodes[nid].enabled:
                return self.nodes[nid]

        return None


_config_cache: Optional[GatewayConfig] = None


def get_base_dir() -> Path:
    return Path(__file__).resolve().parent


def load_config(config_path: Optional[str] = None) -> GatewayConfig:
    global _config_cache
    if _config_cache is not None and config_path is None:
        return _config_cache

    base_dir = get_base_dir()

    # 1. Auto-load .env if available in base_dir or root
    for env_candidate in [base_dir / ".env", base_dir.parent / ".env"]:
        if env_candidate.exists():
            try:
                with open(env_candidate, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            k, v = k.strip(), v.strip().strip("\"'")
                            if k not in os.environ:
                                os.environ[k] = v
            except Exception as e:
                logger.warning(f"Failed to read env file {env_candidate}: {e}")

    if config_path is None:
        env_path = os.getenv("GATEWAY_CONFIG_PATH")
        if env_path:
            target_path = Path(env_path)
        else:
            target_path = base_dir / "workflows.yaml"
    else:
        target_path = Path(config_path)

    raw_yaml = {}
    if target_path.exists():
        with open(target_path, "r", encoding="utf-8") as f:
            raw_yaml = yaml.safe_load(f) or {}

    # Merge local overrides if workflows.local.yaml exists
    local_workflows = base_dir / "workflows.local.yaml"
    if not local_workflows.exists():
        local_workflows = base_dir / "workflows.local.yml"
    if local_workflows.exists():
        try:
            with open(local_workflows, "r", encoding="utf-8") as f:
                local_raw = yaml.safe_load(f) or {}
                for k, v in local_raw.items():
                    if isinstance(v, dict) and k in raw_yaml and isinstance(raw_yaml[k], dict):
                        raw_yaml[k].update(v)
                    else:
                        raw_yaml[k] = v
            logger.info(f"Loaded local configuration overrides from {local_workflows.name}")
        except Exception as e:
            logger.warning(f"Failed to load {local_workflows.name}: {e}")

    config = GatewayConfig(**raw_yaml)

    # Allow environment variables to override server settings
    if "GATEWAY_PORT" in os.environ:
        config.server.port = int(os.environ["GATEWAY_PORT"])
    if "GATEWAY_HOST" in os.environ:
        config.server.host = os.environ["GATEWAY_HOST"]
    if "BASE_URL" in os.environ:
        config.server.base_url = os.environ["BASE_URL"].rstrip("/")

    # Ensure output_dir is absolute or resolved against base_dir
    output_path = Path(config.server.output_dir)
    if not output_path.is_absolute():
        config.server.output_dir = str((base_dir / output_path).resolve())
    os.makedirs(config.server.output_dir, exist_ok=True)

    # Scan nodes_dir for node YAML configs
    nodes_dir_path = Path(config.nodes_dir)
    if not nodes_dir_path.is_absolute():
        nodes_dir_path = base_dir / nodes_dir_path

    config.nodes = {}
    config.models = []
    config.model_to_nodes = {}

    if nodes_dir_path.exists() and nodes_dir_path.is_dir():
        logger.info(f"Scanning compute nodes in: {nodes_dir_path.resolve()}")
        node_files = []
        for pattern in ("*.yaml", "*.yml"):
            node_files.extend(nodes_dir_path.glob(pattern))
        # Ensure *local* files run last to take precedence
        node_files.sort(key=lambda p: (1 if "local" in p.name.lower() else 0, p.name))

        for node_file in node_files:
            try:
                with open(node_file, "r", encoding="utf-8") as f:
                    node_raw = yaml.safe_load(f) or {}
                node = NodeConfig(**node_raw)
                if not node.enabled:
                    logger.info(f"Skipping disabled compute node '{node.node_id}' ({node_file.name})")
                    continue

                # If node already exists, clear its previous models to avoid duplication
                if node.node_id in config.nodes:
                    logger.info(f"Overriding compute node '{node.node_id}' with configuration from {node_file.name}")
                    config.models = [m for m in config.models if m.node_id != node.node_id]
                    for m_list in config.model_to_nodes.values():
                        if node.node_id in m_list:
                            m_list.remove(node.node_id)

                # Register node
                config.nodes[node.node_id] = node
                logger.info(f"Registered compute node '{node.node_id}' ({node.name}) at {node.http_url}")

                # Register models under this node
                for model in node.models:
                    model.node_id = node.node_id
                    config.models.append(model)
                    m_key = model.model_name.lower()
                    config.model_to_nodes.setdefault(m_key, []).append(node.node_id)
                    logger.info(f"  -> Model '{model.model_name}' bound to node '{node.node_id}'")
            except Exception as e:
                logger.error(f"Failed to load node config from {node_file}: {e}")
    else:
        logger.warning(f"Nodes directory not found: {nodes_dir_path.resolve()}")

    # Backwards compatibility: If any models were specified directly in workflows.yaml, merge them
    if "models" in raw_yaml and isinstance(raw_yaml["models"], list):
        for m_raw in raw_yaml["models"]:
            m = ModelConfig(**m_raw)
            if not any(x.model_name.lower() == m.model_name.lower() for x in config.models):
                config.models.append(m)

    # Allow environment variable override for compute node ComfyUI URL
    comfy_env_url = os.getenv("COMFYUI_HTTP_URL")
    if comfy_env_url:
        target_node = config.nodes.get("default")
        if not target_node and config.nodes:
            target_node = next(iter(config.nodes.values()))
        if target_node:
            target_node.http_url = comfy_env_url.strip()
            if os.getenv("COMFYUI_WS_URL"):
                target_node.ws_url = os.getenv("COMFYUI_WS_URL").strip()
            else:
                target_node.ws_url = target_node.get_ws_url()
            logger.info(f"Overridden compute node '{target_node.node_id}' ComfyUI URL to {target_node.http_url} (WS: {target_node.ws_url})")

    _config_cache = config
    return config


def get_config() -> GatewayConfig:
    global _config_cache
    if _config_cache is None:
        _config_cache = load_config()
    return _config_cache
