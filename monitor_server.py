#!/data/data/com.termux/files/usr/bin/python3
"""Монитор — локальный веб-сервер с SSE + Telegram keyboard input"""
import json, os, queue, threading, requests
from flask import Flask, Response, request, render_template_string, make_response, redirect
from urllib.parse import urlparse, urlunparse

HOME = os.path.expanduser("~/.hermes-monitor")
os.makedirs(HOME, exist_ok=True)

TELEGRAM_BOT_TOKEN = ""
TELEGRAM_CHAT_ID = "7123398337"

# Load token from main Hermes config
env_path = os.path.expanduser("~/.hermes/.env")
if os.path.exists(env_path):
    with open(env_path) as f:
        for line in f:
            if line.startswith("TELEGRAM_BOT_TOKEN="):
                TELEGRAM_BOT_TOKEN = line.strip().split("=", 1)[1]
                break

app = Flask(__name__)
sse_clients = set()
sse_lock = threading.Lock()

def sse_broadcast(data):
    with sse_lock:
        dead = set()
        for q in list(sse_clients):
            try:
                q.put_nowait(data)
            except:
                dead.add(q)
        sse_clients.difference_update(dead)

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
    queue_path = os.path.join(HOME, "input_queue.txt")
    try:
        with open(queue_path, "a") as f:
            f.write(msg + "\n")
        return {"ok": True, "sent": True, "note": "queued"}
    except Exception as e:
        return {"ok": False, "error": str(e)}

# === Monitor remote control ===
@app.route("/screenshot")
def screenshot():
    return {"html": current_state.get("content", ""), "type": current_state.get("type", "text"), "title": current_state.get("title", "")}

# Store pending commands for polling
_pending_commands = []
_pending_lock = threading.Lock()

@app.route("/click", methods=["POST"])
def click_element():
    data = request.get_json(force=True)
    selector = data.get("selector", "")
    if not selector:
        return {"ok": False, "error": "no selector"}
    with _pending_lock:
        _pending_commands.append({"action": "click", "selector": selector})
    return {"ok": True, "action": "click", "selector": selector}

@app.route("/execute", methods=["POST"])
def execute_js():
    data = request.get_json(force=True)
    js = data.get("js", "")
    if not js:
        return {"ok": False, "error": "no js"}
    with _pending_lock:
        _pending_commands.append({"action": "execute", "js": js})
    return {"ok": True, "action": "execute"}

@app.route("/commands")
def get_commands():
    """Polling endpoint — ScalpX checks this every 2 seconds"""
    with _pending_lock:
        cmds = list(_pending_commands)
        _pending_commands.clear()
    return {"commands": cmds}

@app.route("/eval", methods=["POST"])
def eval_js():
    """Executes JS and returns the result via a callback"""
    data = request.get_json(force=True)
    js = data.get("js", "")
    if not js:
        return {"ok": False, "error": "no js"}
    # JS that executes, then sends result back to server via fetch
    wrapped = f"""try{{ 
        var _r = eval({json.dumps(js)}); 
        var _s = typeof _r === 'object' ? JSON.stringify(_r) : String(_r);
        fetch('/eval_result', {{method:'POST', headers:{{'Content-Type':'application/json'}}, body:JSON.stringify({{result:_s}})}});
    }}catch(e){{ 
        fetch('/eval_result', {{method:'POST', headers:{{'Content-Type':'application/json'}}, body:JSON.stringify({{result:'ERROR: '+e.message}})}});
    }}"""
    sse_broadcast({"action": "execute", "js": wrapped})
    return {"ok": True, "action": "eval", "note": "result will be posted back"}

# Store for eval results
_eval_result = {"result": "no result yet"}

@app.route("/eval_result", methods=["POST"])
def eval_result():
    global _eval_result
    data = request.get_json(force=True)
    _eval_result = data
    return {"ok": True}

@app.route("/getresult")
def get_result():
    return _eval_result

@app.route("/vision", methods=["POST"])
def vision():
    """Returns structured view of what's on screen — parses HTML from proxy"""
    # Get the current ScalpX URL from state
    url = current_state.get("content", "")
    if not url or "scalpx" not in url:
        return {"ok": False, "error": "no scalpx page loaded"}
    
    # Fetch the page through proxy
    try:
        r = requests.get(url.replace("http://127.0.0.1:8787/proxy/scalpx/", "https://scalpx.ru/"),
                        cookies=SCALPX_COOKIES, headers=SCALPX_HEADERS, timeout=10)
        html = r.text
    except Exception as e:
        return {"ok": False, "error": str(e)}
    
    import re
    result = {"title": "", "buttons": [], "texts": [], "tables": [], "coins": []}
    
    # Title
    m = re.search(r'<title>(.*?)</title>', html, re.DOTALL)
    if m: result["title"] = m.group(1).strip()
    
    # Buttons
    for m in re.finditer(r'<button[^>]*>([\s\S]*?)</button>', html):
        txt = re.sub(r'<[^>]+>', '', m.group(1)).strip()
        if txt and len(txt) < 60:
            result["buttons"].append(txt)
    
    # Links with text
    for m in re.finditer(r'<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)</a>', html):
        txt = re.sub(r'<[^>]+>', '', m.group(2)).strip()
        href = m.group(1)
        if txt and len(txt) < 60:
            result["buttons"].append(f"{txt} -> {href[:80]}")
    
    # Coin symbols in sidebar
    for m in re.finditer(r'<div[^>]*class="[^"]*coin[^"]*"[^>]*>([\s\S]*?)</div>', html):
        txt = re.sub(r'<[^>]+>', '', m.group(1)).strip()
        if txt and len(txt) < 30:
            result["coins"].append(txt)
    
    # Tables
    for m in re.finditer(r'<table[^>]*>([\s\S]*?)</table>', html):
        rows = []
        for rm in re.finditer(r'<tr[^>]*>([\s\S]*?)</tr>', m.group(1)):
            cells = []
            for cm in re.finditer(r'<t[dh][^>]*>([\s\S]*?)</t[dh]>', rm.group(1)):
                cells.append(re.sub(r'<[^>]+>', '', cm.group(1)).strip())
            if cells:
                rows.append(" | ".join(cells))
        if rows:
            result["tables"].append("\n".join(rows[:10]))
    
    # Visible text blocks
    for m in re.finditer(r'<(h[1-4]|th|td|span|div|p|li)[^>]*>([\s\S]*?)</\1>', html):
        txt = re.sub(r'<[^>]+>', '', m.group(2)).strip()
        if txt and len(txt) > 3 and len(txt) < 100:
            result["texts"].append(txt)
    
    return {"ok": True, "vision": result}
@app.route("/navigate", methods=["POST"])
def navigate():
    """Navigate the monitor to a URL — uses JS to change location inside ScalpX"""
    data = request.get_json(force=True)
    url = data.get("url", "")
    if not url:
        return {"ok": False, "error": "no url"}
    global current_state
    current_state = {"type": "webpage", "content": url, "title": data.get("title", "ScalpX")}
    # Use JS to navigate inside ScalpX (which has SSE client injected)
    js = f"window.location.href = '{url}';"
    sse_broadcast({"action": "execute", "js": js})
    return {"ok": True, "type": "webpage", "url": url}

current_state = {"type": "text", "content": "Готов к работе", "title": "Монитор"}

# ScalpX proxy — forwards requests with auth cookies
SCALPX_COOKIES = {}
SCALPX_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Referer": "https://scalpx.ru/",
}

def load_scalpx_session():
    """Load saved cookies from file"""
    cookie_file = os.path.join(HOME, "scalpx_cookies.txt")
    if os.path.exists(cookie_file):
        with open(cookie_file) as f:
            for line in f:
                parts = line.strip().split("\t")
                if len(parts) >= 7:
                    SCALPX_COOKIES[parts[5]] = parts[6]

load_scalpx_session()

@app.route("/proxy/scalpx/")
@app.route("/proxy/scalpx/<path:subpath>")
def scalpx_proxy(subpath=""):
    target = f"https://scalpx.ru/{subpath}" if subpath else "https://scalpx.ru/"
    r = requests.get(target, cookies=SCALPX_COOKIES, headers=SCALPX_HEADERS, timeout=10)
    resp = make_response(r.content)
    resp.headers["Content-Type"] = r.headers.get("Content-Type", "text/html")
    # Remove X-Frame-Options to allow iframe embedding
    resp.headers.pop("X-Frame-Options", None)
    resp.headers.pop("x-frame-options", None)
    # Rewrite absolute URLs to go through proxy
    body = r.text
    body = body.replace('href="/', 'href="/proxy/scalpx/')
    body = body.replace('src="/', 'src="/proxy/scalpx/')
    body = body.replace("href='https://scalpx.ru", "href='/proxy/scalpx")
    body = body.replace("src='https://scalpx.ru", "src='/proxy/scalpx")
    # Inject CSS zoom + polling control into ScalpX pages
    inject = '''
<style>body{zoom:0.35;-moz-transform:scale(0.35);-moz-transform-origin:0 0;}.coins-sidebar{display:none!important}.coins-main{margin-left:0!important;width:100%!important}.main-content-with-sidebar{padding-left:0!important}</style>
<script>
(function(){
  function poll(){
    try {
      var raw = MonitorBridge.getCommands();
      var data = JSON.parse(raw);
      var cmds = data.commands || [];
      for(var i=0;i<cmds.length;i++){
        var d = cmds[i];
        if(d.action === 'click'){
          var el = document.querySelector(d.selector);
          if(el) el.click();
        }
        if(d.action === 'execute'){
          try{ eval(d.js); }catch(err){ console.error(err); }
        }
      }
    } catch(e){}
  }
  setInterval(poll, 2000);
  poll();
})();
</script>'''
    body = body.replace("</head>", inject + "</head>")
    resp.set_data(body)
    return resp

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
  if (data.action === 'click') {
    const iframe = document.querySelector('iframe');
    if (iframe) {
      try {
        const doc = iframe.contentDocument || iframe.contentWindow.document;
        const el = doc.querySelector(data.selector);
        if (el) { el.click(); }
      } catch(err) { console.error(err); }
    } else {
      const el = document.querySelector(data.selector);
      if (el) { el.click(); }
    }
    return;
  }
  if (data.action === 'execute') {
    const iframe = document.querySelector('iframe');
    if (iframe) {
      try {
        iframe.contentWindow.eval(data.js);
      } catch(err) { console.error(err); }
    } else {
      try { eval(data.js); } catch(err) { console.error(err); }
    }
    return;
  }
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
    // Load in iframe (same-origin via proxy, so we can control it)
    content.innerHTML = '<iframe id="scalpxFrame" src="' + data.content + '" style="width:100%;height:100%;border:none;"></iframe>';
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
    app.run(host="0.0.0.0", port=8787, debug=False, threaded=True)
