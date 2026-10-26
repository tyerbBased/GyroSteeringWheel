#!/usr/bin/env python3

import math
import socket
import sys
import time

host = sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1'
port = int(sys.argv[2]) if len(sys.argv) > 2 else 47777

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
t0 = time.time()
print(f"Sending steering sine wave to {host}:{port} (Ctrl+C to stop)")
try:
    while True:
        t = time.time() - t0
        steer = int(1000 * math.sin(2 * math.pi * t / 4.0))
        sock.sendto(f"S,{steer}".encode(), (host, port))
        print(f"\rS,{steer:5d}   ", end='', flush=True)
        time.sleep(0.02)
except KeyboardInterrupt:
    print("\nStopped")
