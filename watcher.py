#!/data/data/com.termux/files/usr/bin/python3
"""Watcher: polls input_queue.txt, saves to last_input.txt for Hermes to read"""
import os, time

HOME = os.path.expanduser("~/.hermes-monitor")
QUEUE = os.path.join(HOME, "input_queue.txt")
LAST = os.path.join(HOME, "last_input.txt")

print("👀 Watcher started. Writing to last_input.txt")

while True:
    try:
        if os.path.exists(QUEUE):
            with open(QUEUE) as f:
                lines = [l.strip() for l in f if l.strip()]
            open(QUEUE, 'w').close()

            for line in lines:
                with open(LAST, "w") as f:
                    f.write(line + "\n")
                print(f"  Saved: {line[:50]}")
    except Exception as e:
        print(f"  Error: {e}")
    time.sleep(2)
