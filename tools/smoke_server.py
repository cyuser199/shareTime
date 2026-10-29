"""Live TLS + two-client end-to-end encrypted round. Uses synthetic image data."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path

import httpx
import websocket
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
CA = None
URL = 'https://cyadmin.xyz/sharetime'


def receive(ws, kind):
    for _ in range(16):
        obj = json.loads(ws.recv())
        if obj['type'] == 'error':
            raise AssertionError(obj['message'])
        if obj['type'] == kind:
            return obj
    raise AssertionError('Missing event: ' + kind)


def send(ws, kind, **extra):
    ws.send(json.dumps({'type': kind, **extra}))


def main():
    with httpx.Client(verify=True, timeout=15, trust_env=False) as http:
        assert http.get(URL + '/health').json() == {'status': 'ok', 'protocol': 1}
        identities = []
        keys = []
        for name in ['SMOKE-A', 'SMOKE-B']:
            private = ec.generate_private_key(ec.SECP256R1())
            public = base64.b64encode(private.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()
            response = http.post(URL + '/register', json={'name': name, 'publicKey': public})
            response.raise_for_status()
            identities.append(response.json()); keys.append(private)
        a = websocket.create_connection(URL.replace('https://', 'wss://') + '/connect', sslopt={}, timeout=15, http_no_proxy=['cyadmin.xyz'])
        b = websocket.create_connection(URL.replace('https://', 'wss://') + '/connect', sslopt={}, timeout=15, http_no_proxy=['cyadmin.xyz'])
        try:
            for ws, identity in zip([a, b], identities):
                ws.send(json.dumps({'token': identity['token']})); receive(ws, 'state')
            send(a, 'create_code'); code = receive(a, 'code')['code']
            send(b, 'join', code=code)
            pending = receive(a, 'pair_request'); receive(b, 'notice')
            send(a, 'confirm_pair', id=pending['id'], accept=True)
            cert = receive(a, 'paired')['certificate']
            assert receive(b, 'paired')['certificate'] == cert
            receive(a, 'state'); receive(b, 'state')
            send(a, 'invite'); rid = receive(a, 'round')['id']; receive(b, 'round')
            send(b, 'respond', id=rid, accept=True)
            assert receive(a, 'round')['phase'] == 'active'; receive(b, 'round')
            # Same HKDF and AES-GCM wire format as Android Crypto.kt.
            shared_a = keys[0].exchange(ec.ECDH(), keys[1].public_key())
            shared_b = keys[1].exchange(ec.ECDH(), keys[0].public_key())
            assert shared_a == shared_b
            prk = hmac.digest(b'ShareTime-v1', shared_a, 'sha256')
            key = hmac.digest(prk, ('record:' + rid).encode() + b'\x01', 'sha256')
            # Synthetic 1x1 PNG; no real user media used.
            image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=')
            def encrypt():
                nonce = os.urandom(12)
                return base64.b64encode(nonce + AESGCM(key).encrypt(nonce, image, rid.encode())).decode()
            send(a, 'submit', id=rid, body=encrypt())
            assert receive(a, 'round')['phase'] == 'active'
            assert receive(b, 'round')['phase'] == 'active'
            send(b, 'submit', id=rid, body=encrypt())
            for ws in [a, b]:
                result = receive(ws, 'record')
                raw = base64.b64decode(result['body'])
                assert AESGCM(key).decrypt(raw[:12], raw[12:], rid.encode()) == image
                send(ws, 'ack', id=rid)
            send(b, 'ping'); receive(b, 'pong')
            send(a, 'unpair'); receive(a, 'unpaired'); receive(b, 'unpaired')
            print('PASS: HTTPS certificate verification, pairing consent, invitation, mutual reveal, encrypted transfer, plaintext integrity, receipts, unpair')
            print('Stats:', http.get(URL + '/stats').json())
        finally:
            a.close(); b.close()


if __name__ == '__main__':
    main()
