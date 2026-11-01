import asyncio
import json
import time
from fastapi.testclient import TestClient
from app import create_app, Relay
from push import PushRegistry
from test_app import identity


def test_registration_auth_consent_token_rotation_and_delete(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as client:
        assert client.get('/push/config').json() == {'enabled': False, 'provider': 'connection'}
        body = {'provider': 'fcm', 'pushToken': 'synthetic-token-a' * 8, 'privacyVersion': 8}
        assert client.post('/push/device', json=body).status_code == 401
        a, b = identity(client, 'A'), identity(client, 'B')
        auth = lambda d: {'Authorization': 'Bearer ' + d['token']}
        assert client.post('/push/device', headers=auth(a), json={**body, 'privacyVersion': 7}).status_code == 400
        assert client.post('/push/device', headers=auth(a), json=body).status_code == 200
        registry = app.state.relay.push
        assert a['id'] in registry.devices
        assert client.post('/push/device', headers=auth(b), json=body).status_code == 200
        assert a['id'] not in registry.devices  # a token never routes to two users
        assert PushRegistry(tmp_path).devices[b['id']]['mode'] == 'duo'
        client.post('/push/device', headers=auth(b), json={'provider': 'none'})
        assert not registry.devices and json.loads(registry.file.read_text()) == {}


def test_queue_dedup_mode_change_revocation_and_invalid_token_cleanup(tmp_path):
    async def run():
        p = PushRegistry(tmp_path)
        p.credentials = object()
        p.register('a', 'synthetic-registration-token', 'friends')
        p.notify('a', 'duo', 'invite', 'r')
        assert p.queue.empty()
        p.notify('a', 'friends', 'invite', 'r')
        p.notify('a', 'friends', 'invite', 'r')
        assert p.queue.qsize() == 1
        calls = []
        p.send = lambda token, key: calls.append((token, key)) or 'sent'
        task = asyncio.create_task(p.worker())
        await p.queue.join()
        assert len(calls) == 1
        p.notify('a', 'friends', 'invite', 'r')
        assert p.queue.empty()
        p.notify('a', 'friends', 'records', 'r', 2)
        p.register('a', 'synthetic-registration-token', 'duo')
        await p.queue.join()
        assert len(calls) == 1  # queued old-mode messages cannot cross modes
        p.send = lambda *_: 'unregistered'
        p.notify('a', 'duo', 'invite', 'r2')
        await p.queue.join()
        assert 'a' not in p.devices
        task.cancel()
        try: await task
        except asyncio.CancelledError: pass
    asyncio.run(run())


def test_push_expiry_and_background_failure_do_not_break_relay(tmp_path):
    p = PushRegistry(tmp_path)
    p.register('expired', 'synthetic-expired-token', 'duo')
    p.devices['expired']['expires'] = time.time() - 1
    p.clean()
    assert not p.devices
    async def run():
        p.credentials = object()
        p.register('a', 'synthetic-registration-token', 'duo')
        def fail(*_): raise OSError('synthetic failure')
        p.send = fail
        task = asyncio.create_task(p.worker())
        p.notify('a', 'duo', 'invite', 'round')
        await p.queue.join()
        assert p.status == 'failed' and not task.done()
        task.cancel()
        try: await task
        except asyncio.CancelledError: pass
    asyncio.run(run())
