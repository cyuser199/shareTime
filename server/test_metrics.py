import asyncio
import hashlib
import json
from datetime import datetime, timedelta

from fastapi.testclient import TestClient

from app import create_app, Relay
from metrics import CHINA, DailyMetrics
from test_app import identity, receive, auth, pair, invite
from test_fixed_pairing import online


def test_daily_unique_timezone_restart_and_midnight(tmp_path):
    now = [datetime(2026, 10, 30, 23, 59, tzinfo=CHINA)]
    metrics = DailyMetrics(tmp_path, b'test-key', lambda: now[0])
    metrics.refresh(['same-device', 'same-device', 'other-device'])
    metrics.completed(['same-device', 'other-device'])
    first_hashes = list(metrics.data['devices'])
    restored = DailyMetrics(tmp_path, b'test-key', lambda: now[0])
    assert restored.summary(['same-device'])['today_users'] == 2
    assert restored.summary([])['today_records'] == 1
    assert 'same-device' not in metrics.file.read_text()
    now[0] += timedelta(minutes=2)
    summary = restored.summary(['same-device'])  # An already-connected user counts after midnight.
    assert summary['date'] == '2026-10-31'
    assert summary['today_users'] == 1 and summary['today_records'] == 0
    assert not set(first_hashes) & set(restored.data['devices'])


def test_dashboard_requires_login_for_user_details(tmp_path):
    (tmp_path / 'admin-auth.json').write_text(json.dumps({
        'username': 'cy', 'password_sha256': hashlib.sha256(b'test-password').hexdigest()}))
    app = create_app(tmp_path)
    with TestClient(app) as client:
        assert client.get('/admin').status_code == 200
        assert client.get('/admin/stats').status_code == 401
        assert client.get('/admin', auth=('cy', 'wrong')).status_code == 200
        page = client.get('/admin', auth=('cy', 'test-password'))
        assert page.status_code == 200 and page.headers['cache-control'] == 'no-store'
        assert '今日完成记录' in page.text
        result = client.get('/admin/stats', auth=('cy', 'test-password'))
        assert set(result.json()) == {'date', 'timezone', 'today_users', 'today_records', 'online_users', 'started_at', 'registered_devices', 'users', 'details_started_at', 'details_partial'}
        assert result.json()['today_records'] == result.json()['today_users'] == 0
        device = identity(client)
        with client.websocket_connect('/connect') as ws:
            auth(ws, device)
            details = client.get('/admin/stats', auth=('cy', 'test-password')).json()
            assert details['today_users'] == 1
            assert details['users'][0]['name'] == 'friend'
            assert details['users'][0]['records'] == 0
            assert details['users'][0]['online']
            assert device['id'] not in json.dumps(details)
        with client.websocket_connect('/connect') as ws:
            auth(ws, device)
            assert client.get('/admin/stats', auth=('cy', 'test-password')).json()['today_users'] == 1


def test_duo_completion_counts_once_and_ack_does_not_add(tmp_path):
    import base64
    app = create_app(tmp_path)
    with TestClient(app) as client:
        da, db = identity(client, 'A'), identity(client, 'B')
        with client.websocket_connect('/connect') as a, client.websocket_connect('/connect') as b:
            auth(a, da); auth(b, db); pair(a, b)
            rid = invite(a, b)
            b.send_json({'type': 'respond', 'id': rid, 'accept': True})
            receive(a, 'round'); receive(b, 'round')
            body = base64.b64encode(b'synthetic-ciphertext' * 4).decode()
            a.send_json({'type': 'submit', 'id': rid, 'body': body})
            receive(a, 'round')
            assert app.state.relay.metrics.summary([])['today_records'] == 0
            b.send_json({'type': 'submit', 'id': rid, 'body': body})
            receive(a, 'record'); receive(b, 'record')
            assert app.state.relay.metrics.summary([])['today_records'] == 1
            assert {u['name']: u['records'] for u in app.state.relay.metrics.summary([])['users']} == {'A': 1, 'B': 1}
            b.send_json({'type': 'submit', 'id': rid, 'body': body})
            receive(b, 'error')
            for ws in (a, b): ws.send_json({'type': 'ack', 'id': rid})
            b.send_json({'type': 'ping'}); receive(b, 'pong')
            assert app.state.relay.metrics.summary([])['today_records'] == 1


def test_group_completion_counts_one_round_for_multiple_people(tmp_path):
    import base64
    async def scenario():
        relay = Relay(tmp_path)
        people = [online(relay, str(i))[0] for i in range(3)]
        group_api = relay.friends
        for p in people: group_api.connections[p['id']] = relay.connections[p['id']]['ws']
        await group_api.handle(people[0], {'type': 'group_create'})
        group = group_api.group('0')
        for p in people[1:]: await group_api.handle(p, {'type': 'group_join', 'code': group['code']})
        await group_api.handle(people[0], {'type': 'invite'})
        rid = group_api.rounds[group['id']]['id']
        for p in people[1:]: await group_api.handle(p, {'type': 'respond', 'id': rid, 'accept': True})
        body = base64.b64encode(b'synthetic' * 10).decode()
        for i, person in enumerate(people):
            await group_api.handle(person, {'type': 'submit', 'id': rid, 'bodies': {p['id']: body for p in people if p != person}})
            assert relay.metrics.summary([])['today_records'] == (1 if i == 2 else 0)
        for p in people: await group_api.handle(p, {'type': 'group_retry', 'id': rid})
        assert relay.metrics.summary([])['today_records'] == 1
        assert DailyMetrics(tmp_path, relay.key).summary([])['today_records'] == 1
        assert {u['name']: u['records'] for u in relay.metrics.summary([])['users']} == {'0': 1, '1': 1, '2': 1}
    asyncio.run(scenario())


def test_user_details_migrate_preserve_totals_and_clear_at_midnight(tmp_path):
    now = [datetime(2026, 10, 30, 23, 59, tzinfo=CHINA)]
    old = DailyMetrics(tmp_path, b'key', lambda: now[0])
    old.refresh(['legacy-device'])
    tag = old.data['devices'][0]
    # Model the aggregate-only file written before this feature.
    old.file.write_text(json.dumps({'date': '2026-10-30', 'devices': [tag],
                                   'completed_rounds': 4, 'started_at': now[0].isoformat()}))
    current = DailyMetrics(tmp_path, b'key', lambda: now[0])
    result = current.summary([], {'legacy-device': {'name': '老朋友'}, 'not-active': {'name': '不应计入'}})
    assert result['today_users'] == 1 and result['today_records'] == 4
    assert result['users'][0]['name'] == '老朋友'
    assert result['details_partial']
    current.completed({'legacy-device': {'name': '老朋友'}, 'new-device': {'name': '老朋友'}})
    restored = DailyMetrics(tmp_path, b'key', lambda: now[0])
    result = restored.summary([])
    assert result['today_records'] == 5 and result['today_users'] == 2
    assert len({u['device_tag'] for u in result['users']}) == 2
    assert [u['records'] for u in result['users']] == [1, 1]
    now[0] += timedelta(minutes=2)
    result = restored.summary([])
    assert result['users'] == [] and result['today_users'] == result['today_records'] == 0
    assert not result['details_partial']


def test_model_migration_restart_no_downgrade_and_midnight(tmp_path):
    now = [datetime(2026, 10, 30, 23, 59, tzinfo=CHINA)]
    metrics = DailyMetrics(tmp_path, b'key', lambda: now[0])
    metrics.refresh({'device': {'name': '小明'}})
    assert metrics.summary([])['users'][0]['device_model'] == '未上报'
    metrics.refresh({'device': {'name': '小明', 'deviceModel': 'Xiaomi 23127PN0CC'}})
    metrics.completed({'device': {'name': '小明'}})
    metrics = DailyMetrics(tmp_path, b'key', lambda: now[0])
    user = metrics.summary([])['users'][0]
    assert user['device_model'] == 'Xiaomi 23127PN0CC' and user['records'] == 1
    assert '"device"' not in metrics.file.read_text()
    now[0] += timedelta(minutes=2)
    assert metrics.summary([])['users'] == []
    assert 'Xiaomi' not in metrics.file.read_text()


def test_both_modes_model_display_and_unpaired_profiles_after_midnight(tmp_path):
    credentials = ('cy', 'test-password')
    (tmp_path / 'admin-auth.json').write_text(json.dumps({
        'username': credentials[0], 'password_sha256': hashlib.sha256(credentials[1].encode()).hexdigest()}))
    app = create_app(tmp_path)
    now = [datetime(2026, 10, 30, 23, 59, tzinfo=CHINA)]
    app.state.relay.metrics.clock = lambda: now[0]
    with TestClient(app) as client:
        a, b = identity(client, '名字 <script>'), identity(client, '另一位')
        with client.websocket_connect('/connect') as wa, client.websocket_connect('/connect') as wb:
            wa.send_json({'token': a['token'], 'deviceModel': 'Lenovo L79031'})
            receive(wa, 'state')
            wb.send_json({'token': b['token'], 'mode': 'friends', 'deviceModel': 'Xiaomi 23127PN0CC'})
            receive(wb, 'group_state')
            for _ in range(2):
                rows = client.get('/admin/stats', auth=credentials).json()['users']
                assert {(r['name'], r['device_model']) for r in rows} == {
                    ('名字 <script>', 'Lenovo L79031'), ('另一位', 'Xiaomi 23127PN0CC')}
                assert all(r['online'] for r in rows)
                assert a['id'] not in json.dumps(rows) and b['id'] not in json.dumps(rows)
                now[0] += timedelta(minutes=2)
        assert not app.state.relay.friends.profiles
        assert client.get('/admin/stats').status_code == 401


def test_model_does_not_change_pairing_identity(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as client:
        a, b = identity(client, 'A'), identity(client, 'B')
        with client.websocket_connect('/connect') as wa, client.websocket_connect('/connect') as wb:
            wa.send_json({'token': a['token'], 'deviceModel': 'New Phone'})
            receive(wa, 'state')
            auth(wb, b)
            pair(wa, wb)
            device = app.state.relay.connections[a['id']]['device']
            assert 'deviceModel' not in device
            assert any(p['members'][a['id']] == device for p in app.state.relay.pairs.values())


def test_guest_form_login_cookie_logout_and_password_rotation(tmp_path):
    auth_file = tmp_path / 'admin-auth.json'
    config = {'username': 'cy', 'password_sha256': hashlib.sha256(b'initial-password').hexdigest()}
    auth_file.write_text(json.dumps(config))
    app = create_app(tmp_path)
    with TestClient(app, base_url='https://testserver') as c:
        page = c.get('/admin')
        assert page.status_code == 200 and '访客查看' in page.text
        assert 'www-authenticate' not in page.headers
        person = identity(c, 'private-nickname')
        with c.websocket_connect('/connect') as ws:
            ws.send_json({'token': person['token'], 'deviceModel': 'PrivateModel'})
            receive(ws, 'state')
            guest = c.get('/admin/public-stats')
            assert guest.status_code == 200
            assert set(guest.json()) == {'date', 'timezone', 'today_users', 'today_records', 'online_users', 'started_at', 'registered_devices'}
            assert 'private-nickname' not in guest.text and 'PrivateModel' not in guest.text
            assert c.get('/admin/stats').status_code == 401
            assert c.get('/admin/session').json() == {'admin': False}
            assert c.post('/admin/login', json={'username':'cy','password':'wrong'}).status_code == 401
            login = c.post('/admin/login', json={'username':'cy','password':'initial-password'})
            assert login.status_code == 200
            cookie = login.headers['set-cookie'].lower()
            assert 'secure' in cookie and 'httponly' in cookie and 'samesite=strict' in cookie
            assert c.get('/admin/session').json() == {'admin': True}
            assert c.get('/admin/stats').json()['users'][0]['name'] == 'private-nickname'
            token = c.cookies.get('sharetime_admin')
            assert c.post('/admin/logout').status_code == 200
            assert c.get('/admin/stats').status_code == 401
            c.cookies.set('sharetime_admin', token)
            config['password_sha256'] = hashlib.sha256(b'changed-password').hexdigest()
            auth_file.write_text(json.dumps(config))
            assert c.get('/admin/stats').status_code == 401
            assert c.post('/admin/login', json={'username':'cy','password':'initial-password'}).status_code == 401
            assert c.post('/admin/login', json={'username':'cy','password':'changed-password'},headers={'Origin':'https://evil.example'}).status_code == 403
            assert c.post('/admin/login', json={'username':'cy','password':'changed-password'}).status_code == 200


def test_login_attempt_rate_limit(tmp_path):
    (tmp_path/'admin-auth.json').write_text(json.dumps({'username':'cy','password_sha256':hashlib.sha256(b'pw').hexdigest()}))
    with TestClient(create_app(tmp_path)) as c:
        for _ in range(8):
            assert c.post('/admin/login',json={'username':'cy','password':'wrong'}).status_code == 401
        assert c.post('/admin/login',json={'username':'cy','password':'wrong'}).status_code == 429


def test_history_archives_only_aggregates_and_survives_restart(tmp_path):
    now = [datetime(2026, 10, 31, 23, 59, tzinfo=CHINA)]
    metrics = DailyMetrics(tmp_path, b'key', lambda: now[0])
    metrics.completed({'private-id': {'name': 'private-name', 'deviceModel': 'private-model'}})
    now[0] += timedelta(minutes=2)
    assert metrics.summary([])['today_records'] == 0
    stored = metrics.history_file.read_text()
    assert all(value not in stored for value in ['private-id', 'private-name', 'private-model', 'device_tag', metrics.tag('private-id')])
    assert metrics.history['2026-10-31'] == {'users': 1, 'records': 1, 'partial': False, 'details': [], 'details_partial': True}
    restored = DailyMetrics(tmp_path, b'key', lambda: now[0])
    old = restored.for_date('2026-10-31', [])
    assert old['historical'] and old['today_users'] == old['today_records'] == 1
    assert old['users'] == []
    assert restored.dates() == ['2026-11-01', '2026-10-31']


def test_history_api_dates_validation_and_guest_privacy(tmp_path):
    auth_file = tmp_path/'admin-auth.json'
    auth_file.write_text(json.dumps({'username':'cy', 'password_sha256':hashlib.sha256(b'pw').hexdigest()}))
    app = create_app(tmp_path)
    metrics = app.state.relay.metrics
    now = [datetime(2026, 10, 31, 23, 59, tzinfo=CHINA)]
    metrics.clock = lambda: now[0]
    metrics.completed({'private-id': {'name':'private-name','deviceModel':'private-model','privacyVersion':6}})
    now[0] += timedelta(minutes=2)
    with TestClient(app) as c:
        assert c.get('/admin/dates').json()['dates'] == ['2026-11-01','2026-10-31']
        response = c.get('/admin/public-stats?date=2026-10-31')
        assert response.status_code == 200 and response.json()['today_records'] == 1
        assert response.json()['historical']
        assert 'users' not in response.json()
        assert 'private-' not in response.text
        assert c.get('/admin/stats?date=2026-10-31').status_code == 401
        admin = c.get('/admin/stats?date=2026-10-31',auth=('cy','pw')).json()
        assert admin['users'][0]['name'] == 'private-name' and admin['historical']
        assert c.get('/admin/public-stats?date=../../etc/passwd').status_code == 400
        assert c.get('/admin/public-stats?date=2026-13-01').status_code == 400
        assert c.get('/admin/public-stats?date=2026-10-29').status_code == 404
        assert c.get('/admin/public-stats?date=2026-11-01').json()['today_records'] == 0


def test_history_details_require_new_privacy_consent(tmp_path):
    now=[datetime(2026,10,31,23,59,tzinfo=CHINA)]
    metrics=DailyMetrics(tmp_path,b'key',lambda:now[0])
    metrics.completed({'old':{'name':'old-private','deviceModel':'old-model','privacyVersion':5},
                       'new':{'name':'new-private','deviceModel':'new-model','privacyVersion':6}})
    now[0]+=timedelta(minutes=2)
    historical=metrics.for_date('2026-10-31',[])
    assert historical['today_users']==2 and historical['today_records']==1
    assert len(historical['users'])==1 and historical['users'][0]['name']=='new-private'
    assert historical['users'][0]['device_model']=='new-model'
    assert historical['users'][0]['records']==1 and not historical['users'][0]['online']
    assert historical['history_details_partial']
    text=metrics.history_file.read_text()
    assert 'old-private' not in text and 'old-model' not in text
    assert '"new"' not in text and '"old"' not in text
    restored=DailyMetrics(tmp_path,b'key',lambda:now[0])
    assert restored.for_date('2026-10-31',[])['users']==historical['users']


def test_connected_user_history_consent_survives_midnight_without_reconnect(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        now=[datetime(2026,10,30,23,59,tzinfo=CHINA)]
        relay.metrics.clock=lambda:now[0]
        device,_=online(relay,'connected')
        relay.connections['connected']['profile']={**device,'name':'always-online','privacyVersion':6}
        await relay.sweep()
        now[0]+=timedelta(minutes=2)
        await relay.sweep()
        now[0]+=timedelta(days=1)
        await relay.sweep()
        day=relay.metrics.for_date('2026-10-31',[])
        assert day['users'][0]['name']=='always-online'
        assert day['users'][0]['records']==0
    asyncio.run(scenario())
