"""Magic Phone Bridge: loopback-only, user-confirmed Windows Phone Link dialer.

No cloud communications, no access to ChatGPT account, no audio modifications.
Only fresh, explicit command messages from an armed Chrome extension are handled.
"""
from __future__ import annotations

import hmac
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import unicodedata
from http.server import BaseHTTPRequestHandler, HTTPServer
import tkinter as tk
from tkinter import messagebox
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
CONFIG = HERE / 'settings.json'
CONTACTS = HERE / 'contacts.json'
LOG = HERE / 'events.log'
CONTROLLER = HERE / 'phone_ui.ps1'
PORT = 18763
BRIDGE_VERSION = '0.21'


def only_number(value: str) -> str:
    translate = str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩', '01234567890123456789')
    value = value.translate(translate)
    return re.sub(r'[\s\-().]+', '', value)


def valid_number(value: str) -> bool:
    """Pilot accepts domestic Iranian numbers or +98 prefix."""
    return bool(re.fullmatch(r'(?:0\d{9,11}|\+98\d{10})', only_number(value)))


def normalize_name(name: str) -> str:
    name = unicodedata.normalize('NFKC', name).strip().replace('ي', 'ی').replace('ك', 'ک')
    name = re.sub(r'[\u200c\u200f\u200e]', ' ', name)
    compact = re.sub(r'\s+', ' ', name).strip()
    parts = compact.split(' ')
    if len(parts) >= 2 and all(re.fullmatch(r'[A-Za-z]', part) for part in parts):
        compact = ''.join(parts)
    return compact.casefold()


def load_or_make_settings() -> dict:
    if CONFIG.exists():
        obj = json.loads(CONFIG.read_text(encoding='utf-8'))
        if not isinstance(obj.get('secret'), str) or len(obj['secret']) < 32:
            raise ValueError('Invalid secret: remove settings.json only after disabling extension')
        return obj
    obj = {'secret': secrets.token_urlsafe(36)}
    CONFIG.write_text(json.dumps(obj, indent=2), encoding='utf-8')
    return obj


def load_contacts() -> dict:
    if not CONTACTS.exists():
        CONTACTS.write_text('{}\n', encoding='utf-8')
    obj = json.loads(CONTACTS.read_text(encoding='utf-8'))
    if not isinstance(obj, dict):
        raise ValueError('contacts.json must be an object {"name":"number"}')
    return obj


def find_exact_contact(name: str, contacts: dict) -> tuple[str, str] | None:
    wanted = normalize_name(name)
    matches = [(n, v) for n, v in contacts.items() if normalize_name(n) == wanted]
    if not matches:
        # Accept a unique first-name prefix, but NEVER guess between two matches.
        matches = [(n, v) for n, v in contacts.items()
                   if normalize_name(n).startswith(wanted + ' ')]
    if len(matches) != 1:
        return None
    n, v = matches[0]
    if not isinstance(v, str) or not valid_number(v):
        return None
    number = only_number(v)
    if number.startswith('+98'):
        number = '0' + number[3:]
    return n, number


def show_approval(name: str, phone: str) -> bool:
    root = tk.Tk()
    root.withdraw()
    root.attributes('-topmost', True)
    masked = phone[:3] + '*' * max(0, len(phone)-5) + phone[-2:]
    try:
        return bool(messagebox.askyesno(
            'Magic Phone: confirmation required',
            f'Call contact: {name}\nNumber: {masked}\n\n'
            'The call will be placed using your Samsung SIM through Phone Link.\n'
            'You are responsible for ensuring the recipient consents to an AI-assisted call.\n\n'
            'Place this call now?', parent=root,
        ))
    finally:
        root.destroy()


def invoke_dial(number: str) -> tuple[bool, str]:
    # No shell=True, no extra switches coming from the request, powershell script uses exact IDs.
    cmd = ['powershell.exe', '-NoProfile', '-NonInteractive', '-STA', '-ExecutionPolicy',
           'Bypass', '-File', str(CONTROLLER), '-Number', number]
    try:
        completed = subprocess.run(cmd, capture_output=True, text=True, timeout=80,
                                   creationflags=0x08000000 if sys.platform == 'win32' else 0)
        msg = (completed.stdout + '\n' + completed.stderr).strip()
        # Redact numeric sequences from diagnostics (never print a full phone number).
        msg = re.sub(r'(?<![0-9])[0-9]{7,}(?![0-9])', '[redacted]', msg)
        print(msg, flush=True)
        if completed.returncode == 0:
            return True, 'Dial action sent to verified Phone Link dial pane. Connection not yet confirmed.'
        return False, ('Phone Link UI stopped; call not confirmed. ' + msg[-450:])
    except Exception as exc:
        return False, f'Failed to reach UI automation: {type(exc).__name__}: {exc}'


def audit(event: str) -> None:
    # Do not log numbers, names, raw transcript, or secrets.
    from datetime import datetime, timezone
    with LOG.open('a', encoding='utf-8') as fp:
        fp.write(datetime.now(timezone.utc).isoformat() + ' ' + event + '\n')


class Handler(BaseHTTPRequestHandler):
    server_version = 'MagicPhoneLocal/' + BRIDGE_VERSION

    def log_message(self, *_args):
        return

    def _origin_ok(self) -> bool:
        """Allow extension requests even if Chrome omits Origin on privileged fetch.

        Chromium extensions with host_permissions can omit the Origin header for
        local GET requests. Browsers cannot forge the custom X-Magic-Token
        header cross-site without CORS preflight, which we deny to web origins.
        All real requests also require the separate high-entropy shared token.
        """
        if self.headers.get('Host', '').lower() != f'127.0.0.1:{PORT}':
            return False
        origin = self.headers.get('Origin', '')
        if not origin:
            return True
        return re.fullmatch(r'chrome-extension://[a-p]{32}', origin) is not None

    def _extension_origin(self) -> bool:
        origin = self.headers.get('Origin', '')
        return re.fullmatch(r'chrome-extension://[a-p]{32}', origin) is not None

    def _headers(self, status: int) -> None:
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        if self._origin_ok() and self._extension_origin():
            self.send_header('Access-Control-Allow-Origin', self.headers.get('Origin'))
            self.send_header('Vary', 'Origin')
            self.send_header('Access-Control-Allow-Headers', 'Content-Type, X-Magic-Token')
            self.send_header('Access-Control-Allow-Methods', 'POST, GET, OPTIONS')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()

    def _reply(self, status: int, obj: dict):
        self._headers(status)
        self.wfile.write(json.dumps(obj, ensure_ascii=False).encode('utf-8'))

    def do_OPTIONS(self):
        if not self._origin_ok() or not self._extension_origin() or self.path not in ('/call', '/health', '/check-contact'):
            self._reply(403, {'ok': False})
        else:
            self._reply(200, {'ok': True})

    def do_GET(self):
        # Health checks never dial, touch the phone UI, or return contact details.
        if self.path != '/health' or not self._origin_ok():
            self._reply(404, {'ok': False, 'reason': 'invalid_origin_or_path'})
            return
        token = self.headers.get('X-Magic-Token', '')
        if not hmac.compare_digest(token, self.server.shared_secret):
            self._reply(403, {'ok': False, 'reason': 'pairing_required'})
            return
        try:
            count = len(load_contacts())
            self._reply(200, {'ok': True, 'version': BRIDGE_VERSION, 'contacts_count': count})
        except Exception:
            self._reply(503, {'ok': False, 'reason': 'contacts_unavailable'})

    def do_POST(self):
        # /check-contact is read-only, for diagnosing import/matching without dial.
        if self.path not in ('/call', '/check-contact') or not self._origin_ok():
            self._reply(404, {'ok': False, 'reason': 'invalid_origin_or_path'})
            return
        provided = self.headers.get('X-Magic-Token', '')
        if not hmac.compare_digest(provided, self.server.shared_secret):
            self._reply(403, {'ok': False, 'reason': 'pairing_required'})
            return
        if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
            self._reply(415, {'ok': False, 'reason': 'json_required'})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            size = -1
        if not 0 < size <= 2048:
            self._reply(413, {'ok': False, 'reason': 'invalid_size'})
            return
        try:
            body = json.loads(self.rfile.read(size).decode('utf-8'))
            name = body.get('contact')
            if not isinstance(name, str) or not 1 <= len(name) <= 65:
                raise ValueError('invalid contact name')
            match = find_exact_contact(name, load_contacts())
            if not match:
                audit('unknown_contact')
                self._reply(404, {'ok': False, 'reason': 'contact_not_found_in_local_address_book'})
                return
            contact_name, phone = match
            if self.path == '/check-contact':
                self._reply(200, {'ok': True, 'reason': 'contact_found', 'display_name': contact_name})
                return
            if not show_approval(contact_name, phone):
                audit('declined')
                self._reply(200, {'ok': False, 'reason': 'declined_by_user'})
                return
            ok, result = invoke_dial(phone)
            audit('dial_invoked' if ok else 'dial_failed')
            self._reply(200 if ok else 409, {'ok': ok, 'result': result})
        except Exception as exc:
            audit('request_error')
            self._reply(400, {'ok': False, 'reason': type(exc).__name__})


def main():
    if sys.platform != 'win32':
        raise SystemExit('Windows only.')
    data = load_or_make_settings()
    load_contacts()
    server = HTTPServer(('127.0.0.1', PORT), Handler)
    server.shared_secret = data['secret']
    print('Magic Phone Local Control - v' + BRIDGE_VERSION, flush=True)
    print('1. Keep Phone Link open on Calls screen.', flush=True)
    print('2. Pair Chrome extension with the token below (one time).', flush=True)
    print('3. Commands only trigger after you ARM the extension.', flush=True)
    print('PAIRING TOKEN:', data['secret'], flush=True)
    print('Local server on 127.0.0.1:', PORT, flush=True)
    print('Ctrl+C to stop.', flush=True)
    try:
        server.serve_forever(poll_interval=0.3)
    except KeyboardInterrupt:
        print('Stopped by user.')
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
