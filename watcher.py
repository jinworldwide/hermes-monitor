#!/data/data/com.termux/files/usr/bin/python3
"""Watcher: polls input_queue.txt, sends to Telegram via @Etotal_bot"""
import os, time, requests

HOME = os.path.expanduser("~/.hermes-monitor")
QUEUE = os.path.join(HOME, "input_queue.txt")

BOT_TOKEN = "8620683361:AAEwr-pRcFr18rgv1-i6zBt8HZ7-BOwo6sk"
CHAT_ID = "7123398337"

print("👀 Watcher started")

while True:
    try:
        if os.path.exists(QUEUE):
            with open(QUEUE) as f:
                lines = [l.strip() for l in f if l.strip()]
            open(QUEUE, 'w').close()

            for line in lines:
                r = requests.post(
                    f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage",
                    json={"chat_id": CHAT_ID, "text": line},
                    timeout=10
                )
                print(f"  Sent: {r.status_code}")
    except Exception as e:
        print(f"  Error: {e}")
    time.sleep(2)
