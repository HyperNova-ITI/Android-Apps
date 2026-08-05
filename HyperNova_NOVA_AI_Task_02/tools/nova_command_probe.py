#!/usr/bin/env python3
"""Minimal laptop-side Pi substitute for the Android command bridge.

Run NOVA with -PnovaHost=10.0.2.2, then launch this probe before starting the app. It verifies the
real TCP -> NOVA broker -> AIDL -> provider callback path on the Android emulator.
"""

import argparse
import json
import socket
import struct
import threading
import time
import uuid


HOST = "0.0.0.0"
CONTROL_PORT = 8765
AUDIO_PORT = 8766
HEADER = struct.Struct("!4sBBHII")
FINAL = {"confirmed", "rejected", "unavailable", "timeout", "cancelled"}


def listener(port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, port))
    server.listen(1)
    server.settimeout(45)
    return server


def receive_exact(connection, size):
    data = b""
    while len(data) < size:
        part = connection.recv(size - len(data))
        if not part:
            raise EOFError("connection closed")
        data += part
    return data


def audio_server(ready):
    with listener(AUDIO_PORT) as server:
        ready.set()
        connection, address = server.accept()
        with connection:
            header = receive_exact(connection, HEADER.size)
            magic, version, frame_type, _flags, size, stream_id = HEADER.unpack(header)
            payload = receive_exact(connection, size) if size else b""
            if magic != b"NVA1" or version != 1 or frame_type != 1:
                raise RuntimeError("Android sent an invalid audio HELLO")
            print(f"audio connected: {address[0]} {payload.decode()}", flush=True)
            ack = json.dumps({"server": "nova-command-probe"}).encode()
            connection.sendall(HEADER.pack(b"NVA1", 1, 2, 0, len(ack), stream_id) + ack)
            while connection.recv(1024):
                pass


def send_json(writer, message):
    writer.write((json.dumps(message, separators=(",", ":")) + "\n").encode())
    writer.flush()


def command(reader, writer, domain, operation, args):
    request_id = str(uuid.uuid4())
    request = {
        "type": "command_request",
        "v": 1,
        "turn_id": "emulator-probe",
        "request_id": request_id,
        "domain": domain,
        "operation": operation,
        "args": args,
    }
    send_json(writer, request)
    seen = []
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        raw = reader.readline()
        if not raw:
            raise EOFError("NOVA closed the control connection")
        message = json.loads(raw)
        if message.get("type") != "command_result" or message.get("request_id") != request_id:
            continue
        status = message.get("status")
        seen.append(status)
        print(f"{domain}.{operation}: {status} — {message.get('message')}", flush=True)
        if status in FINAL:
            if status != "confirmed":
                raise RuntimeError(f"{domain}.{operation} ended as {status}: {message}")
            return message, seen
    raise TimeoutError(f"No final result for {domain}.{operation}")


def main(hold_connection=False):
    audio_ready = threading.Event()
    threading.Thread(target=audio_server, args=(audio_ready,), daemon=True).start()
    audio_ready.wait()

    with listener(CONTROL_PORT) as server:
        print("probe ready on ports 8765 and 8766", flush=True)
        connection, address = server.accept()
        connection.settimeout(30)
        with connection, connection.makefile("rb") as reader, connection.makefile("wb") as writer:
            hello = json.loads(reader.readline())
            if hello.get("type") != "hello":
                raise RuntimeError(f"Expected HELLO, received {hello}")
            print(f"control connected: {address[0]} {hello}", flush=True)
            send_json(writer, {
                "type": "hello_ack",
                "v": 1,
                "server": "nova-command-probe",
            })

            climate, climate_states = command(
                reader,
                writer,
                "climate",
                "set_temperature",
                {"zone": "all", "temperature_c": 22.0},
            )
            if climate_states != ["accepted", "confirmed"]:
                raise RuntimeError(f"Climate callback sequence was {climate_states}")
            confirmed_state = climate.get("data", {}).get("confirmed_state", {})
            if confirmed_state.get("driver_temperature_c") != 22.0:
                raise RuntimeError(f"Climate did not confirm 22°C: {confirmed_state}")

            saved, _ = command(reader, writer, "navigation", "get_saved_destinations", {})
            destinations = saved.get("data", {}).get("destinations", [])
            home = next(
                (item for item in destinations if item.get("source") == "saved_home"),
                None,
            )
            if not home:
                raise RuntimeError(f"Navigation did not return a saved-home entry: {destinations}")

            route, route_states = command(
                reader,
                writer,
                "navigation",
                "set_destination",
                {"destination_id": home["id"]},
            )
            if route_states != ["accepted", "confirmed"]:
                raise RuntimeError(f"Route callback sequence was {route_states}")
            if route.get("data", {}).get("navigation_state") != "ACTIVE":
                raise RuntimeError(f"Navigation did not become active: {route}")

            print("PASS: TCP -> NOVA -> AIDL -> Climate/Navigation -> final callback", flush=True)
            if hold_connection:
                print(
                    "Holding both NOVA connections open for UI inspection; press Ctrl+C to stop.",
                    flush=True,
                )
                while True:
                    time.sleep(60)
            time.sleep(2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--hold",
        action="store_true",
        help="keep NOVA connected after the checks so Launcher remains in its ready state",
    )
    main(hold_connection=parser.parse_args().hold)
