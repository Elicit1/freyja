"""Password login and individually revocable API keys. Only hashes are persisted."""
import hashlib
import hmac
import logging
import secrets
import time
import uuid
from collections import defaultdict, deque

from .models import ServiceError


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def hash_password(password, salt=None):
    salt = salt or secrets.token_hex(16)
    value = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1, maxmem=64 * 1024 * 1024)
    return salt + ":" + value.hex()


def verify_password(password, stored):
    return hmac.compare_digest(hash_password(password, stored.split(":")[0]), stored)


class Auth:
    session_seconds = 12 * 60 * 60

    def __init__(self, store):
        self.store = store
        self.attempts = defaultdict(deque)
        with store.lock, store.db:
            store.db.executescript("""
                CREATE TABLE IF NOT EXISTS admin_account (
                    id INTEGER PRIMARY KEY CHECK(id=1), password_hash TEXT NOT NULL,
                    must_change INTEGER NOT NULL, updated REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS admin_sessions (
                    token_hash TEXT PRIMARY KEY, expires REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS api_keys (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL,
                    prefix TEXT NOT NULL, created REAL NOT NULL, last_used REAL, revoked REAL
                );
            """)

    def initialize(self, initial_password=""):
        with self.store.lock, self.store.db:
            account = self.store.db.execute("SELECT must_change FROM admin_account WHERE id=1").fetchone()
            if account and not account["must_change"]:
                return
            password = initial_password or secrets.token_urlsafe(18)
            if not 12 <= len(password) <= 128:
                raise ValueError("The initial SIF administrator password must contain 12–128 characters.")
            self.store.db.execute("INSERT OR REPLACE INTO admin_account VALUES (1,?,?,?)",
                                  (hash_password(password), int(not bool(initial_password)), time.time()))
            self.store.db.execute("DELETE FROM admin_sessions")
        if not initial_password:
            # Until the user chooses a password, issue a fresh bootstrap password on restart.
            # This remains recoverable from the current container's logs after an image update.
            logging.getLogger("sif.bootstrap").warning("SIF initial administrator password: %s", password)

    def account(self):
        with self.store.lock:
            row = self.store.db.execute("SELECT must_change FROM admin_account WHERE id=1").fetchone()
        return {"role": "admin", "must_change_password": bool(row["must_change"])}

    def login(self, password, peer):
        now = time.time()
        attempts = self.attempts[peer]
        while attempts and attempts[0] < now - 300:
            attempts.popleft()
        if len(attempts) >= 5:
            raise ServiceError("login_rate_limited", "Too many login attempts. Try again in five minutes.", 429)
        with self.store.lock:
            row = self.store.db.execute("SELECT password_hash FROM admin_account WHERE id=1").fetchone()
        if not row or not verify_password(password, row["password_hash"]):
            attempts.append(now)
            raise ServiceError("invalid_password", "The administrator password is incorrect.", 401)
        self.attempts.pop(peer, None)
        token = secrets.token_urlsafe(32)
        with self.store.lock, self.store.db:
            self.store.db.execute("DELETE FROM admin_sessions WHERE expires<=?", (now,))
            self.store.db.execute("INSERT INTO admin_sessions VALUES (?,?)", (digest(token), now + self.session_seconds))
        return token

    def require_session(self, token):
        with self.store.lock:
            row = self.store.db.execute("SELECT expires FROM admin_sessions WHERE token_hash=?", (digest(token or ""),)).fetchone()
        if not row or row["expires"] <= time.time():
            raise ServiceError("login_required", "Sign in to the SIF console.", 401)
        return self.account()

    def logout(self, token):
        with self.store.lock, self.store.db:
            self.store.db.execute("DELETE FROM admin_sessions WHERE token_hash=?", (digest(token or ""),))

    def change_password(self, current, new):
        if not 12 <= len(new) <= 128:
            raise ServiceError("invalid_password", "Use a password of 12–128 characters.", 422)
        with self.store.lock, self.store.db:
            row = self.store.db.execute("SELECT password_hash FROM admin_account WHERE id=1").fetchone()
            if not verify_password(current, row["password_hash"]):
                raise ServiceError("invalid_password", "The current password is incorrect.", 401)
            self.store.db.execute("UPDATE admin_account SET password_hash=?,must_change=0,updated=? WHERE id=1",
                                  (hash_password(new), time.time()))
            self.store.db.execute("DELETE FROM admin_sessions")

    def create_key(self, name):
        token = "sk-" + secrets.token_urlsafe(32)
        key_id, now = str(uuid.uuid4()), time.time()
        with self.store.lock, self.store.db:
            self.store.db.execute("INSERT INTO api_keys(id,name,token_hash,prefix,created) VALUES (?,?,?,?,?)",
                                  (key_id, name, digest(token), token[:11] + "…" + token[-4:], now))
        return {"id": key_id, "name": name, "key": token, "created": now}

    def keys(self):
        with self.store.lock:
            return [dict(row) for row in self.store.db.execute(
                "SELECT id,name,prefix,created,last_used,revoked FROM api_keys ORDER BY created DESC")]

    def revoke_key(self, key_id):
        with self.store.lock, self.store.db:
            if not self.store.db.execute("UPDATE api_keys SET revoked=COALESCE(revoked,?) WHERE id=?", (time.time(), key_id)).rowcount:
                raise ServiceError("key_not_found", "API key does not exist.", 404)

    def require_key(self, token):
        if not token or not token.startswith("sk-"):
            raise ServiceError("unauthorized", "Provide a valid SIF API key using Authorization: Bearer sk-…", 401)
        with self.store.lock, self.store.db:
            row = self.store.db.execute("SELECT id,name FROM api_keys WHERE token_hash=? AND revoked IS NULL", (digest(token),)).fetchone()
            if not row:
                raise ServiceError("unauthorized", "The API key is invalid or revoked.", 401)
            self.store.db.execute("UPDATE api_keys SET last_used=? WHERE id=?", (time.time(), row["id"]))
        return dict(row)
