#!/usr/bin/env python3
"""Server minimale per distribuire l'APK su una rete privata (APK locale).

Perche' non `python3 -m http.server`: serve un URL stabile (/app.apk) che punta
sempre alla build corrente, niente cache del browser e redirect dei vecchi nomi
(`app-0.2.0-m2.apk`, `opencode-m1.apk`, ...) verso l'ultima build.
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

DIST = os.path.dirname(os.path.abspath(__file__)) + "/dist"
POINTER = os.path.join(DIST, "current.txt")
BIND = (os.environ.get("OCM_BIND", "127.0.0.1"), int(os.environ.get("OCM_PORT", "8091")))


def current_apk() -> str | None:
    try:
        name = open(POINTER).read().strip()
    except OSError:
        return None
    path = os.path.join(DIST, name)
    return path if os.path.isfile(path) else None


class Handler(BaseHTTPRequestHandler):
    server_version = "opencode-mobile/1"
    protocol_version = "HTTP/1.1"

    def _headers(self, code: int, ctype: str, length: int, extra: dict | None = None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()

    def _file(self, path: str, ctype: str, download_name: str | None = None):
        data = open(path, "rb").read()
        extra = {"Content-Disposition": f'attachment; filename="{download_name}"'} if download_name else None
        self._headers(200, ctype, len(data), extra)
        self.wfile.write(data)

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head: bool = False):
        route = urlparse(self.path).path
        apk = current_apk()

        if route in ("/", "/index.html"):
            index = os.path.join(DIST, "index.html")
            if os.path.isfile(index):
                data = open(index, "rb").read()
                self._headers(200, "text/html; charset=utf-8", len(data))
                if not head:
                    self.wfile.write(data)
                return

        if route == "/app.apk":
            if apk:
                name = os.path.basename(apk)
                size = os.path.getsize(apk)
                self._headers(
                    200,
                    "application/vnd.android.package-archive",
                    size,
                    {"Content-Disposition": f'attachment; filename="{name}"'},
                )
                if not head:
                    with open(apk, "rb") as fh:
                        while chunk := fh.read(1 << 16):
                            self.wfile.write(chunk)
                return

        # Qualsiasi vecchio nome di APK: manda all'ultima build (i browser hanno in cache i link vecchi).
        if route.endswith(".apk"):
            self._headers(302, "text/plain; charset=utf-8", 0, {"Location": "/app.apk"})
            return

        candidate = os.path.normpath(os.path.join(DIST, route.lstrip("/")))
        if candidate.startswith(DIST) and os.path.isfile(candidate):
            self._file(candidate, "application/octet-stream")
            return

        body = b"not found\n"
        self._headers(404, "text/plain; charset=utf-8", len(body))
        if not head:
            self.wfile.write(body)

    def log_message(self, format, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), format % args))


if __name__ == "__main__":
    apk = current_apk()
    if not apk:
        sys.exit("current.txt mancante o APK inesistente in dist/")
    print(f"serving {os.path.basename(apk)} on http://{BIND[0]}:{BIND[1]}/app.apk", flush=True)
    ThreadingHTTPServer(BIND, Handler).serve_forever()
