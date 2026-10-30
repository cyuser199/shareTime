import asyncio
import base64
import json
import time

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from fastapi.testclient import TestClient

from app import create_app


def test_update_manifest_and_download(tmp_path):
    import hashlib
    app = create_app(tmp_path / 'data')
    updates = tmp_path / 'updates'
    updates.mkdir()
    with TestClient(app) as c:
        assert c.get('/updates/latest').json() == {'versionCode': 0}
        assert c.get('/updates/apk/invalid').status_code == 404
        body = b'synthetic apk test'
        digest = hashlib.sha256(body).hexdigest()
        (updates / (digest + '.apk')).write_bytes(body)
        manifest = {'versionCode': 6, 'versionName': '0.4.0', 'notes': 'test', 'sha256': digest,
                    'apkUrl': 'https://example.com/updates/apk/' + digest}
        (updates / 'latest.json').write_text(json.dumps(manifest))
        assert c.get('/updates/latest').json() == manifest
        download = c.get('/updates/apk/' + digest)
        assert download.content == body
        assert download.headers['content-type'] == 'application/vnd.android.package-archive'
        (updates / 'latest.json').write_text('{}')
        assert c.get('/updates/latest').status_code == 503


def identity(client, name='friend'):
    key = ec.generate_private_key(ec.SECP256R1())
    public = base64.b64encode(key.public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()
    r = client.post('/register', json={'name': name, 'publicKey': public})
    assert r.status_code == 200
    return r.json()


def receive(ws, kind):
    for _ in range(12):
        event = ws.receive_json()
        if event['type'] == kind:
            return event
    raise AssertionError('Missing ' + kind)


def auth(ws, device, cert=None):
    ws.send_json({'token': device['token'], 'certificate': cert})
    return receive(ws, 'state')


def pair(a, b):
    a.send_json({'type': 'create_code'})
    code = receive(a, 'code')['code']
    b.send_json({'type': 'join', 'code': code})
    request = receive(a, 'pair_request')
    assert receive(b, 'notice')
    a.send_json({'type': 'confirm_pair', 'id': request['id'], 'accept': True})
    ca = receive(a, 'paired')['certificate']
    cb = receive(b, 'paired')['certificate']
    assert ca == cb
    assert receive(a, 'state')['paired']
    assert receive(b, 'state')['paired']
    return ca


def invite(a, b):
    a.send_json({'type': 'invite'})
    rid = receive(a, 'round')['id']
    assert receive(b, 'round')['id'] == rid
    return rid


def test_offline_invite_reconnect_and_expiry(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as c:
        da, db = identity(c, 'A'), identity(c, 'B')
        with c.websocket_connect('/connect') as a:
            auth(a, da)
            with c.websocket_connect('/connect') as b:
                auth(b, db)
                cert = pair(a, b)
            assert not receive(a, 'state')['online']
            a.send_json({'type': 'invite'})
            waiting = receive(a, 'round')
            rid = waiting['id']
            assert waiting['phase'] == 'waiting'
            assert waiting['remaining'] > 86000
            assert not app.state.relay.rounds[rid]['bodies']
            with c.websocket_connect('/connect') as b:
                auth(b, db, cert)
                assert receive(b, 'round')['id'] == rid
                b.send_json({'type': 'respond', 'id': rid, 'accept': True})
                assert receive(b, 'round')['phase'] == 'active'
                assert app.state.relay.rounds[rid]['expires'] - time.monotonic() <= 600
            app.state.relay.rounds[rid]['expires'] = time.monotonic() - 1
            a.send_json({'type': 'ping'})
            assert receive(a, 'ended')
            assert rid not in app.state.relay.rounds


def test_pair_consent_fair_reveal_and_no_content_on_disk(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as c:
        da, db = identity(c, 'A'), identity(c, 'B')
        with c.websocket_connect('/connect') as a, c.websocket_connect('/connect') as b:
            assert not auth(a, da)['paired']
            auth(b, db)
            pair(a, b)
            rid = invite(a, b)
            body = base64.b64encode(b'opaque-encrypted-payload-' * 4).decode()
            a.send_json({'type': 'submit', 'id': rid, 'body': body})
            assert receive(a, 'error')  # Cannot share before consent.
            assert not app.state.relay.rounds[rid]['bodies']
            b.send_json({'type': 'respond', 'id': rid, 'accept': True})
            assert receive(a, 'round')['phase'] == 'active'
            receive(b, 'round')
            a.send_json({'type': 'submit', 'id': rid, 'body': body})
            assert receive(a, 'round')['phase'] == 'active'
            assert receive(b, 'round')['phase'] == 'active'  # No early record event.
            b.send_json({'type': 'submit', 'id': rid, 'body': body})
            assert receive(a, 'record')['body'] == body
            assert receive(b, 'record')['body'] == body
            a.send_json({'type': 'ack', 'id': rid})
            b.send_json({'type': 'ack', 'id': rid})
            # Ping is a processing barrier.
            b.send_json({'type': 'ping'}); receive(b, 'pong')
            assert rid not in app.state.relay.rounds
            assert {p.name for p in tmp_path.iterdir()} == {'signing.key', 'stats.json'}
            assert json.loads((tmp_path / 'stats.json').read_text()) == {'registered_devices': 2}


def test_rejection_expiry_and_unpair(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as c:
        da, db = identity(c), identity(c)
        with c.websocket_connect('/connect') as a, c.websocket_connect('/connect') as b:
            auth(a, da); auth(b, db); pair(a, b)
            rid = invite(a, b)
            a.send_json({'type': 'respond', 'id': rid, 'accept': True})
            receive(a, 'error')  # Inviter cannot impersonate recipient consent.
            b.send_json({'type': 'respond', 'id': rid, 'accept': False})
            receive(a, 'ended'); receive(b, 'ended')
            assert not app.state.relay.rounds
            rid = invite(a, b)
            app.state.relay.rounds[rid]['expires'] = time.monotonic() - 1
            b.send_json({'type': 'respond', 'id': rid, 'accept': True})
            receive(a, 'ended'); receive(b, 'ended')
            a.send_json({'type': 'unpair'})
            receive(a, 'unpaired'); receive(b, 'unpaired')
            assert not app.state.relay.pairs


def test_credential_tampering_and_cross_pair_submission(tmp_path):
    app = create_app(tmp_path)
    relay = app.state.relay
    token = relay.sign({'kind': 'device', 'id': 'one'})
    with pytest.raises(ValueError):
        relay.verify(token[:-3] + 'xxx', 'device')
    with pytest.raises(ValueError):
        relay.verify(token, 'pair')
    with TestClient(app) as c:
        da, db, dc = identity(c), identity(c), identity(c)
        with c.websocket_connect('/connect') as a, c.websocket_connect('/connect') as b, c.websocket_connect('/connect') as outsider:
            auth(a, da); auth(b, db); auth(outsider, dc); cert = pair(a, b)
            rid = invite(a, b)
            outsider.send_json({'type': 'cancel', 'id': rid})
            receive(outsider, 'error')
            assert rid in relay.rounds


def test_certificates_restore_pair_after_server_restart(tmp_path):
    with TestClient(create_app(tmp_path)) as c:
        da, db = identity(c), identity(c)
        with c.websocket_connect('/connect') as a, c.websocket_connect('/connect') as b:
            auth(a, da); auth(b, db); cert = pair(a, b)
    with TestClient(create_app(tmp_path)) as c:
        with c.websocket_connect('/connect') as a, c.websocket_connect('/connect') as b:
            assert auth(a, da, cert)['paired']
            assert auth(b, db, cert)['online']
            invite(a, b)
        assert c.get('/stats').json()['registered_devices'] == 2


def test_invalid_public_key_and_join_rate_limit(tmp_path):
    with TestClient(create_app(tmp_path)) as c:
        assert c.post('/register', json={'name': 'A', 'publicKey': 'a' * 70}).status_code == 400
        device = identity(c)
        with c.websocket_connect('/connect') as ws:
            auth(ws, device)
            for _ in range(8):
                ws.send_json({'type': 'join', 'code': '00000000'})
                receive(ws, 'error')
        assert c.get('/health').json()['status'] == 'ok'


def test_simultaneous_invites_merge_and_duplicate_submit_cannot_replace(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as c:
        da, db = identity(c), identity(c)
        with c.websocket_connect('/connect') as a, c.websocket_connect('/connect') as b:
            auth(a, da); auth(b, db); pair(a, b)
            rid = invite(a, b)
            b.send_json({'type': 'invite'})
            assert receive(a, 'round')['phase'] == 'active'
            assert receive(b, 'round')['id'] == rid
            assert len(app.state.relay.rounds) == 1
            original = base64.b64encode(b'first-encrypted-body' * 4).decode()
            a.send_json({'type': 'submit', 'id': rid, 'body': original})
            receive(a, 'round'); receive(b, 'round')
            a.send_json({'type': 'submit', 'id': rid, 'body': base64.b64encode(b'other' * 20).decode()})
            receive(a, 'error')
            assert app.state.relay.rounds[rid]['bodies'][da['id']] == original


def test_offline_peer_cannot_restore_revoked_pair(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as c:
        da, db = identity(c), identity(c)
        with c.websocket_connect('/connect') as a:
            auth(a, da)
            with c.websocket_connect('/connect') as b:
                auth(b, db); cert = pair(a, b)
            a.send_json({'type': 'unpair'}); receive(a, 'unpaired')
            with c.websocket_connect('/connect') as b:
                b.send_json({'token': db['token'], 'certificate': cert})
                receive(b, 'unpaired')
                assert receive(b, 'state')['paired'] is False
            assert not app.state.relay.pairs
