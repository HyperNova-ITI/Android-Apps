#!/usr/bin/env python3
"""Smoke-test Android-facing HNVG through the Linux/QNX relay to a live TC397."""

from __future__ import annotations

import argparse
import socket
import struct
import time

MAGIC = b"HNVG"
VERSION = 1


def encode(message_type: int, correlation: int, payload: bytes = b"") -> bytes:
    return struct.pack(">4sBBHIHH", MAGIC, VERSION, message_type, 0, correlation, len(payload), 0) + payload


def receive_exact(peer: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = peer.recv(size - len(chunks))
        if not chunk:
            raise RuntimeError("gateway closed the connection")
        chunks.extend(chunk)
    return bytes(chunks)


def receive(peer: socket.socket) -> tuple[int, int, bytes]:
    header = receive_exact(peer, 16)
    magic, version, message_type, flags, correlation, length, reserved = struct.unpack(
        ">4sBBHIHH", header
    )
    if magic != MAGIC or version != VERSION or flags != 0 or reserved != 0 or length > 512:
        raise RuntimeError("invalid HNVG response header")
    return message_type, correlation, receive_exact(peer, length)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6100)
    parser.add_argument("--target", type=int, default=22)
    parser.add_argument("--fan", type=int, default=3)
    parser.add_argument("--zone", type=int, default=0)
    args = parser.parse_args()

    with socket.create_connection((args.host, args.port), timeout=2) as peer:
        peer.settimeout(6)
        peer.sendall(encode(0x01, 0, struct.pack(">HI", VERSION, 0x7)))
        seen_hello = False
        state = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and (not seen_hello or state is None):
            message_type, correlation, payload = receive(peer)
            if message_type == 0x81:
                if correlation != 0 or payload != b"\x00\x01\x00\x01":
                    raise RuntimeError("unexpected HELLO_ACK")
                seen_hello = True
            elif message_type == 0xA0:
                if len(payload) != 14:
                    raise RuntimeError("unexpected VEHICLE_STATE length")
                state = payload

        if not seen_hello or state is None:
            raise RuntimeError("handshake/state timeout")
        tc_connected = bool(state[8] & 0x01)
        telemetry_fresh = bool(state[8] & 0x02)
        print(
            f"PASS handshake; TC397 connected={tc_connected}, telemetry fresh={telemetry_fresh}, "
            f"temperature={struct.unpack('b', state[0:1])[0]}C, fuel={state[2]}%"
        )
        if not tc_connected:
            raise RuntimeError("relay is not connected to TC397")

        # Let the relay's reconnect quarantine expire before sending an actuation.
        time.sleep(0.8)
        correlation = 1
        peer.sendall(
            encode(0x10, correlation, bytes((args.target, args.fan, args.zone, 1)))
        )
        accepted = False
        confirmed = False
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline and not confirmed:
            message_type, received_correlation, payload = receive(peer)
            if message_type != 0x90 or received_correlation != correlation:
                continue
            if len(payload) != 12:
                raise RuntimeError("unexpected COMMAND_RESULT length")
            status, reason, tc_sequence = payload[1], payload[2], payload[8]
            if status == 1:
                accepted = True
                print(f"PASS command accepted; TC sequence={tc_sequence}")
            elif status == 2:
                confirmed = True
                print(f"PASS command confirmed by TC397; TC sequence={tc_sequence}")
            else:
                raise RuntimeError(f"command failed: status={status}, reason=0x{reason:02x}")

        if not accepted or not confirmed:
            raise RuntimeError("did not receive accepted then TC397-confirmed results")
        print("PASS full HNVG -> relay -> TC397 round trip")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
