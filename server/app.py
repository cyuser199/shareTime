"""ShareTime relay. Counters, signing key and bounded pairing metadata persist.

Record bodies are opaque end-to-end ciphertext, bounded in RAM and never logged.
One uvicorn worker is required: coordination deliberately has no persistent DB.
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from contextlib import asynccontextmanager, suppress
from pathlib import Path

from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse
from urllib.parse import urlencode
from pydantic import BaseModel, Field
from pairing import PairingRegistry
from groups import FriendGroups

class ActionError(ValueError):
    """A safe, user-facing operation failure."""


MAX_BODY = 4_500_000  # <=3 MiB compressed image plus AES-GCM and base64
INVITE_TTL = 24 * 60 * 60
ROUND_TTL = 600


def b64(data):
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


class Relay:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        keyfile = self.directory / 'signing.key'
        if not keyfile.exists():
            fd = os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as f:
                f.write(secrets.token_bytes(32))
        self.key = keyfile.read_bytes()
        statsfile = self.directory / 'stats.json'
        self.stats = json.loads(statsfile.read_text()) if statsfile.exists() else {'registered_devices': 0}
        self.connections = {}
        self.codes = {}
        self.pending = {}
        self.pairs = {}
        self.revoked = {}
        self.rounds = {}
        self.rate = {}
        self.lock = asyncio.Lock()
        self.registry = PairingRegistry(self)
        self.friends = FriendGroups(self)

    def sign(self, obj):
        body = b64(json.dumps(obj, separators=(',', ':'), ensure_ascii=False).encode())
        return body + '.' + b64(hmac.digest(self.key, body.encode(), 'sha256'))

    def verify(self, token, kind):
        try:
            body, sig = token.split('.')
            if not hmac.compare_digest(sig, b64(hmac.digest(self.key, body.encode(), 'sha256'))):
                raise ValueError()
            obj = json.loads(base64.urlsafe_b64decode(body + '=' * (-len(body) % 4)))
            if obj['kind'] != kind:
                raise ValueError()
            return obj
        except (ValueError, KeyError, TypeError):
            raise ValueError('Invalid credential') from None

    def limited(self, bucket, limit, period=60):
        now = time.monotonic()
        timestamps = [t for t in self.rate.get(bucket, []) if now - t < period]
        if len(timestamps) >= limit:
            return True
        self.rate[bucket] = timestamps + [now]
        return False

    async def send(self, device, event):
        conn = self.connections.get(device)
        if not conn:
            return False
        try:
            await asyncio.wait_for(conn['ws'].send_json(event), 5)
            return True
        except (RuntimeError, OSError, asyncio.TimeoutError, WebSocketDisconnect):
            return False

    def pair_for(self, device):
        return next((p for p in self.pairs.values() if device in p['members']), None)

    def round_for(self, pair):
        return next((r for r in self.rounds.values() if r['pair'] == pair), None)

    async def broadcast_pair(self, pair, event):
        for member in pair['members']:
            await self.send(member, event)

    async def close_round(self, record, reason):
        record['bodies'].clear()
        self.rounds.pop(record['id'], None)
        pair = self.pairs.get(record['pair'])
        if pair:
            await self.broadcast_pair(pair, {'type': 'ended', 'reason': reason})

    async def sweep(self):
        self.registry.clean()
        await self.friends.sweep()
        now = time.monotonic()
        self.codes = {k: v for k, v in self.codes.items() if v['expires'] > now}
        self.pending = {k: v for k, v in self.pending.items() if v['expires'] > now}
        self.revoked = {k: v for k, v in self.revoked.items() if v > now}
        self.rate = {k: [t for t in v if now - t < 60] for k, v in self.rate.items() if v and now - v[-1] < 60}
        for record in list(self.rounds.values()):
            if record['expires'] < now:
                await self.close_round(record, '已超时，本轮已结束')
        # Pair certificates live on clients; discard idle routing mappings.
        for pid, pair in list(self.pairs.items()):
            if not any(m in self.connections for m in pair['members']) and not self.round_for(pid):
                self.pairs.pop(pid, None)

    async def state(self, device):
        pair = self.pair_for(device)
        if not pair:
            await self.send(device, {'type': 'state', 'paired': False})
            return
        peer = next(m for m in pair['members'] if m != device)
        await self.send(device, {'type': 'state', 'paired': True, 'online': peer in self.connections,
                                 'peer': pair['members'][peer]})
        record = self.round_for(pair['id'])
        if record:
            event = {'type': 'round', 'id': record['id'], 'phase': record['phase'],
                     'initiator': record['initiator'], 'submitted': list(record['bodies']),
                     'remaining': max(0, int(record['expires'] - time.monotonic()))}
            await self.send(device, event)
            if record['phase'] == 'complete':
                await self.deliver(record, device)

    async def deliver(self, record, device):
        pair = self.pairs[record['pair']]
        peer = next(m for m in pair['members'] if m != device)
        if peer in record['bodies']:
            await self.send(device, {'type': 'record', 'id': record['id'], 'body': record['bodies'][peer]})

    async def handle(self, device, obj):
        kind = obj.get('type')
        uid = device['id']
        pair = self.pair_for(uid)
        if kind == 'ping':
            await self.send(uid, {'type': 'pong'})
            return
        if self.limited('events:' + uid, 90):
            raise ActionError('操作过于频繁，请稍后再试')
        if kind == 'reserve_fixed':
            await self.registry.reserve(device, obj.get('ticket'), renew=True)
            return
        if kind == 'join_fixed':
            await self.registry.join(device, obj.get('code'))
            return
        if kind == 'cancel_fixed':
            self.registry.data['pending'].pop(uid, None)
            self.registry.save()
            await self.send(uid, {'type': 'pair_waiting', 'message': '配对申请已取消'})
            return
        if kind == 'paired_ack':
            self.registry.ack(device)
            return
        if kind == 'create_code':
            if pair:
                raise ActionError('请先解除现有配对')
            self.codes = {k: v for k, v in self.codes.items() if v['device']['id'] != uid}
            if len(self.codes) >= 1000:
                raise ActionError('服务器繁忙')
            code = ''.join(secrets.choice('0123456789') for _ in range(8))
            while code in self.codes:
                code = ''.join(secrets.choice('0123456789') for _ in range(8))
            self.codes[code] = {'device': device, 'expires': time.monotonic() + 300}
            await self.send(uid, {'type': 'code', 'code': code, 'expiresIn': 300})
            return
        if kind == 'join':
            if pair or self.limited('join:' + uid, 6):
                raise ActionError('已有配对，或尝试次数过多')
            code = obj.get('code', '')
            entry = self.codes.get(code)
            if not entry or entry['expires'] < time.monotonic() or entry['device']['id'] == uid:
                raise ActionError('配对码无效或已过期')
            owner = entry['device']
            if owner['id'] not in self.connections or self.pair_for(owner['id']):
                raise ActionError('对方不在线或已配对')
            if any(p['owner']['id'] == owner['id'] or p['guest']['id'] == uid for p in self.pending.values()):
                raise ActionError('已有配对确认，请稍后重试')
            request_id = secrets.token_urlsafe(16)
            self.pending[request_id] = {'owner': owner, 'guest': device, 'expires': entry['expires']}
            await self.send(owner['id'], {'type': 'pair_request', 'id': request_id, 'name': device['name']})
            await self.send(uid, {'type': 'notice', 'message': '已申请配对，等待对方确认'})
            return
        if kind == 'confirm_pair':
            pending = self.pending.get(obj.get('id'))
            if not pending or pending['expires'] < time.monotonic() or pending['owner']['id'] != uid:
                raise ActionError('配对请求已失效')
            self.pending.pop(obj['id'])
            guest = pending['guest']
            if not obj.get('accept'):
                await self.send(guest['id'], {'type': 'notice', 'message': '对方未同意配对'})
                return
            if self.pair_for(uid) or self.pair_for(guest['id']):
                raise ActionError('设备已配对')
            pid = secrets.token_urlsafe(24)
            members = {uid: device, guest['id']: guest}
            pair = {'id': pid, 'members': members}
            cert = self.sign({'kind': 'pair', **pair})
            self.pairs[pid] = pair
            self.codes = {k: v for k, v in self.codes.items() if v['device']['id'] not in members}
            await self.broadcast_pair(pair, {'type': 'paired', 'certificate': cert})
            for member in members:
                await self.state(member)
            return
        if not pair:
            raise ActionError('请先配对')
        peer = next(m for m in pair['members'] if m != uid)
        if kind == 'unpair':
            self.registry.unpair(pair['members'])
            record = self.round_for(pair['id'])
            if record:
                await self.close_round(record, '配对已解除')
            self.pairs.pop(pair['id'])
            self.revoked[pair['id']] = time.monotonic() + 86400
            await self.broadcast_pair(pair, {'type': 'unpaired'})
            return
        if kind == 'invite':
            current = self.round_for(pair['id'])
            if current:
                if current['phase'] == 'waiting' and current['initiator'] != uid:
                    current['phase'] = 'active'  # Simultaneous invitations are mutual consent.
                    current['expires'] = time.monotonic() + ROUND_TTL
                for member in pair['members']:
                    await self.state(member)
                return
            if len(self.rounds) >= 100:
                raise ActionError('服务器繁忙')
            rid = secrets.token_urlsafe(24)
            record = {'id': rid, 'pair': pair['id'], 'initiator': uid, 'phase': 'waiting',
                      'expires': time.monotonic() + INVITE_TTL, 'bodies': {}, 'acked': set()}
            self.rounds[rid] = record
            for member in pair['members']:
                await self.state(member)
            return
        record = self.rounds.get(obj.get('id'))
        if not record or record['pair'] != pair['id']:
            raise ActionError('本轮记录已结束')
        if record['expires'] < time.monotonic():
            await self.close_round(record, '已超时，本轮已结束')
            return
        if kind == 'respond':
            if uid == record['initiator'] or record['phase'] != 'waiting':
                raise ActionError('邀请状态已变化')
            if not obj.get('accept'):
                await self.close_round(record, '对方暂时不方便')
                return
            record['phase'] = 'active'
            record['expires'] = time.monotonic() + ROUND_TTL
        elif kind == 'submit':
            if record['phase'] != 'active' or uid in record['bodies']:
                raise ActionError('请等待好友同意，或你已经提交')
            body = obj.get('body')
            if not isinstance(body, str) or len(body) < 40 or len(body) > MAX_BODY:
                raise ActionError('图片大小不符合要求')
            try:
                base64.b64decode(body, validate=True)
            except ValueError:
                raise ActionError('无效图片数据') from None
            record['bodies'][uid] = body
            if len(record['bodies']) == 2:
                record['phase'] = 'complete'
        elif kind == 'ack':
            if record['phase'] == 'complete':
                record['acked'].add(uid)
                if len(record['acked']) == 2:
                    record['bodies'].clear()
                    self.rounds.pop(record['id'])
            return
        elif kind == 'cancel':
            await self.close_round(record, '本轮记录已结束')
            return
        else:
            raise ActionError('不支持的操作')
        for member in pair['members']:
            await self.state(member)


def create_app(directory=None):
    relay = Relay(directory or os.environ.get('SHARETIME_DATA', 'data'))

    @asynccontextmanager
    async def lifespan(app):
        async def cleanup():
            while True:
                await asyncio.sleep(5)
                async with relay.lock:
                    await relay.sweep()
        task = asyncio.create_task(cleanup())
        yield
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        relay.rounds.clear()

    app = FastAPI(title='ShareTime', docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.relay = relay

    @app.get('/health')
    async def health():
        return {'status': 'ok', 'protocol': 1}

    updates = Path(os.environ.get('SHARETIME_UPDATES', str(relay.directory.parent / 'updates')))

    @app.get('/updates/latest')
    async def latest_update():
        manifest = updates / 'latest.json'
        if not manifest.exists():
            return {'versionCode': 0}
        try:
            data = json.loads(manifest.read_text())
            digest = data['sha256']
            if not isinstance(digest, str) or len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
                raise ValueError()
            if not isinstance(data['versionCode'], int) or data['versionCode'] < 1 or not data['apkUrl'].startswith('https://'):
                raise ValueError()
            if not (updates / (digest + '.apk')).is_file():
                raise ValueError()
            return data
        except (ValueError, KeyError, TypeError):
            raise HTTPException(503, 'Update unavailable') from None

    @app.get('/updates/apk/{digest}')
    async def update_apk(digest: str):
        if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
            raise HTTPException(404)
        apk = updates / (digest + '.apk')
        if not apk.is_file():
            raise HTTPException(404)
        return FileResponse(apk, media_type='application/vnd.android.package-archive', filename='ShareTime-update.apk',
                            headers={'Cache-Control': 'public, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff'})

    @app.get('/pair', response_class=HTMLResponse)
    async def pair_link(code: str):
        if len(code) != 8 or not code.isascii() or not code.isdigit():
            raise HTTPException(400, 'Invalid pairing code')
        import html
        base = os.environ.get('SHARETIME_PUBLIC_URL', 'https://cyadmin.xyz/sharetime').rstrip('/')
        target = 'sharetime://pair?' + urlencode({'code': code, 'server': base})
        return HTMLResponse('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                            '<title>此刻 · 好友配对</title><body style="font-family:system-ui;max-width:420px;margin:64px auto;padding:24px">'
                            '<h1>与好友连接此刻</h1><p>固定配对码</p><h2>' + code + '</h2><p>仅使用信任的好友提供的链接。对方在线时将直接配对。</p>'
                            '<p><a href="' + html.escape(target, quote=True) + '">用此刻打开</a></p>'
                            '<p><a href="https://github.com/cyuser199/shareTime/releases">下载安装此刻</a></p></body></html>',
                            headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer'})

    @app.get('/stats')
    async def stats():
        return {**relay.stats, 'online_devices': len(set(relay.connections) | set(relay.friends.connections))}

    class Registration(BaseModel):
        name: str = Field(min_length=1, max_length=24)
        publicKey: str = Field(min_length=50, max_length=512)

    @app.post('/register')
    async def register(body: Registration, request: Request):
        async with relay.lock:
            if relay.limited('register:' + request.client.host, 10):
                raise HTTPException(429, 'Too many registrations')
            try:
                key = load_der_public_key(base64.b64decode(body.publicKey, validate=True))
                if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
                    raise ValueError()
            except (ValueError, TypeError):
                raise HTTPException(400, 'P-256 public key required') from None
            device = {'kind': 'device', 'id': secrets.token_urlsafe(24), 'name': body.name.strip() or '好友',
                      'publicKey': body.publicKey}
            relay.stats['registered_devices'] += 1
            temporary = relay.directory / 'stats.tmp'
            temporary.write_text(json.dumps(relay.stats))
            temporary.replace(relay.directory / 'stats.json')
            return {'token': relay.sign(device), 'id': device['id']}

    @app.websocket('/connect')
    async def connect(ws: WebSocket):
        await ws.accept()
        device = None
        try:
            auth = await asyncio.wait_for(ws.receive_json(), 10)
            if not isinstance(auth, dict) or not isinstance(auth.get('token'), str):
                raise ValueError('Invalid authentication')
            device = relay.verify(auth.get('token', ''), 'device')
            uid = device['id']
            if auth.get('mode') == 'friends':
                await relay.friends.connect(ws, device)
                return
            async with relay.lock:
                await relay.sweep()
                if len(relay.connections) >= 200 and uid not in relay.connections:
                    raise ActionError('服务器繁忙')
                if uid in relay.connections:
                    await relay.connections[uid]['ws'].close(code=4001)
                relay.connections[uid] = {'ws': ws, 'device': device}
                certificate = auth.get('certificate')
                if certificate:
                    pair = relay.verify(certificate, 'pair')
                    if uid not in pair['members'] or pair['members'][uid] != device:
                        raise ActionError('无效配对凭据')
                    conflict = any(relay.pair_for(member) and relay.pair_for(member)['id'] != pair['id'] for member in pair['members'])
                    if pair['id'] in relay.revoked or conflict:
                        await ws.send_json({'type': 'unpaired'})
                    else:
                        relay.pairs[pair['id']] = {'id': pair['id'], 'members': pair['members']}
                if auth.get('pairingProtocol') == 2:
                    await relay.registry.reconnect(device, auth.get('reservation'))
                await relay.state(uid)
                pair = relay.pair_for(uid)
                if pair:
                    for member in pair['members']:
                        if member != uid:
                            await relay.state(member)
            while True:
                raw = await asyncio.wait_for(ws.receive_text(), 90)
                if len(raw) > MAX_BODY + 1024:
                    raise ActionError('数据过大')
                try:
                    obj = json.loads(raw)
                    if not isinstance(obj, dict):
                        raise ActionError('消息格式错误')
                    async with relay.lock:
                        await relay.handle(device, obj)
                except (ValueError, KeyError, TypeError) as error:
                    # Only controlled ActionError messages are safe to show; no content logs.
                    message = str(error) if isinstance(error, ActionError) else '消息格式有误，请重试'
                    await ws.send_json({'type': 'error', 'message': message})
        except (WebSocketDisconnect, RuntimeError, ValueError, asyncio.TimeoutError):
            with suppress(Exception):
                await ws.close(code=4000)
        finally:
            if device:
                async with relay.lock:
                    uid = device['id']
                    if relay.connections.get(uid, {}).get('ws') is ws:
                        relay.connections.pop(uid, None)
                        pair = relay.pair_for(uid)
                        if pair:
                            for member in pair['members']:
                                if member != uid:
                                    await relay.state(member)

    return app


if __name__ == '__main__':
    import uvicorn
    uvicorn.run(create_app(), host='127.0.0.1', port=8765, ws_max_size=MAX_BODY + 2048,
                ws_per_message_deflate=False, access_log=False)
