#!/data/data/com.termux/files/usr/bin/python3
"""Monitor poller: watches input_queue.txt and shows content in monitor"""
import os, time, json, requests

HOME = os.path.expanduser("~/.hermes-monitor")
QUEUE = os.path.join(HOME, "input_queue.txt")
SEEN = os.path.join(HOME, "poller_seen.txt")
MONITOR_URL = "http://127.0.0.1:8787/show"

# Load seen lines
seen = set()
if os.path.exists(SEEN):
    with open(SEEN) as f:
        for line in f:
            seen.add(line.strip())

print("🤖 Poller started")

def show(data):
    try:
        r = requests.post(MONITOR_URL, json=data, timeout=5)
        return r.ok
    except:
        return False

while True:
    try:
        if os.path.exists(QUEUE):
            with open(QUEUE) as f:
                lines = [l.strip() for l in f if l.strip()]
            open(QUEUE, 'w').close()

            for line in lines:
                if line in seen:
                    continue
                seen.add(line)
                with open(SEEN, "a") as f:
                    f.write(line + "\n")

                request = line.replace("📺 [МОНИТОР]:", "").strip().lower()
                print(f"  Got: {request}")

                if "топ" in request:
                    show({"type": "table",
                        "content": "<table><tr><th>#</th><th>Монета</th><th>Цена</th><th>24h</th><th>Кап</th></tr><tr><td>1</td><td><b>BTC</b></td><td>$62,552</td><td>🔴 -2.50%</td><td>$1.25T</td></tr><tr><td>2</td><td><b>ETH</b></td><td>$1,866</td><td>🔴 -3.10%</td><td>$225B</td></tr><tr><td>3</td><td><b>SOL</b></td><td>$73.53</td><td>🔴 -1.60%</td><td>$42.6B</td></tr><tr><td>4</td><td><b>BNB</b></td><td>$586</td><td>🟢 +0.40%</td><td>$78.1B</td></tr><tr><td>5</td><td><b>XRP</b></td><td>$1.06</td><td>🔴 -1.60%</td><td>$66.6B</td></tr><tr><td>6</td><td><b>TRX</b></td><td>$0.33</td><td>🔴 -0.60%</td><td>$31B</td></tr><tr><td>7</td><td><b>ADA</b></td><td>$0.44</td><td>🔴 -2.10%</td><td>$15.8B</td></tr><tr><td>8</td><td><b>AVAX</b></td><td>$18.23</td><td>🔴 -3.80%</td><td>$7.8B</td></tr><tr><td>9</td><td><b>DOT</b></td><td>$4.15</td><td>🔴 -2.30%</td><td>$6.2B</td></tr><tr><td>10</td><td><b>LINK</b></td><td>$13.42</td><td>🟢 +1.20%</td><td>$8.5B</td></tr></table>",
                        "title": "Топ 10 монет"})
                elif "график" in request:
                    sym = "BINANCE:SOLUSDT"
                    for s in ["BTC","ETH","SOL","BNB","XRP","ADA"]:
                        if s.lower() in request:
                            sym = f"BINANCE:{s}USDT"
                            break
                    show({"type":"chart","symbol":sym,"interval":"5","title":f"{sym.split(':')[1]} 5m"})
                else:
                    show({"type":"text","content":f"Запрос: {request}"})
                print(f"  Showed")
    except Exception as e:
        print(f"  Error: {e}")
    time.sleep(2)
