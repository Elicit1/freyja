import os
import io
import json
import base64
import asyncio
import logging
import mimetypes
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple
import httpx
import websockets
import aiofiles

from config import GatewayConfig, NodeConfig

logger = logging.getLogger("comfy_client")


class ComfyAsyncClient:
    """
    Production-grade asynchronous client for a specific ComfyUI backend instance.
    Handles media asset upload (images, videos, audios), prompt queuing,
    WebSocket execution monitoring, and output retrieval.
    """

    def __init__(self, http_url: str, ws_url: str, timeout: float = 300.0):
        self.http_base = http_url.rstrip("/")
        self.ws_base = ws_url.rstrip("/")
        self.timeout = timeout
        self._http_client: Optional[httpx.AsyncClient] = None

    async def get_http_client(self) -> httpx.AsyncClient:
        if self._http_client is None or self._http_client.is_closed:
            self._http_client = httpx.AsyncClient(
                timeout=httpx.Timeout(self.timeout, connect=10.0),
                limits=httpx.Limits(max_keepalive_connections=50, max_connections=100)
            )
        return self._http_client

    async def close(self) -> None:
        if self._http_client and not self._http_client.is_closed:
            await self._http_client.aclose()

    async def check_health(self) -> bool:
        """Check if this ComfyUI backend is reachable"""
        client = await self.get_http_client()
        try:
            resp = await client.get(f"{self.http_base}/system_stats", timeout=3.0)
            return resp.status_code == 200
        except Exception:
            return False

    async def load_and_upload_media(self, media_source: str, default_name: str = "media_upload") -> str:
        """
        Load image/video/audio from URL, local file, or Base64, and upload to ComfyUI /upload/image.
        Returns the filename stored in the ComfyUI input directory.
        """
        raw_bytes: bytes
        filename: str

        media_source = media_source.strip()

        # 1. HTTP / HTTPS URL
        if media_source.startswith("http://") or media_source.startswith("https://"):
            client = await self.get_http_client()
            logger.info(f"[{self.http_base}] Downloading media from URL: {media_source}")
            try:
                resp = await client.get(media_source, follow_redirects=True, timeout=60.0)
                resp.raise_for_status()
                raw_bytes = resp.content
                url_name = Path(media_source.split("?")[0]).name
                filename = url_name if url_name else f"{default_name}.bin"
            except Exception as e:
                raise ValueError(f"Failed to fetch media from URL '{media_source}': {e}")

        # 2. Base64 Data URI or Base64 String
        elif media_source.startswith("data:") or ";base64," in media_source:
            logger.info(f"[{self.http_base}] Decoding media from Base64 Data URI...")
            try:
                header, data = media_source.split(";base64,", 1)
                raw_bytes = base64.b64decode(data)
                mime = header.replace("data:", "").strip()
                ext = mimetypes.guess_extension(mime) or ".bin"
                filename = f"b64_{abs(hash(media_source)) % 100000000}{ext}"
            except Exception as e:
                raise ValueError(f"Invalid Base64 media format: {e}")

        # 3. Local File Path
        else:
            local_path = Path(media_source)
            if not local_path.is_file():
                raise FileNotFoundError(f"Media file not found on server: {media_source}")
            logger.info(f"[{self.http_base}] Reading local media file: {local_path}")
            async with aiofiles.open(local_path, "rb") as f:
                raw_bytes = await f.read()
            filename = local_path.name

        return await self.upload_file_to_comfy(raw_bytes, filename)

    async def load_and_upload_ref_image(self, ref_source: str) -> str:
        """Alias for reference image upload"""
        return await self.load_and_upload_media(ref_source, default_name="ref_download.png")

    async def upload_file_to_comfy(self, raw_bytes: bytes, filename: str) -> str:
        """Upload binary file (image/video/audio) to ComfyUI /upload/image endpoint"""
        client = await self.get_http_client()
        content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        files = {
            "image": (filename, io.BytesIO(raw_bytes), content_type),
            "overwrite": (None, "true"),
            "type": (None, "input")
        }

        try:
            resp = await client.post(f"{self.http_base}/upload/image", files=files)
            resp.raise_for_status()
            data = resp.json()
            uploaded_name = data.get("name", filename)
            logger.info(f"[{self.http_base}] Uploaded file to ComfyUI input: {uploaded_name}")
            return uploaded_name
        except Exception as e:
            logger.error(f"[{self.http_base}] Failed to upload media to ComfyUI: {e}")
            raise RuntimeError(f"ComfyUI file upload error on {self.http_base}: {e}")

    async def submit_prompt(self, workflow_prompt: dict, client_id: str) -> str:
        """Submit workflow dictionary to ComfyUI /prompt endpoint"""
        client = await self.get_http_client()
        payload = {
            "prompt": workflow_prompt,
            "client_id": client_id
        }

        try:
            resp = await client.post(f"{self.http_base}/prompt", json=payload)
            if resp.status_code != 200:
                error_detail = resp.text
                friendly_msg = self._parse_comfy_validation_error(error_detail, workflow_prompt)
                logger.error(f"[{self.http_base}] Prompt submission rejected: {resp.status_code}\n{friendly_msg}")
                raise ValueError(friendly_msg)

            data = resp.json()
            prompt_id = data.get("prompt_id")
            if not prompt_id:
                raise RuntimeError(f"No prompt_id returned by ComfyUI at {self.http_base}: {data}")

            logger.info(f"[{self.http_base}] Prompt successfully submitted. Prompt ID: {prompt_id}, Client ID: {client_id}")
            return prompt_id
        except httpx.RequestError as e:
            raise RuntimeError(f"无法连接到 ComfyUI 实例 ({self.http_base}): {e}")

    def _parse_comfy_validation_error(self, resp_text: str, workflow_prompt: dict) -> str:
        """Parse raw ComfyUI prompt validation errors into user-friendly messages"""
        try:
            err_json = json.loads(resp_text)
            node_errors = err_json.get("node_errors", {})
            if node_errors:
                messages = []
                for node_id, node_info in node_errors.items():
                    class_type = node_info.get("class_type", "UnknownNode")
                    node_title = workflow_prompt.get(str(node_id), {}).get("_meta", {}).get("title", class_type)
                    errors = node_info.get("errors", [])
                    for err in errors:
                        err_type = err.get("type")
                        extra = err.get("extra_info", {})
                        if err_type == "value_not_in_list":
                            input_name = extra.get("input_name", "参数")
                            received = extra.get("received_value", "")
                            input_config = extra.get("input_config", [])
                            available_list = input_config[0] if (input_config and isinstance(input_config[0], list)) else []
                            avail_str = ", ".join(f"'{m}'" for m in available_list) if available_list else "【无任何可用模型】"
                            messages.append(
                                f"❌【ComfyUI 缺失模型文件】\n"
                                f"• 报错节点: 节点 #{node_id} ({node_title} / {class_type})\n"
                                f"• 缺失配置: {input_name} = '{received}' 未在 ComfyUI 中找到！\n"
                                f"• 当前 ComfyUI 目录中已安装的可用模型有:\n  👉 [{avail_str}]\n"
                            )
                        else:
                            messages.append(
                                f"❌ 节点 #{node_id} ({node_title}) 校验失败: {err.get('message', '')} {err.get('details', '')}"
                            )
                if messages:
                    return "\n\n".join(messages)
        except Exception:
            pass
        return f"ComfyUI 任务校验失败 ({resp_text})"

    async def wait_for_completion(
        self,
        prompt_id: str,
        client_id: str,
        timeout_override: Optional[float] = None
    ) -> List[Dict[str, Any]]:
        """
        Connect to ComfyUI WebSocket on this node, monitor progress,
        and capture output assets (videos/gifs/images).
        """
        ws_url = f"{self.ws_base}?clientId={client_id}"
        output_assets: List[Dict[str, Any]] = []
        timeout = timeout_override if timeout_override and timeout_override > 0 else self.timeout

        try:
            logger.info(f"[{self.http_base}] Connecting to WebSocket: {ws_url} (timeout: {timeout}s)")
            async with websockets.connect(ws_url, open_timeout=10.0, max_size=100 * 1024 * 1024) as ws:
                start_time = asyncio.get_event_loop().time()

                while True:
                    elapsed = asyncio.get_event_loop().time() - start_time
                    if elapsed > timeout:
                        raise TimeoutError(f"ComfyUI generation on {self.http_base} timed out after {timeout}s.")

                    try:
                        message = await asyncio.wait_for(ws.recv(), timeout=5.0)
                    except asyncio.TimeoutError:
                        continue

                    if isinstance(message, bytes):
                        continue

                    data = json.loads(message)
                    msg_type = data.get("type")
                    msg_data = data.get("data", {})

                    if msg_type == "progress":
                        val = msg_data.get("value")
                        max_val = msg_data.get("max")
                        node_id = msg_data.get("node")
                        logger.info(f"⏳ [{self.http_base}] Node {node_id}: {val}/{max_val} steps")

                    elif msg_type == "executing":
                        node_id = msg_data.get("node")
                        curr_prompt_id = msg_data.get("prompt_id")
                        # 当工作流完成时，ComfyUI 发送 node 为 None（此时 prompt_id 可能是 null 或对应 prompt_id）
                        if node_id is None and (curr_prompt_id is None or curr_prompt_id == prompt_id):
                            logger.info(f"✅ [{self.http_base}] ComfyUI execution finished for prompt {prompt_id}")
                            break
                        elif curr_prompt_id == prompt_id:
                            logger.info(f"⚙️ [{self.http_base}] Running node ID: {node_id}")

                    elif msg_type == "executed":
                        curr_prompt_id = msg_data.get("prompt_id")
                        if curr_prompt_id == prompt_id:
                            output = msg_data.get("output", {})
                            for asset_key in ("videos", "gifs", "images", "video"):
                                if asset_key in output and isinstance(output[asset_key], list):
                                    captured = output[asset_key]
                                    logger.info(f"🎬 [{self.http_base}] Found {len(captured)} generated {asset_key} asset(s)")
                                    output_assets.extend(captured)

                    elif msg_type == "execution_error":
                        if msg_data.get("prompt_id") == prompt_id:
                            node_id = msg_data.get("node_id")
                            node_type = msg_data.get("node_type")
                            err = msg_data.get("exception_message")
                            raise RuntimeError(f"ComfyUI error on {self.http_base} in node {node_id} ({node_type}): {err}")

                    elif msg_type == "execution_interrupted":
                        if msg_data.get("prompt_id") == prompt_id:
                            raise RuntimeError(f"ComfyUI execution on {self.http_base} was interrupted.")

        except (websockets.exceptions.WebSocketException, OSError, TimeoutError) as e:
            logger.warning(f"WebSocket error on {self.http_base} ({e}). Falling back to HTTP history polling...")
            output_assets = await self._poll_history_fallback(prompt_id)

        if not output_assets:
            output_assets = await self._poll_history_fallback(prompt_id)

        if not output_assets:
            raise RuntimeError(f"Workflow completed on {self.http_base} but no output assets were found for prompt {prompt_id}")

        return output_assets

    async def _poll_history_fallback(self, prompt_id: str, max_retries: int = 15) -> List[Dict[str, Any]]:
        client = await self.get_http_client()
        logger.info(f"[{self.http_base}] Polling history for prompt {prompt_id}...")

        for _ in range(max_retries):
            try:
                resp = await client.get(f"{self.http_base}/history/{prompt_id}", timeout=5.0)
                if resp.status_code == 200:
                    history = resp.json()
                    if prompt_id in history:
                        outputs = history[prompt_id].get("outputs", {})
                        assets = []
                        for node_out in outputs.values():
                            for asset_key in ("videos", "gifs", "images"):
                                if asset_key in node_out and isinstance(node_out[asset_key], list):
                                    assets.extend(node_out[asset_key])
                        if assets:
                            return assets
            except Exception as e:
                logger.debug(f"History poll error on {self.http_base}: {e}")
            await asyncio.sleep(1.0)

        return []

    async def cancel_job(self, prompt_id: str) -> Tuple[bool, str]:
        """
        Precise task cancellation for a specific prompt_id:
        1. Try modern ComfyUI endpoint: POST /api/jobs/{prompt_id}/cancel
        2. Fallback to /queue inspection:
           - In queue_pending -> POST /queue {"delete": [prompt_id]} (dequeue, do NOT interrupt running tasks)
           - In queue_running -> POST /interrupt {"prompt_id": prompt_id} (safely interrupt only when target is executing)
           - In history -> ALREADY_FINISHED
           - Not found -> NOT_FOUND
        """
        if not prompt_id:
            return False, "EMPTY_PROMPT_ID"

        client = await self.get_http_client()

        # 1. 尝试新版 ComfyUI 官方原生端点 /api/jobs/{job_id}/cancel
        try:
            resp = await client.post(f"{self.http_base}/api/jobs/{prompt_id}/cancel", timeout=3.0)
            if resp.status_code == 200:
                logger.info(f"[{self.http_base}] Successfully cancelled job {prompt_id} via /api/jobs/cancel")
                return True, "CANCELLED"
            elif resp.status_code in (404, 405):
                logger.debug(f"[{self.http_base}] /api/jobs/cancel endpoint unavailable ({resp.status_code}), falling back to queue check")
            else:
                logger.warning(f"[{self.http_base}] /api/jobs/cancel returned status {resp.status_code}")
        except Exception as pe:
            logger.debug(f"[{self.http_base}] /api/jobs/cancel probe warning: {pe}")

        # 2. 审查 /queue 状态
        try:
            q_resp = await client.get(f"{self.http_base}/queue", timeout=3.0)
            q_resp.raise_for_status()
            queue_data = q_resp.json()
        except Exception as qe:
            logger.error(f"[{self.http_base}] Failed to query /queue: {qe}")
            return False, f"COMFY_UNREACHABLE: {qe}"

        # 2a. 排队中任务精准出队 (绝不误伤运行中的其他任务)
        queue_pending = queue_data.get("queue_pending", [])
        is_pending = any(len(item) > 1 and str(item[1]) == str(prompt_id) for item in queue_pending)
        if is_pending:
            try:
                del_resp = await client.post(f"{self.http_base}/queue", json={"delete": [prompt_id]}, timeout=3.0)
                if del_resp.status_code == 200:
                    logger.info(f"[{self.http_base}] Prompt {prompt_id} successfully dequeued from queue_pending")
                    return True, "QUEUED_TASK_DELETED"
                else:
                    logger.warning(f"[{self.http_base}] Failed to delete prompt from queue, status={del_resp.status_code}")
                    return False, f"QUEUE_DELETE_FAILED: {del_resp.status_code}"
            except Exception as de:
                logger.error(f"[{self.http_base}] Error deleting prompt {prompt_id} from queue: {de}")
                return False, f"QUEUE_DELETE_ERROR: {de}"

        # 2b. 运行中任务精准中断 (已验证当前正在 GPU 上计算的正是目标 prompt_id)
        queue_running = queue_data.get("queue_running", [])
        is_running = any(len(item) > 1 and str(item[1]) == str(prompt_id) for item in queue_running)
        if is_running:
            try:
                int_resp = await client.post(f"{self.http_base}/interrupt", json={"prompt_id": prompt_id}, timeout=3.0)
                if int_resp.status_code == 200:
                    logger.info(f"🛑 [{self.http_base}] Successfully interrupted running prompt {prompt_id}")
                    return True, "RUNNING_TASK_INTERRUPTED"
                else:
                    logger.warning(f"[{self.http_base}] /interrupt returned status {int_resp.status_code}")
                    return False, f"INTERRUPT_FAILED: {int_resp.status_code}"
            except Exception as ie:
                logger.error(f"[{self.http_base}] Failed to send /interrupt for prompt {prompt_id}: {ie}")
                return False, f"INTERRUPT_ERROR: {ie}"

        # 2c. 检查历史记录 (是否已提早生成完成)
        try:
            h_resp = await client.get(f"{self.http_base}/history/{prompt_id}", timeout=3.0)
            if h_resp.status_code == 200:
                history = h_resp.json()
                if prompt_id in history:
                    logger.info(f"[{self.http_base}] Prompt {prompt_id} already finished in history")
                    return False, "ALREADY_FINISHED"
        except Exception as he:
            logger.debug(f"[{self.http_base}] History check error: {he}")

        logger.info(f"[{self.http_base}] Prompt {prompt_id} not found in pending, running, or history")
        return False, "NOT_FOUND"

    async def interrupt(self, prompt_id: Optional[str] = None) -> bool:
        """
        Global emergency interrupt signal (reserved for admin emergency stops).
        """
        client = await self.get_http_client()
        success = False
        try:
            resp = await client.post(f"{self.http_base}/interrupt", timeout=3.0)
            if resp.status_code == 200:
                logger.info(f"🛑 [{self.http_base}] Successfully sent emergency /interrupt signal to ComfyUI")
                success = True
            else:
                logger.warning(f"[{self.http_base}] Emergency /interrupt returned status {resp.status_code}")
        except Exception as e:
            logger.error(f"[{self.http_base}] Failed to send emergency interrupt to ComfyUI: {e}")
        return success

    async def fetch_asset_bytes(self, filename: str, subfolder: str = "", type_name: str = "output") -> bytes:
        """Fetch media asset binary from ComfyUI /view endpoint"""
        client = await self.get_http_client()
        params = {
            "filename": filename,
            "subfolder": subfolder,
            "type": type_name
        }
        resp = await client.get(f"{self.http_base}/view", params=params)
        resp.raise_for_status()
        return resp.content


class ComfyClientPool:
    """
    Manages ComfyAsyncClient instances across multiple compute nodes.
    Supports pooling, health checking, and dynamic node connections.
    """

    def __init__(self, config: GatewayConfig):
        self.config = config
        self._clients: Dict[str, ComfyAsyncClient] = {}
        self._lock = asyncio.Lock()

    async def interrupt_all(self, prompt_id: Optional[str] = None) -> Dict[str, bool]:
        """Broadcast interrupt signal to all registered nodes"""
        results = {}
        for node_id, node in self.config.nodes.items():
            if not node.enabled:
                continue
            try:
                client = await self.get_client_for_node(node)
                results[node_id] = await client.interrupt(prompt_id)
            except Exception as e:
                logger.warning(f"Error interrupting node {node_id}: {e}")
                results[node_id] = False
        return results

    async def get_client_for_node(self, node: NodeConfig) -> ComfyAsyncClient:
        """Get or create client for a given NodeConfig"""
        return await self.get_client_by_url(
            http_url=node.http_url,
            ws_url=node.get_ws_url(),
            timeout=node.timeout_seconds or self.config.server.timeout_seconds
        )

    async def get_client_by_url(self, http_url: str, ws_url: Optional[str] = None, timeout: float = 300.0) -> ComfyAsyncClient:
        """Get or create client given http_url and optional ws_url"""
        http_base = http_url.strip().rstrip("/")
        if not ws_url:
            if http_base.startswith("https://"):
                ws_url = http_base.replace("https://", "wss://") + "/ws"
            else:
                ws_url = http_base.replace("http://", "ws://") + "/ws"

        async with self._lock:
            if http_base not in self._clients:
                logger.info(f"Creating new ComfyAsyncClient instance for {http_base} (ws: {ws_url})")
                self._clients[http_base] = ComfyAsyncClient(http_base, ws_url, timeout=timeout)
            return self._clients[http_base]

    async def check_all_health(self) -> Dict[str, bool]:
        """Check health status of all registered compute nodes"""
        health_results = {}
        for node_id, node in self.config.nodes.items():
            if not node.enabled:
                health_results[node_id] = False
                continue
            client = await self.get_client_for_node(node)
            is_ok = await client.check_health()
            health_results[node_id] = is_ok
        return health_results

    async def close_all(self) -> None:
        """Close all pooled clients"""
        async with self._lock:
            for http_base, client in self._clients.items():
                try:
                    await client.close()
                except Exception as e:
                    logger.warning(f"Error closing client for {http_base}: {e}")
            self._clients.clear()
