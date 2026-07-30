#!/usr/bin/env python3
"""
Relay server: bridges G2 glasses plugin -> dev machine -> phone via ADB.
Run on the dev machine at 0.0.0.0:5051.
Glasses fetch http://10.0.0.117:5051/media/status, relay proxies via adb.
"""
import json
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
}

def adb_curl_get(path: str) -> bytes:
    r = subprocess.run(
        ["adb", "shell", "curl", "-s", f"http://localhost:5051{path}"],
        capture_output=True, timeout=5
    )
    return r.stdout

def adb_curl_post(path: str, body: bytes) -> bytes:
    r = subprocess.run(
        ["adb", "shell", "curl", "-s", "-X", "POST",
         "-H", "Content-Type: application/json",
         "-d", body.decode(),
         f"http://localhost:5051{path}"],
        capture_output=True, timeout=5
    )
    return r.stdout


class RelayHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(fmt % args)

    def send_cors(self):
        for k, v in CORS.items():
            self.send_header(k, v)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors()
        self.end_headers()

    def do_GET(self):
        try:
            data = adb_curl_get(self.path)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors()
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            self.send_response(502)
            self.end_headers()
            self.wfile.write(str(e).encode())

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = adb_curl_post(self.path, body)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors()
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            self.send_response(502)
            self.end_headers()
            self.wfile.write(str(e).encode())


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 5051), RelayHandler)
    print("Relay listening on 0.0.0.0:5051 -> phone via ADB")
    server.serve_forever()
