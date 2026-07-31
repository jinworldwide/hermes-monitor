#!/data/data/com.termux/files/usr/bin/python3
"""Монитор — локальный веб-сервер с SSE + Telegram keyboard input"""
import json, os, queue, threading, requests
from flask import Flask, Response, request, render_template_string

HOME = os.path.expanduser("~/.hermes-monitor")
os.makedirs(HOME, exist_ok=True)

TELEGRAM_BOT_TOKEN = "8558804095:AAHj5tQ5t5t5t5t5t5t5t5t5t5t5t5t5t5t5t5t5"
TELEGRAM_CHAT_ID = "7123398337"

env_path = os.path.expanduser("~/.hermes/profiles/trader/.env")
if os.path.exists(env_path):
    with open(env_path) as f:
        for line in f:
            if line.startswith("TELEGRAM_BOT_TOKEN="):
                TELEGRAM_BOT_TOKEN = line.strip().split("=", 1)[1]

app = Flask(__name__)
sse_clients = set()
sse_lock = threading.Lock()

def sse_broadcast(data):
    with sse_lock:
        dead = set()
        for q in sse_clients:
            try:
                q.put_nowait(data)
            except:
                dead.add(q)
        sse_clients -= dead

@app.route("/")
def index():
    return render_template_string(HTML)

@app.route("/events")
def sse():
    def gen():
        q = queue.Queue()
        with sse_lock:
            sse_clients.add(q)
        try:
            yield f"data: {json.dumps(current_state)}\n\n"
            while True:
                data = q.get()
                yield f"data: {json.dumps(data)}\n\n"
        except GeneratorExit:
            pass
        finally:
            with sse_lock:
                sse_clients.discard(q)
    return Response(gen(), mimetype="text/event-stream")

@app.route("/show", methods=["POST"])
def show():
    global current_state
    data = request.get_json(force=True)
    payload = {
        "type": data.get("type", "text"),
        "content": data.get("content", ""),
        "title": data.get("title", ""),
        "symbol": data.get("symbol", ""),
        "interval": data.get("interval", "5")
    }
    current_state.update(payload)
    sse_broadcast(payload)
    return {"ok": True, "type": payload["type"]}

@app.route("/keyboard_input", methods=["POST"])
def keyboard_input():
    data = request.get_json(force=True)
    text = data.get("text", "")
    if not text.strip():
        return {"ok": False, "error": "empty text"}
    msg = f"📺 [МОНИТОР]: {text}"
    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage"
    try:
        r = requests.post(url, json={"chat_id": TELEGRAM_CHAT_ID, "text": msg}, timeout=10)
        return {"ok": True, "sent": r.ok}
    except Exception as e:
        return {"ok": False, "error": str(e)}

current_state = {"type": "text", "content": "Готов к работе", "title": "Монитор"}

HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Монитор</title>
<script src="https://s3.tradingview.com/tv.js"></script>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #0d1117;
  color: #c9d1d9;
  overflow: hidden;
  height: 100vh;
  width: 100vw;
  -webkit-user-select: none;
  user-select: none;
}
#content {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  pointer-events: none;
}
#content iframe { width: 100%; height: 100%; border: none; }
#content img { max-width: 100%; max-height: 100%; object-fit: contain; }
#content pre {
  width: 100%; height: 100%; overflow: auto;
  background: #0d1117; color: #c9d1d9; padding: 12px;
  font-size: 12px; font-family: monospace; white-space: pre-wrap; word-break: break-all;
}
#content table { width: 100%; border-collapse: collapse; font-size: 12px; }
#content th, #content td { border: 1px solid #30363d; padding: 6px 10px; text-align: left; }
#content th { background: #161b22; color: #8b949e; }
#content .placeholder { color: #484f58; font-size: 18px; text-align: center; }
</style>
</head>
<body>
<div id="content">
  <div class="placeholder" id="placeholder">Готов к работе</div>
</div>
<script>
const content = document.getElementById('content');
const evtSource = new EventSource('/events');
evtSource.onmessage = function(e) {
  const data = JSON.parse(e.data);
  render(data);
};
function render(data) {
  if (data.type === 'chart') {
    content.innerHTML = '<div id="tvChart" style="width:100%;height:100%"></div>';
    const symbol = data.symbol || data.content || 'BINANCE:SOLUSDT';
    const interval = data.interval || '5';
    new TradingView.widget({
      container_id: "tvChart", symbol: symbol, interval: interval,
      theme: "dark", style: "1", locale: "ru",
      width: "100%", height: "100%",
      hide_top_toolbar: true, hide_legend: false,
      allow_symbol_change: false, save_image: false,
      studies: [], autosize: true
    });
  } else if (data.type === 'video') {
    var v = data.content;
    if (v.indexOf('youtube.com/embed/') !== -1) {
      v += (v.indexOf('?') === -1 ? '?autoplay=1&mute=1' : '&autoplay=1&mute=1');
    }
    content.innerHTML = '<iframe src="' + v + '" allow="autoplay" allowfullscreen style="width:100%;height:100%;border:none;"></iframe>';
  } else if (data.type === 'webpage') {
    content.innerHTML = '<iframe src="' + data.content + '" allowfullscreen style="width:100%;height:100%;border:none;"></iframe>';
  } else if (data.type === 'image') {
    content.innerHTML = '<img src="' + data.content + '" alt="" style="max-width:100%;max-height:100%;object-fit:contain;">';
  } else if (data.type === 'code') {
    content.innerHTML = '<pre>' + esc(data.content) + '</pre>';
  } else if (data.type === 'table') {
    content.innerHTML = data.content;
  } else {
    content.innerHTML = '<pre>' + esc(data.content) + '</pre>';
  }
}
function esc(t) { const d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
</script>
</body>
</html>"""

if __name__ == "__main__":
    print(f"🚀 Монитор: http://127.0.0.1:8787")
    app.run(host="127.0.0.1", port=8787, debug=False, threaded=True)
