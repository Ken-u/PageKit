#!/usr/bin/env python3
"""Deterministic localhost fixture used by ./build.sh llmtest."""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PAGE = b"""<!doctype html><html><head><title>PageKit LLM Smoke</title></head><body>
<article><h1>Install</h1><p>Keep this original installation detail.</p>
<pre><code class="language-sh">echo ORIGINAL_CODE</code></pre>
<h2>Verify</h2><p>Keep this original verification detail.</p></article>
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/page":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer smoke-secret":
            self.send_error(401)
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length))
        prompt = "\n".join(message.get("content", "") for message in request.get("messages", []))
        if "ORIGINAL_CODE" not in prompt:
            self.send_error(422, "missing page content")
            return
        summary = "focus-ok" if "运行模式 Focus" in prompt else "compact-ok"
        compressed = {
            "title": "must-not-win",
            "url": "https://must-not-win.example",
            "summary": summary,
            "key_points": ["semantic point"],
            "sections": [{"heading": "Install", "summary": "semantic section"}],
            "code_blocks": [{"language": "sh", "content": "MODEL_CHANGED_CODE"}],
            "commands": ["MODEL_CHANGED_COMMAND"],
            "warnings": [],
            "limitations": [],
        }
        response = json.dumps(
            {"choices": [{"message": {"role": "assistant", "content": json.dumps(compressed)}}]},
            ensure_ascii=False,
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, _format, *_args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
