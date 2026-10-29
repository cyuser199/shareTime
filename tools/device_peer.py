"""In-memory simulated friend for a one-phone test; loopback-only control API.

Only public status, hashes and byte lengths are reported. Received photos are
decrypted for verification then discarded; no personal media is written to disk.
"""
import base64
import hashlib
import hmac
import json
import os
import pathlib
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import websocket
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, load_der_public_key

ROOT = pathlib.Path(__file__).resolve().parents[1]
CA = str(ROOT / 'android/app/src/main/res/raw/sharetime_ca.pem')
URL = 'https://192.168.1.191:8443'


class Peer:
    def __init__(self):
        self.private = ec.generate_private_key(ec.SECP256R1())
        public = base64.b64encode(self.private.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()
        with httpx.Client(verify=CA, trust_env=False, timeout=12) as client:
            response = client.post(URL + '/register', json={'name': 'TestFriend', 'publicKey': public})
            response.raise_for_status()
            self.auth = response.json()
        self.certificate = None
        self.peer_key = None
        self.socket = None
        self.status = {'connected': False}
        self.events = []
        self.connect()
        threading.Thread(target=self.heartbeat, daemon=True).start()

    def connect(self):
        ws = websocket.create_connection(URL.replace('https://', 'wss://') + '/connect', sslopt={'ca_certs': CA}, timeout=35, http_no_proxy=['192.168.1.191'])
        self.socket = ws
        ws.send(json.dumps({'token': self.auth['token'], 'certificate': self.certificate}))
        threading.Thread(target=self.receive, args=(ws,), daemon=True).start()

    def send(self, kind, **extra):
        self.socket.send(json.dumps({'type': kind, **extra}))

    def key(self, rid):
        public = load_der_public_key(base64.b64decode(self.peer_key))
        secret = self.private.exchange(ec.ECDH(), public)
        prk = hmac.digest(b'ShareTime-v1', secret, 'sha256')
        return hmac.digest(prk, ('record:' + rid).encode() + b'\x01', 'sha256')

    def receive(self, ws):
        try:
            while self.socket is ws:
                event = json.loads(ws.recv())
                kind = event['type']
                if kind == 'state':
                    self.status.update(connected=True, paired=event['paired'], online=event.get('online', False))
                    if event.get('peer'):
                        self.peer_key = event['peer']['publicKey']
                elif kind == 'paired':
                    self.certificate = event['certificate']
                elif kind == 'unpaired':
                    self.certificate = None
                    self.status.update(paired=False, round='', phase='')
                elif kind == 'code':
                    self.status['code'] = event['code']
                elif kind == 'pair_request':
                    self.status['pairRequest'] = event['id']
                elif kind == 'round':
                    self.status.update(round=event['id'], phase=event['phase'], submitted=event['submitted'])
                elif kind == 'record':
                    raw = base64.b64decode(event['body'])
                    plain = AESGCM(self.key(event['id'])).decrypt(raw[:12], raw[12:], event['id'].encode())
                    self.status['received'] = {'bytes': len(plain), 'sha256': hashlib.sha256(plain).hexdigest(), 'jpeg': plain[:2] == b'\xff\xd8'}
                    del plain
                    self.send('ack', id=event['id'])
                elif kind == 'ended':
                    self.status.update(round='', phase='', reason=event.get('reason'))
                elif kind in ['error', 'notice']:
                    self.status['message'] = event.get('message')
                if kind != 'pong':
                    self.events.append({'type': kind, 'at': round(time.time(), 2)})
                    self.events = self.events[-40:]
        except Exception as error:
            if self.socket is ws:
                self.status['connected'] = False
                self.status['error'] = type(error).__name__

    def heartbeat(self):
        while True:
            time.sleep(20)
            try:
                self.send('ping')
            except Exception:
                pass

    def action(self, obj):
        kind = obj.pop('action')
        if kind == 'submit':
            rid = self.status['round']
            image = (ROOT / 'docs/device-test-20261029/peer-test.jpg').read_bytes()
            nonce = os.urandom(12)
            body = base64.b64encode(nonce + AESGCM(self.key(rid)).encrypt(nonce, image, rid.encode())).decode()
            self.send('submit', id=rid, body=body)
        elif kind == 'disconnect':
            ws = self.socket
            self.socket = None
            ws.close()
            self.status['connected'] = False
        elif kind == 'reconnect':
            self.connect()
        else:
            if kind in ['respond', 'cancel']:
                obj.setdefault('id', self.status['round'])
            self.send(kind, **obj)


peer = Peer()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        body = json.dumps({**peer.status, 'events': peer.events}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if size > 2048:
                raise ValueError()
            peer.action(json.loads(self.rfile.read(size)))
            self.send_response(200); self.end_headers(); self.wfile.write(b'{"ok":true}')
        except Exception as error:
            self.send_response(400); self.end_headers(); self.wfile.write(type(error).__name__.encode())


print('Test peer ready on loopback 127.0.0.1:18876 (no credentials saved)', flush=True)
ThreadingHTTPServer(('127.0.0.1', 18876), Handler).serve_forever()
