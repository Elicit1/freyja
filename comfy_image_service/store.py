import json
import sqlite3
import threading
import time
import uuid
from pathlib import Path

from .models import ServiceError


class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.directory / "jobs.sqlite3", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, status TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL,
                fingerprint TEXT NOT NULL, idempotency_key TEXT UNIQUE, request TEXT NOT NULL,
                graph TEXT NOT NULL, metadata TEXT NOT NULL, prompt_id TEXT, result TEXT, error TEXT
            );
            CREATE TABLE IF NOT EXISTS uploads (
                id TEXT PRIMARY KEY, path TEXT NOT NULL, sha256 TEXT NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, created REAL NOT NULL
            );
        """)
        self.db.commit()

    def close(self):
        self.db.close()

    def upload(self, upload_id):
        with self.lock:
            row = self.db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
        if row is None:
            raise ServiceError("upload_not_found", "Upload an image before submitting i2i.", 404)
        return dict(row)

    def save_upload(self, upload_id, path, sha256, width, height):
        with self.lock, self.db:
            self.db.execute("INSERT INTO uploads VALUES (?,?,?,?,?,?)",
                            (upload_id, str(path), sha256, width, height, time.time()))
        return self.upload(upload_id)

    def create_job(self, values, graph, metadata, fingerprint, idempotency_key, limit):
        with self.lock, self.db:
            if idempotency_key:
                existing = self.db.execute("SELECT * FROM jobs WHERE idempotency_key=?", (idempotency_key,)).fetchone()
                if existing:
                    if existing["fingerprint"] != fingerprint:
                        raise ServiceError("idempotency_conflict", "The idempotency key was used for different parameters.", 409)
                    return self.decode(existing), False
            pending = self.db.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','preparing','running','unknown')").fetchone()[0]
            if pending >= limit:
                raise ServiceError("queue_full", "The independent image service queue is full.", 429)
            job_id = str(uuid.uuid4())
            now = time.time()
            self.db.execute("INSERT INTO jobs(id,status,created,updated,fingerprint,idempotency_key,request,graph,metadata) VALUES (?,?,?,?,?,?,?,?,?)",
                            (job_id, "queued", now, now, fingerprint, idempotency_key,
                             json.dumps(values), json.dumps(graph), json.dumps(metadata)))
        return self.get_job(job_id), True

    def idempotent_job(self, key, fingerprint):
        if not key:
            return None
        with self.lock:
            row = self.db.execute("SELECT * FROM jobs WHERE idempotency_key=?", (key,)).fetchone()
        if row is None:
            return None
        if row["fingerprint"] != fingerprint:
            raise ServiceError("idempotency_conflict", "The idempotency key was used for different parameters.", 409)
        return self.decode(row)

    @staticmethod
    def decode(row):
        data = dict(row)
        for key in ("request", "graph", "metadata", "result", "error"):
            if data[key] is not None:
                data[key] = json.loads(data[key])
        return data

    def get_job(self, job_id):
        with self.lock:
            row = self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None:
            raise ServiceError("job_not_found", "Job does not exist.", 404)
        return self.decode(row)

    def jobs(self, statuses=None, limit=100):
        sql, values = "SELECT * FROM jobs", []
        if statuses:
            sql += " WHERE status IN (" + ",".join("?" for _ in statuses) + ")"
            values.extend(statuses)
        sql += " ORDER BY rowid DESC LIMIT ?"
        values.append(limit)
        with self.lock:
            return [self.decode(row) for row in self.db.execute(sql, values).fetchall()]

    def next_job(self):
        with self.lock:
            row = self.db.execute("SELECT * FROM jobs WHERE status IN ('queued','running') ORDER BY CASE status WHEN 'running' THEN 0 ELSE 1 END,rowid LIMIT 1").fetchone()
        return self.decode(row) if row else None

    def queue_position(self, job_id):
        with self.lock:
            row = self.db.execute("SELECT status,rowid AS sequence FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row or row["status"] != "queued":
                return None
            return self.db.execute("SELECT count(*) FROM jobs WHERE status='queued' AND rowid<=?", (row["sequence"],)).fetchone()[0]

    def queue_summary(self):
        with self.lock:
            counts = dict(self.db.execute("SELECT status,count(*) FROM jobs GROUP BY status"))
        return {"concurrency": 1, "active": counts.get("preparing", 0) + counts.get("running", 0),
                "waiting": counts.get("queued", 0), "blocked": counts.get("unknown", 0),
                "completed": counts.get("succeeded", 0), "failed": counts.get("failed", 0)}

    def claim(self, job_id):
        with self.lock, self.db:
            return self.db.execute("UPDATE jobs SET status='preparing',updated=? WHERE id=? AND status='queued'",
                                   (time.time(), job_id)).rowcount == 1

    def update(self, job_id, status, *, prompt_id=None, result=None, error=None):
        with self.lock, self.db:
            changed = self.db.execute("""UPDATE jobs SET status=?,updated=?,prompt_id=COALESCE(?,prompt_id),result=?,error=? WHERE id=?""",
                                     (status, time.time(), prompt_id, json.dumps(result) if result is not None else None,
                                      json.dumps(error) if error is not None else None, job_id)).rowcount
            if changed != 1:
                raise RuntimeError("Job persistence failed: job does not exist.")
