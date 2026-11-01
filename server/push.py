"""Bounded FCM notification routing; no photos, names or pairing codes leave the relay."""
import asyncio
import hashlib
import json
import os
import time
from pathlib import Path


class PushRegistry:
    TTL = 30 * 86400

    def __init__(self, directory):
        self.file = Path(directory) / 'push-devices.json'
        self.devices = json.loads(self.file.read_text()) if self.file.exists() else {}
        self.queue = asyncio.Queue(maxsize=128)
        self.pending = set()
        self.sent = {}
        self.credentials = None
        self.project = None
        self.status = 'not_configured'
        credential_file = os.environ.get('SHARETIME_FCM_CREDENTIALS')
        if credential_file:
            from google.oauth2 import service_account
            self.credentials = service_account.Credentials.from_service_account_file(
                credential_file, scopes=['https://www.googleapis.com/auth/firebase.messaging'])
            self.project = self.credentials.project_id
            self.status = 'configured'
        self.clean()

    @property
    def enabled(self):
        return self.credentials is not None and os.environ.get('SHARETIME_FCM_ENABLED', '1') == '1'

    def save(self):
        tmp = self.file.with_suffix('.tmp')
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump(self.devices, f)
        tmp.replace(self.file)

    def clean(self):
        now = time.time()
        old = len(self.devices)
        self.devices = {u: d for u, d in self.devices.items() if d.get('expires', 0) > now}
        if old != len(self.devices): self.save()
        self.sent = {k: v for k, v in self.sent.items() if v > now - 86400}

    def register(self, uid, token, mode):
        self.clean()
        # A refreshed/reassigned FCM token must have exactly one local owner.
        self.devices = {u: d for u, d in self.devices.items() if u == uid or d['token'] != token}
        if uid not in self.devices and len(self.devices) >= 10000:
            raise ValueError('Push registry full')
        self.devices[uid] = {'token': token, 'mode': mode, 'expires': time.time() + self.TTL}
        self.save()

    def remove(self, uid):
        if self.devices.pop(uid, None) is not None: self.save()

    def notify(self, uid, mode, event, round_id, revision=0):
        route = self.devices.get(uid)
        if not self.enabled or not route or route['expires'] <= time.time() or route['mode'] != mode: return
        key = (uid, event, round_id, revision, mode)
        if key in self.pending or key in self.sent: return
        if self.queue.full(): return  # Never block record handling or the heartbeat.
        self.pending.add(key)
        self.queue.put_nowait((key, route['token']))

    def send(self, token, key):
        import httpx
        from google.auth.transport.requests import Request
        if not self.credentials.valid:
            self.credentials.refresh(Request())
        event_id = hashlib.sha256(repr(key).encode()).hexdigest()[:32]
        message = {'token': token, 'data': {'event': key[1], 'eventId': event_id,
                                          'mode': key[4]},
                   'android': {'priority': 'high', 'ttl': '86400s', 'collapse_key': 'sharetime',
                               'restricted_package_name': 'cn.sharetime.app'}}
        with httpx.Client(timeout=10, trust_env=False) as client:
            response = client.post(f'https://fcm.googleapis.com/v1/projects/{self.project}/messages:send',
                                   headers={'Authorization': 'Bearer ' + self.credentials.token}, json={'message': message})
        if response.status_code == 200: return 'sent'
        error = response.json().get('error', {})
        if any(d.get('errorCode') == 'UNREGISTERED' for d in error.get('details', [])): return 'unregistered'
        return 'failed'

    async def worker(self):
        while True:
            key, token = await self.queue.get()
            try:
                route = self.devices.get(key[0])
                if not self.enabled or not route or route['token'] != token or route['mode'] != key[4] or route['expires'] <= time.time(): continue
                result = await asyncio.to_thread(self.send, token, key)
                self.status = result
                if result == 'sent':
                    self.sent[key] = time.time()
                    if len(self.sent) > 8192: self.sent.pop(next(iter(self.sent)))
                if result == 'unregistered' and self.devices.get(key[0], {}).get('token') == token:
                    self.remove(key[0])
            except Exception:
                # Tokens, credentials, response bodies and private payloads are never logged.
                self.status = 'failed'
            finally:
                self.pending.discard(key)
                self.queue.task_done()
