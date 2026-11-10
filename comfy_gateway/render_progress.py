"""Observe the exact submitted prompt. Progress never authorizes task completion."""
import asyncio
import logging
import time
from task_tracker import task_tracker

logger = logging.getLogger(__name__)
_observers = set()


async def follow(client, prompt_id, client_id, workflow, timeout):
    completed = set()
    current = None
    snapshot = {"totalNodes": len(workflow), "completedNodes": 0}

    async def changed(kind, data):
        nonlocal current
        node = data.get("node")
        if kind == "execution_cached":
            completed.update(str(n) for n in data.get("nodes", []) if str(n) in workflow)
        elif kind == "executing":
            if current is not None:
                completed.add(current)
            current = str(node) if node is not None else None
            snapshot.pop("value", None)
            snapshot.pop("max", None)
        if node is not None:
            key = str(node)
            snapshot.update(nodeId=key, nodeType=workflow.get(key, {}).get("class_type", ""))
        if kind == "progress":
            value, maximum = data.get("value"), data.get("max")
            if isinstance(value, (int, float)) and isinstance(maximum, (int, float)) and maximum > 0:
                snapshot.update(value=max(0, min(value, maximum)), max=maximum)
        snapshot.update(completedNodes=len(completed), updatedAt=time.time())
        await task_tracker.record_progress(prompt_id, snapshot)

    client._progress_listeners[prompt_id] = changed
    try:
        return await client.wait_for_completion(prompt_id, client_id, timeout_override=timeout)
    finally:
        client._progress_listeners.pop(prompt_id, None)


def start(client, prompt_id, client_id, workflow, timeout):
    async def observe():
        try:
            await follow(client, prompt_id, client_id, workflow, timeout)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            # HTTP history/queue reconciliation remains authoritative.
            logger.info("Progress observer stopped: prompt=%s error=%s", prompt_id, type(error).__name__)
    task = asyncio.create_task(observe())
    _observers.add(task)
    task.add_done_callback(_observers.discard)
