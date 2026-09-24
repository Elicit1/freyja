import os
import time
import logging
from typing import Optional, Dict, Tuple, Any

logger = logging.getLogger("task_tracker")

try:
    import redis.asyncio as aioredis
    HAS_REDIS = True
except ImportError:
    HAS_REDIS = False
    logger.info("[TaskTracker] redis-py not installed, fallback to in-memory tracking")


class TaskTracker:
    """
    Tracks active generation tasks across Gateway workers.
    Supports Redis persistence with automatic fallback to memory.
    """

    def __init__(self):
        self._redis_client: Optional[Any] = None
        self._memory_tasks: Dict[str, Dict[str, Any]] = {}
        self._memory_index: Dict[str, str] = {}  # alias_key -> task_id or prompt_id
        self._init_redis()

    def _init_redis(self):
        if not HAS_REDIS:
            return
        redis_host = os.getenv("REDIS_HOST", "127.0.0.1")
        redis_port = int(os.getenv("REDIS_PORT", "6379"))
        redis_password = os.getenv("REDIS_PASSWORD", "123456")
        redis_db = int(os.getenv("REDIS_DB", "0"))
        try:
            self._redis_client = aioredis.Redis(
                host=redis_host,
                port=redis_port,
                password=redis_password if redis_password else None,
                db=redis_db,
                decode_responses=True,
                socket_connect_timeout=2.0
            )
            logger.info(f"[TaskTracker] Connected to Redis at {redis_host}:{redis_port}")
        except Exception as e:
            logger.warning(f"[TaskTracker] Redis init failed ({e}), falling back to memory")
            self._redis_client = None

    async def register(
        self,
        prompt_id: str,
        node_id: str,
        task_id: Optional[str] = None,
        shot_id: Optional[str] = None,
        ttl: int = 7200
    ) -> None:
        """Register active task metadata with TTL"""
        now_ts = str(time.time())
        data = {
            "prompt_id": str(prompt_id),
            "node_id": str(node_id),
            "task_id": str(task_id or ""),
            "shot_id": str(shot_id or ""),
            "status": "RUNNING",
            "created_at": now_ts
        }

        # 1. 尝试写入 Redis
        if self._redis_client:
            try:
                pipe = self._redis_client.pipeline()
                if task_id:
                    t_key = f"comfy:task:{task_id}"
                    pipe.hset(t_key, mapping=data)
                    pipe.expire(t_key, ttl)
                if shot_id:
                    s_key = f"comfy:shot:{shot_id}"
                    pipe.set(s_key, task_id or prompt_id, ex=ttl)
                p_key = f"comfy:prompt:{prompt_id}"
                pipe.set(p_key, task_id or prompt_id, ex=ttl)
                await pipe.execute()
            except Exception as e:
                logger.warning(f"[TaskTracker] Redis register error ({e}), writing to memory")

        # 2. 内存双写兜底
        primary_id = task_id or prompt_id
        self._memory_tasks[primary_id] = data
        if task_id:
            self._memory_index[f"task:{task_id}"] = primary_id
        if shot_id:
            self._memory_index[f"shot:{shot_id}"] = primary_id
        self._memory_index[f"prompt:{prompt_id}"] = primary_id

    async def find(
        self,
        task_id: Optional[str] = None,
        shot_id: Optional[str] = None,
        prompt_id: Optional[str] = None
    ) -> Optional[Tuple[str, str]]:
        """
        Find (prompt_id, node_id) by task_id, shot_id, or prompt_id.
        Priority: task_id -> shot_id -> prompt_id.
        """
        # 1. 优先从 Redis 查找
        if self._redis_client:
            try:
                target_key = None
                if task_id:
                    target_key = f"comfy:task:{task_id}"
                elif shot_id:
                    mapped_tid = await self._redis_client.get(f"comfy:shot:{shot_id}")
                    if mapped_tid:
                        target_key = f"comfy:task:{mapped_tid}"
                elif prompt_id:
                    mapped_tid = await self._redis_client.get(f"comfy:prompt:{prompt_id}")
                    if mapped_tid:
                        target_key = f"comfy:task:{mapped_tid}"

                if target_key:
                    task_data = await self._redis_client.hgetall(target_key)
                    if task_data and "prompt_id" in task_data and "node_id" in task_data:
                        return task_data["prompt_id"], task_data["node_id"]
            except Exception as e:
                logger.warning(f"[TaskTracker] Redis find error ({e}), falling back to memory")

        # 2. 从内存查找兜底
        primary_id = None
        if task_id and f"task:{task_id}" in self._memory_index:
            primary_id = self._memory_index[f"task:{task_id}"]
        elif shot_id and f"shot:{shot_id}" in self._memory_index:
            primary_id = self._memory_index[f"shot:{shot_id}"]
        elif prompt_id and f"prompt:{prompt_id}" in self._memory_index:
            primary_id = self._memory_index[f"prompt:{prompt_id}"]

        if primary_id and primary_id in self._memory_tasks:
            entry = self._memory_tasks[primary_id]
            return entry.get("prompt_id"), entry.get("node_id")

        return None

    async def unregister(
        self,
        prompt_id: str,
        task_id: Optional[str] = None,
        shot_id: Optional[str] = None
    ) -> None:
        """Clean up task tracking on completion or cancellation"""
        if self._redis_client:
            try:
                keys_to_del = [f"comfy:prompt:{prompt_id}"]
                if task_id:
                    keys_to_del.append(f"comfy:task:{task_id}")
                if shot_id:
                    keys_to_del.append(f"comfy:shot:{shot_id}")
                await self._redis_client.delete(*keys_to_del)
            except Exception as e:
                logger.debug(f"[TaskTracker] Redis cleanup warning: {e}")

        primary_id = task_id or prompt_id
        self._memory_tasks.pop(primary_id, None)
        if task_id:
            self._memory_index.pop(f"task:{task_id}", None)
        if shot_id:
            self._memory_index.pop(f"shot:{shot_id}", None)
        self._memory_index.pop(f"prompt:{prompt_id}", None)


# Global singleton instance
task_tracker = TaskTracker()
