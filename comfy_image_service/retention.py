"""Remove expired temporary references, preserving snapshots used by pending jobs."""
import asyncio
import logging


async def maintain_uploads(store, storage, stopped):
    while not stopped.is_set():
        for cleanup in (storage.cleanup_expired, store.cleanup_expired_uploads):
            try:
                await asyncio.to_thread(cleanup)
            except Exception as error:
                logging.getLogger("sif.retention").warning("%s failed (%s)", cleanup.__qualname__, type(error).__name__)
        try:
            await asyncio.wait_for(stopped.wait(), timeout=60)
        except asyncio.TimeoutError:
            pass
