import http.server, socketserver, os
DEST="/tmp/zelo_uploads"
os.makedirs(DEST, exist_ok=True)
class H(http.server.BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin","*")
        self.send_header("Access-Control-Allow-Methods","POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers","*")
    def do_OPTIONS(self):
        self.send_response(204); self._cors(); self.end_headers()
    def do_POST(self):
        n=int(self.headers.get("Content-Length",0))
        data=self.rfile.read(n)
        name=os.path.basename(self.path.strip("/")) or "out"
        with open(os.path.join(DEST,name),"wb") as f: f.write(data)
        self.send_response(200); self._cors(); self.end_headers()
        self.wfile.write(b"ok "+str(len(data)).encode())
    def log_message(self,*a): pass
with socketserver.TCPServer(("127.0.0.1",8099),H) as s:
    s.serve_forever()
