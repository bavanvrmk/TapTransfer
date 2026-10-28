"""Run behind an HTTPS reverse proxy. SQLite stores the group snapshot and hashed invite tokens."""
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import uuid
from ledger import validate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LIMIT = 24 * 1024 * 1024
DB = os.environ.get("TRIP_DB", "trips.sqlite3")

def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()

def connect():
    conn = sqlite3.connect(DB, timeout=10)
    conn.execute("CREATE TABLE IF NOT EXISTS trips(id TEXT PRIMARY KEY, token TEXT NOT NULL, rev INTEGER NOT NULL, body BLOB NOT NULL)")
    return conn

class Handler(BaseHTTPRequestHandler):
    def reply(self, code, body=b"", rev=None):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        if rev is not None:
            self.send_header("ETag", '"%d"' % rev)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self): self.handle_api("GET")
    def do_POST(self): self.handle_api("POST")
    def do_PUT(self): self.handle_api("PUT")

    def handle_api(self, method):
        token = self.headers.get("Authorization", "").removeprefix("Bearer ")
        parts = self.path.strip("/").split("/")
        conn = None
        try:
            if not token or parts[0] != "trips":
                return self.reply(403)
            creating = method == "POST" and parts == ["trips"]
            if creating:
                if not hmac.compare_digest(token, os.environ["TRIP_CREATE_KEY"]):
                    return self.reply(403)
            elif len(parts) != 2 or method not in ("GET", "PUT"):
                return self.reply(404)
            conn = connect()
            if not creating:
                row = conn.execute("SELECT token, rev, body FROM trips WHERE id=?", (parts[1],)).fetchone()
                if not row or not hmac.compare_digest(row[0], digest(token)):
                    return self.reply(403)
                if method == "GET":
                    return self.reply(200, row[2], row[1])
                if self.headers.get("If-Match") != '"%d"' % row[1]:
                    return self.reply(409)
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= LIMIT:
                return self.reply(413)
            body = self.rfile.read(length)
            data = json.loads(body)
            trip_id = str(uuid.UUID(data["id"]))
            validate(data)
            entries = data["entries"]
            if data["version"] != 1 or len(entries) > 2000 or not isinstance(entries, list):
                return self.reply(400)
            by_id = {str(uuid.UUID(e["id"])): e for e in entries}
            if len(by_id) != len(entries):
                return self.reply(400)
            if creating:
                secret = secrets.token_urlsafe(32)
                conn.execute("INSERT INTO trips VALUES(?,?,1,?)", (trip_id, digest(secret), body))
                conn.commit()
                return self.reply(201, json.dumps({"id":trip_id, "token":secret}).encode(), 1)
            old = json.loads(row[2])
            if trip_id != parts[1] or any(data[k] != old[k] for k in ("id","name","members","keeper")):
                return self.reply(409)
            if any(by_id.get(e["id"]) != e for e in old["entries"]):
                return self.reply(409)
            changed = conn.execute("UPDATE trips SET rev=rev+1,body=? WHERE id=? AND rev=?", (body, trip_id, row[1])).rowcount
            conn.commit()
            return self.reply(200 if changed else 409,rev=row[1]+1 if changed else None)
        except sqlite3.IntegrityError:
            self.reply(409)
        except (KeyError, ValueError, TypeError, AttributeError, RecursionError):
            self.reply(400)
        finally:
            if conn is not None:
                conn.close()

if __name__ == "__main__":
    if len(os.environ.get("TRIP_CREATE_KEY","")) < 24:
        raise SystemExit("Set TRIP_CREATE_KEY to a strong random secret of at least 24 characters.")
    ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("PORT","8787"))), Handler).serve_forever()

