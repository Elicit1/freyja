"""Prevent two processes from rendering jobs from the same SIF data directory."""
import os


class WorkerLock:
    def __init__(self, directory):
        self.path = directory / "worker.lock"
        self.stream = None

    def acquire(self):
        stream = self.path.open("a+b")
        if stream.tell() == 0:
            stream.write(b"\0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            stream.close()
            raise RuntimeError("SIF already has a worker for this data directory. Run exactly one service process.") from error
        self.stream = stream

    def release(self):
        if self.stream:
            self.stream.close()
            self.stream = None
