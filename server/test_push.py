import asyncio
import json
import time
import pytest
from fastapi.testclient import TestClient
from app import create_app, Relay
from push import PushRegistry
from test_app import identity


@pytest.mark.parametrize('content', ['{invalid', '[]', '{"device":{"token":null,"expires":"bad"}}'])
def test_broken_push_registry_does_not_break_core_service_or_overwrite_file(tmp_path, content):
    file = tmp_path / 'push-devices.json'
    file.write_text(content)
    app = create_app(tmp_path)
    with TestClient(app) as client:
        assert client.get('/health').status_code == 200
        assert client.get('/push/config').json()['enabled'] is False
        device = identity(client)
        response = client.post('/push/device', headers={'Authorization': 'Bearer ' + device['token']},
            json={'provider': 'fcm', 'pushToken': 'synthetic-token' * 8, 'privacyVersion': 8})
        assert response.status_code == 503
    assert file.read_text() == content


@pytest.mark.parametrize('enabled', ['0', '1'])
def test_missing_fcm_credentials_only_disables_push(tmp_path, monkeypatch, enabled):
    monkeypatch.setenv('SHARETIME_FCM_CREDENTIALS', str(tmp_path / 'missing.json'))
    monkeypatch.setenv('SHARETIME_FCM_ENABLED', enabled)
    with TestClient(create_app(tmp_path)) as client:
        assert client.get('/health').status_code == 200
        assert client.get('/push/config').json()['enabled'] is False


def test_oauth_refresh_has_a_network_timeout(tmp_path, monkeypatch):
    import google.auth.transport.requests
    import httpx
    timeouts = []
    class Credentials:
        valid = False
        token = 'synthetic-access-token'
        def refresh(self, request): request(url='https://oauth2.googleapis.com/token')
    class Request:
        def __call__(self, **kwargs): timeouts.append(kwargs['timeout'])
    class Client:
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def post(self, *args, **kwargs): return httpx.Response(200)
    monkeypatch.setattr(google.auth.transport.requests, 'Request', Request)
    monkeypatch.setattr(httpx, 'Client', Client)
    p = PushRegistry(tmp_path)
    p.credentials = Credentials(); p.project = 'synthetic-project'
    assert p.send('synthetic-token', ('uid', 'invite', 'round', 0, 'duo')) == 'sent'
    assert timeouts == [10]


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
