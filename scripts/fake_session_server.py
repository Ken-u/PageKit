#!/usr/bin/env python3
"""Deterministic pages for PageKit multi-session/profile device tests."""

import html
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        name = query.get("name", ["READ"])[0][:80]
        marker = query.get("cookie", [""])[0][:80]
        safe_name = html.escape(name)
        filler = (f"This deterministic page belongs to browser session {safe_name}. " * 8)
        body = f"""<!doctype html>
<html><head><title>{safe_name}</title></head><body>
<main><article>
<h1>SESSION_{safe_name}</h1>
<p>{filler}</p>
<p id="cookie-value">PROFILE_COOKIE_PENDING</p>
<button>BUTTON_{safe_name}</button>
</article></main>
<script>
document.getElementById('cookie-value').textContent = 'PROFILE_COOKIE=' + (document.cookie || 'EMPTY');
</script>
</body></html>""".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        if marker:
            self.send_header("Set-Cookie", f"pagekit_profile={marker}; Path=/; SameSite=Lax")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18081
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
