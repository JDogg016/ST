#!/usr/bin/env python3
"""Create the Guest Wi-Fi device tracker helper and the Guest person.

Run this on a machine that can reach your Home Assistant instance:

    export HA_URL=http://homeassistant.local:8123
    export HA_TOKEN=<long-lived access token>
    python3 create_guest_presence.py

Profile -> Security -> Long-lived access tokens creates the token. It must
belong to an admin user, because creating a helper goes through the config
flow API.

The script is idempotent: run it twice and the second run reports that both
objects already exist and changes nothing. Nothing is deleted, ever.

Standard library only - no pip install needed.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import socket
import ssl
import struct
import sys
import urllib.error
import urllib.request
from typing import Any

TRACKER_NAME = "Guest WiFi"
TRACKER_ENTITY = "device_tracker.guest_wifi"
PERSON_NAME = "Guest"
PERSON_ENTITY = "person.guest"


# ---------------------------------------------------------------------------
# REST
# ---------------------------------------------------------------------------


class Ha:
    def __init__(self, url: str, token: str, insecure: bool = False) -> None:
        self.url = url.rstrip("/")
        self.token = token
        self.ctx: ssl.SSLContext | None = None
        if insecure:
            self.ctx = ssl.create_default_context()
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE

    def request(self, path: str, payload: Any = None, method: str | None = None) -> Any:
        data = None
        headers = {"Authorization": f"Bearer {self.token}"}
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(
            f"{self.url}{path}", data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(req, timeout=30, context=self.ctx) as resp:
                body = resp.read().decode()
        except urllib.error.HTTPError as err:
            detail = err.read().decode(errors="replace")[:400]
            raise SystemExit(
                f"HTTP {err.code} from {path}\n  {detail}\n"
                + (
                    "  A 401 means the token is wrong or expired.\n"
                    if err.code == 401
                    else ""
                )
                + (
                    "  A 403 usually means the token's user is not an admin.\n"
                    if err.code == 403
                    else ""
                )
            ) from err
        except urllib.error.URLError as err:
            raise SystemExit(
                f"Could not reach {self.url}: {err.reason}\n"
                "  Check HA_URL, and that this machine is on the same network."
            ) from err
        return json.loads(body) if body.strip() else None


# ---------------------------------------------------------------------------
# Minimal WebSocket client (person/* is only exposed over the websocket API)
# ---------------------------------------------------------------------------


class Ws:
    def __init__(self, url: str, token: str, insecure: bool = False) -> None:
        m = re.match(r"^(https?)://([^/:]+)(?::(\d+))?", url)
        if not m:
            raise SystemExit(f"Could not parse HA_URL: {url!r}")
        scheme, host, port_s = m.group(1), m.group(2), m.group(3)
        tls = scheme == "https"
        port = int(port_s) if port_s else (443 if tls else 80)

        self.sock: Any = socket.create_connection((host, port), timeout=30)
        if tls:
            ctx = ssl.create_default_context()
            if insecure:
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
            self.sock = ctx.wrap_socket(self.sock, server_hostname=host)

        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(
            (
                "GET /api/websocket HTTP/1.1\r\n"
                f"Host: {host}:{port}\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            ).encode()
        )
        self.buf = b""
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise SystemExit("Server closed the connection during the handshake")
            self.buf += chunk
        head, _, rest = self.buf.partition(b"\r\n\r\n")
        if b"101" not in head.split(b"\r\n")[0]:
            raise SystemExit(f"WebSocket upgrade refused: {head.decode(errors='replace')[:200]}")
        self.buf = rest

        self._id = 0
        if self.recv().get("type") != "auth_required":
            raise SystemExit("Unexpected greeting from the websocket API")
        self.send({"type": "auth", "access_token": token})
        auth = self.recv()
        if auth.get("type") != "auth_ok":
            raise SystemExit(
                f"Websocket auth failed: {auth.get('message', auth)}\n"
                "  The long-lived access token is wrong or expired."
            )

    # -- framing ------------------------------------------------------------

    def _read(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise SystemExit("Websocket closed unexpectedly")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _frame(self, payload: bytes, opcode: int = 0x1) -> bytes:
        n = len(payload)
        if n < 126:
            header = struct.pack("!BB", 0x80 | opcode, 0x80 | n)
        elif n < 65536:
            header = struct.pack("!BBH", 0x80 | opcode, 0x80 | 126, n)
        else:
            header = struct.pack("!BBQ", 0x80 | opcode, 0x80 | 127, n)
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return header + mask + masked

    def send(self, msg: dict) -> None:
        self.sock.sendall(self._frame(json.dumps(msg).encode()))

    def recv(self) -> dict:
        while True:
            b0, b1 = struct.unpack("!BB", self._read(2))
            opcode = b0 & 0x0F
            length = b1 & 0x7F
            if length == 126:
                (length,) = struct.unpack("!H", self._read(2))
            elif length == 127:
                (length,) = struct.unpack("!Q", self._read(8))
            payload = self._read(length) if length else b""
            if b1 & 0x80:  # server frames should not be masked, but be safe
                payload = bytes(b ^ payload[i % 4] for i, b in enumerate(payload[4:]))

            if opcode == 0x9:  # ping -> pong
                self.sock.sendall(self._frame(payload, opcode=0xA))
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode == 0x8:  # close
                raise SystemExit("Websocket closed by Home Assistant")
            if opcode in (0x1, 0x2):
                return json.loads(payload.decode())

    def command(self, msg: dict) -> Any:
        self._id += 1
        msg = dict(msg, id=self._id)
        self.send(msg)
        while True:
            reply = self.recv()
            if reply.get("id") != self._id:
                continue
            if not reply.get("success", False):
                err = reply.get("error", {})
                raise SystemExit(
                    f"{msg['type']} failed: {err.get('code')} {err.get('message')}"
                )
            return reply.get("result")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------------------


def find_source_sensor(states: list[dict], explicit: str | None) -> str:
    if explicit:
        if not any(s["entity_id"] == explicit for s in states):
            raise SystemExit(f"{explicit} does not exist on this instance")
        return explicit

    candidates = [
        s["entity_id"]
        for s in states
        if s["entity_id"].startswith("sensor.")
        and "gorcho" in s["entity_id"].lower()
        and "client" in s["entity_id"].lower()
    ]
    if not candidates:
        raise SystemExit(
            "Could not find a Gorcho client-count sensor.\n"
            "  Check Developer tools -> States for the UniFi WLAN 'Clients' sensor\n"
            "  and pass it explicitly with --source sensor.<whatever_it_is>"
        )
    if len(candidates) > 1:
        raise SystemExit(
            "Found more than one candidate, pass one with --source:\n  "
            + "\n  ".join(sorted(candidates))
        )
    return candidates[0]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", default=os.environ.get("HA_URL"))
    ap.add_argument("--token", default=os.environ.get("HA_TOKEN"))
    ap.add_argument("--source", help="UniFi Gorcho client-count sensor entity_id")
    ap.add_argument("--insecure", action="store_true", help="skip TLS verification")
    ap.add_argument("--dry-run", action="store_true", help="show what would happen")
    args = ap.parse_args()

    if not args.url or not args.token:
        ap.error("set HA_URL and HA_TOKEN (or pass --url/--token)")

    ha = Ha(args.url, args.token, args.insecure)

    conf = ha.request("/api/config")
    print(f"Connected to {conf.get('location_name')} (HA {conf.get('version')})")

    version = conf.get("version", "")
    m = re.match(r"^(\d{4})\.(\d+)", version)
    if m and (int(m.group(1)), int(m.group(2))) < (2026, 6):
        raise SystemExit(
            f"HA {version} is too old: the template device_tracker platform\n"
            "  arrived in 2026.6. See the README for the MQTT fallback."
        )

    states = ha.request("/api/states")
    source = find_source_sensor(states, args.source)
    current = next(s for s in states if s["entity_id"] == source)
    print(f"Source sensor: {source} = {current['state']}")

    in_zones = "{{ ['zone.home'] if states('%s') | int(0) > 0 else [] }}" % source
    existing = {s["entity_id"] for s in states}

    if args.dry_run:
        print("\n-- dry run, nothing will be created --")
        print(f"tracker {TRACKER_ENTITY}: "
              f"{'exists' if TRACKER_ENTITY in existing else 'would create'}")
        print(f"  in_zones: {in_zones}")
        print(f"person  {PERSON_ENTITY}: "
              f"{'exists' if PERSON_ENTITY in existing else 'would create'}")
        return 0

    # 1. the template device_tracker helper -------------------------------
    if TRACKER_ENTITY in existing:
        print(f"{TRACKER_ENTITY} already exists, leaving it alone")
    else:
        flow = ha.request(
            "/api/config/config_entries/flow",
            {"handler": "template", "show_advanced_options": False},
        )
        flow_id = flow["flow_id"]
        step = ha.request(
            f"/api/config/config_entries/flow/{flow_id}",
            {"next_step_id": "device_tracker"},
        )
        if step.get("type") != "form":
            raise SystemExit(f"Unexpected config flow step: {step}")
        result = ha.request(
            f"/api/config/config_entries/flow/{flow_id}",
            {"name": TRACKER_NAME, "in_zones": in_zones},
        )
        if result.get("type") != "create_entry":
            raise SystemExit(
                f"Helper was not created: {result.get('errors') or result}"
            )
        print(f"Created template device tracker {TRACKER_NAME!r}")

    # 2. the person --------------------------------------------------------
    ws = Ws(args.url, args.token, args.insecure)
    try:
        listing = ws.command({"type": "person/list"})
        people = list(listing.get("storage", [])) + list(listing.get("config", []))
        match = next((p for p in people if p.get("name") == PERSON_NAME), None)

        if match is None:
            ws.command(
                {
                    "type": "person/create",
                    "name": PERSON_NAME,
                    "device_trackers": [TRACKER_ENTITY],
                }
            )
            print(f"Created person {PERSON_NAME!r}")
        elif TRACKER_ENTITY in (match.get("device_trackers") or []):
            print(f"Person {PERSON_NAME!r} already exists and is wired up")
        elif "id" not in match:
            print(
                f"WARNING: a YAML-defined person named {PERSON_NAME!r} already exists.\n"
                f"  Add {TRACKER_ENTITY} to its device_trackers by hand, or remove the\n"
                "  YAML person and re-run this script."
            )
        else:
            ws.command(
                {
                    "type": "person/update",
                    "person_id": match["id"],
                    "name": PERSON_NAME,
                    "device_trackers": sorted(
                        set(match.get("device_trackers") or []) | {TRACKER_ENTITY}
                    ),
                }
            )
            print(f"Attached {TRACKER_ENTITY} to the existing {PERSON_NAME!r} person")
    finally:
        ws.close()

    # 3. report ------------------------------------------------------------
    print()
    for entity in (source, TRACKER_ENTITY, PERSON_ENTITY):
        try:
            state = ha.request(f"/api/states/{entity}")
            print(f"  {entity:34} {state['state']}")
        except SystemExit:
            print(f"  {entity:34} (not created yet - give HA a few seconds)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
