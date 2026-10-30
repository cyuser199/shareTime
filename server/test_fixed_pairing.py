import asyncio
import json

import pytest

from app import Relay, ActionError
from pairing import TTL


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


def test_expiry_requires_explicit_renew_and_keeps_code(tmp_path, monkeypatch):
    now = [1000000.0]
    monkeypatch.setattr('pairing.time.time', lambda: now[0])
    async def scenario():
        relay = Relay(tmp_path)
        a, sa = online(relay, 'a')
        await relay.registry.reserve(a)
        original = sa.events[-1]
        now[0] += TTL + 1
        await relay.registry.reconnect(a, original['ticket'])
        assert not relay.registry.data['codes']
        assert sa.events[-1]['code'] == original['code']
        await relay.handle(a, {'type': 'reserve_fixed'})
        assert sa.events[-1]['code'] == original['code']
        assert relay.registry.data['codes'][original['code']]['expires'] == now[0] + TTL
        assert Relay(tmp_path).registry.code(a) == original['code']
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
