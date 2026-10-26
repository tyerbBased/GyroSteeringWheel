#!/usr/bin/env python3

import socket
import sys
import threading
import time

import uinput

UDP_PORT = 47777
PHONE_TIMEOUT = 1.0
RETURN_TIME = 0.4
PC_SMOOTHING = 0.0

AXIS_MAX = 32767
AXIS_CENTER = AXIS_MAX // 2
STEER_SCALE = 16.384

steer_spec = (0x03, 0x00, 0, AXIS_MAX, 0, 0)
ABS_X_EVENT = (0x03, 0x00)
device_events = [steer_spec]

emit_lock = threading.Lock()


def emit(device, event, value):
    with emit_lock:
        device.emit(event, value)


def steering_from_packet(value):
    value = max(-1000, min(1000, int(value)))
    return AXIS_CENTER + int(value * STEER_SCALE)


def main():
    device = uinput.Device(device_events, "Gyro Steering Wheel")
    print("Virtual joystick created (uinput): steering on ABS_X.", flush=True)
    emit(device, ABS_X_EVENT, AXIS_CENTER)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", UDP_PORT))
    sock.settimeout(0.05)
    print(f"UDP receiver listening on port {UDP_PORT} (100 Hz).", flush=True)
    print("Waiting for the phone... (open the app and press START)", flush=True)

    current_axis = AXIS_CENTER
    phone_connected = False
    last_packet_time = 0.0
    last_addr = ("?", 0)
    return_from = AXIS_CENTER
    return_started = 0.0
    smooth_value = float(AXIS_CENTER)
    last_ack_time = 0.0
    pkt_count = 0
    rate_t0 = time.time()

    try:
        while True:
            try:
                data, addr = sock.recvfrom(256)
            except socket.timeout:
                data = None

            now = time.time()

            if data is not None:
                last_packet_time = now
                if not phone_connected:
                    phone_connected = True
                    pkt_count = 0
                    rate_t0 = now
                    print(f"Phone connected: {addr[0]}:{addr[1]}", flush=True)
                last_addr = addr

                line = data.decode('utf-8', errors='ignore').strip()
                if line.startswith('S,'):
                    parts = line.split(',')
                    if len(parts) >= 2 and parts[1].lstrip('-').isdigit():
                        steer = int(parts[1])
                        target = steering_from_packet(steer)

                        alpha = 1.0 - PC_SMOOTHING
                        smooth_value = smooth_value * PC_SMOOTHING + target * alpha
                        new_axis = int(round(smooth_value))
                        if new_axis != current_axis:
                            current_axis = new_axis
                            emit(device, ABS_X_EVENT, current_axis)

                        pkt_count += 1

                if now - last_ack_time >= 1.0:
                    try:
                        sock.sendto(b'A', (addr[0], addr[1]))
                    except OSError:
                        pass
                    last_ack_time = now

                if now - rate_t0 >= 5.0:
                    r = pkt_count / (now - rate_t0)
                    print(f"\rSteering: {r:.0f} pkt/s        ", end='', flush=True)
                    pkt_count = 0
                    rate_t0 = now

            else:
                if phone_connected and (now - last_packet_time > PHONE_TIMEOUT):
                    phone_connected = False
                    return_from = current_axis
                    return_started = now
                    pkt_count = 0
                    rate_t0 = now
                    print("\nPhone disconnected — steering smoothly centered.", flush=True)

                if not phone_connected and current_axis != AXIS_CENTER:
                    k = min(1.0, (now - return_started) / RETURN_TIME) if RETURN_TIME > 0 else 1.0
                    new_axis = int(return_from + (AXIS_CENTER - return_from) * k)
                    if new_axis != current_axis:
                        current_axis = new_axis
                        emit(device, ABS_X_EVENT, current_axis)

    except KeyboardInterrupt:
        print("\nStopped by user.", flush=True)
    finally:
        try:
            emit(device, ABS_X_EVENT, AXIS_CENTER)
        except Exception:
            pass
        sock.close()
        device.destroy()
        print("Virtual joystick destroyed. Done.", flush=True)


if __name__ == "__main__":
    main()
