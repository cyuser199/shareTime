import asyncio
import json

import pytest

from app import Relay, ActionError
from pairing import TTL
from fastapi.testclient import TestClient
from app import create_app
from test_app import identity, receive


class Socket:
    def __init__(self):
        self.events = []

    async def send_json(self, event):
        self.events.append(event)


def online(relay, uid):
    device = {'id': uid, 'kind': 'device', 'name': uid, 'publicKey': 'test-only'}
    socket = Socket()
    relay.connections[uid] = {'device': device, 'ws': socket}
    return device, socket


def test_online_pair_ack_cleans_metadata(tmp_path):
    async def scenario():
        relay = Relay(tmp_path)
        a, sa = online(relay, 'a')
        b, sb = online(relay, 'b')
        await relay.registry.reserve(a)
        await relay.registry.reserve(b)
        await relay.handle(b, {'type': 'join_fixed', 'code': relay.registry.code(a)})
        assert relay.pair_for('a') == relay.pair_for('b')
        assert any(e['type'] == 'paired' for e in sa.events)
        assert any(e['type'] == 'paired' for e in sb.events)
        await relay.handle(a, {'type': 'paired_ack'})
        await relay.handle(b, {'type': 'paired_ack'})
        assert json.loads((tmp_path / 'pairing.json').read_text()) == {'codes': {}, 'pending': {}, 'deliveries': {}}
    asyncio.run(scenario())


def test_offline_request_and_certificate_survive_restart(tmp_path):
    async def scenario():
        relay = Relay(tmp_path)
        a, sa = online(relay, 'a')
        b, sb = online(relay, 'b')
        await relay.registry.reserve(a)
        ticket_a = sa.events[-1]['ticket']
        await relay.registry.reserve(b)
        ticket_b = sb.events[-1]['ticket']
        relay.connections.pop('a')
        await relay.registry.join(b, relay.registry.code(a))
        assert sb.events[-1]['type'] == 'pair_waiting'
        assert not relay.pairs
        relay = Relay(tmp_path)
        a, sa = online(relay, 'a')
        await relay.registry.reconnect(a, ticket_a)
        assert relay.pair_for('a')
        relay.registry.ack(a)
        relay = Relay(tmp_path)
        b, sb = online(relay, 'b')
        await relay.registry.reconnect(b, ticket_b)
        assert sb.events[0]['type'] == 'paired'
        assert relay.pair_for('b')
        relay.registry.ack(b)
        assert not any(relay.registry.data.values())
    asyncio.run(scenario())


def test_expired_ticket_renews_on_reconnect_and_keeps_code(tmp_path, monkeypatch):
    now = [1000000.0]
    monkeypatch.setattr('pairing.time.time', lambda: now[0])
    async def scenario():
        relay = Relay(tmp_path)
        a, sa = online(relay, 'a')
        await relay.registry.reserve(a)
        original = sa.events[-1]
        now[0] += TTL + 1
        await relay.registry.reconnect(a, original['ticket'])
        assert relay.registry.data['codes'][original['code']]['expires'] == now[0] + TTL
        assert sa.events[-1]['code'] == original['code']
        await relay.handle(a, {'type': 'reserve_fixed'})
        assert sa.events[-1]['code'] == original['code']
        assert relay.registry.data['codes'][original['code']]['expires'] == now[0] + TTL
        assert Relay(tmp_path).registry.code(a) == original['code']
    asyncio.run(scenario())


def test_join_renews_expired_sender_and_online_owner(tmp_path, monkeypatch):
    now = [1000000.0]
    monkeypatch.setattr('pairing.time.time', lambda: now[0])
    async def scenario():
        relay = Relay(tmp_path)
        a, _ = online(relay, 'a')
        b, _ = online(relay, 'b')
        await relay.registry.reserve(a)
        await relay.registry.reserve(b)
        code_a, code_b = relay.registry.code(a), relay.registry.code(b)
        now[0] += TTL + 1
        await relay.registry.join(b, code_a)
        assert relay.pair_for('a') == relay.pair_for('b')
        assert relay.registry.code(a) == code_a and relay.registry.code(b) == code_b
    asyncio.run(scenario())


def test_join_renews_expired_sender_without_extending_offline_owner(tmp_path, monkeypatch):
    now = [1000000.0]
    monkeypatch.setattr('pairing.time.time', lambda: now[0])
    async def scenario():
        relay = Relay(tmp_path)
        a, _ = online(relay, 'a')
        b, _ = online(relay, 'b')
        await relay.registry.reserve(b)
        now[0] += TTL - 5
        await relay.registry.reserve(a)
        owner_expires = relay.registry.data['codes'][relay.registry.code(a)]['expires']
        relay.connections.pop('a')
        now[0] += 10
        await relay.registry.join(b, relay.registry.code(a))
        assert relay.registry.data['pending']['b']['expires'] == owner_expires
        now[0] = owner_expires + 1
        with pytest.raises(ActionError, match='请对方打开应用'):
            await relay.registry.join(b, relay.registry.code(a))
        assert not relay.registry.data['pending']
    asyncio.run(scenario())


def test_live_reservation_does_not_extend_on_reconnect(tmp_path, monkeypatch):
    now = [1000000.0]
    monkeypatch.setattr('pairing.time.time', lambda: now[0])
    async def scenario():
        relay = Relay(tmp_path)
        a, socket = online(relay, 'a')
        await relay.registry.reserve(a)
        original = socket.events[-1]
        now[0] += 60
        await relay.registry.reconnect(a, original['ticket'])
        assert socket.events[-1]['expiresAt'] == original['expiresAt']
    asyncio.run(scenario())


def test_cancel_and_self_pair_rejected(tmp_path):
    async def scenario():
        relay = Relay(tmp_path)
        a, sa = online(relay, 'a')
        b, sb = online(relay, 'b')
        await relay.registry.reserve(a)
        await relay.registry.reserve(b)
        with pytest.raises(ActionError):
            await relay.registry.join(a, relay.registry.code(a))
        relay.connections.pop('a')
        await relay.registry.join(b, relay.registry.code(a))
        await relay.handle(b, {'type': 'cancel_fixed'})
        assert not relay.registry.data['pending']
        online(relay, 'a')
        await relay.registry.reconnect(a, sa.events[0]['ticket'])
        assert not relay.pairs
    asyncio.run(scenario())


@pytest.mark.parametrize('expired', [('a',), ('b',), ('a', 'b')])
def test_websocket_fixed_pairing_recovers_expired_online_registrations(tmp_path, expired):
    app = create_app(tmp_path)
    with TestClient(app) as client:
        people = {name: identity(client, name) for name in ('a', 'b')}
        with client.websocket_connect('/connect') as a, client.websocket_connect('/connect') as b:
            codes = {}
            for name, socket in [('a', a), ('b', b)]:
                socket.send_json({'token': people[name]['token'], 'pairingProtocol': 2})
                codes[name] = receive(socket, 'fixed_code')['code']
                assert receive(socket, 'state')['paired'] is False
            for name in expired:
                app.state.relay.registry.data['codes'][codes[name]]['expires'] = 0
            b.send_json({'type': 'join_fixed', 'code': codes['a']})
            assert receive(a, 'paired')['certificate'] == receive(b, 'paired')['certificate']
            assert receive(a, 'state')['paired'] is True
            assert receive(b, 'state')['paired'] is True


def test_websocket_expired_reservation_ticket_reregisters_same_code(tmp_path):
    app = create_app(tmp_path)
    with TestClient(app) as client:
        device = identity(client)
        relay = app.state.relay
        owner = relay.verify(device['token'], 'device')
        code = relay.registry.code(owner)
        ticket = relay.sign({'kind': 'reservation', 'uid': device['id'], 'code': code, 'expires': 0})
        with client.websocket_connect('/connect') as socket:
            socket.send_json({'token': device['token'], 'pairingProtocol': 2, 'reservation': ticket})
            event = receive(socket, 'fixed_code')
            assert event['code'] == code and event['expiresAt'] > 0
            assert receive(socket, 'state')['paired'] is False
