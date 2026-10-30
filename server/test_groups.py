import asyncio
import base64
import json

import pytest
from app import Relay, ActionError
from test_fixed_pairing import online


def test_five_member_limit_and_restart(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        people=[online(relay,str(i))[0] for i in range(6)]
        groups=relay.friends
        await groups.handle(people[0],{'type':'group_create'})
        code=groups.group('0')['code']
        for p in people[1:5]: await groups.handle(p,{'type':'group_join','code':code})
        with pytest.raises(ActionError,match='最多 5 人'):
            await groups.handle(people[5],{'type':'group_join','code':code})
        restored=Relay(tmp_path)
        assert len(restored.friends.group('0')['members'])==5
        assert not restored.friends.rounds
        old_code=code
        await groups.handle(people[0],{'type':'group_code'})
        assert groups.group('0')['code']!=old_code
        await groups.handle(people[4],{'type':'group_leave'})
        assert groups.group('4') is None
        await groups.handle(people[5],{'type':'group_join','code':groups.group('0')['code']})
        assert len(groups.group('5')['members'])==5
    asyncio.run(scenario())


def test_partial_participation_private_delivery_and_cleanup(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        people=[online(relay,str(i))[0] for i in range(5)]
        g=relay.friends
        for p in people: g.connections[p['id']]=relay.connections[p['id']]['ws']
        await g.handle(people[0],{'type':'group_create'})
        group=g.group('0')
        for p in people[1:]: await g.handle(p,{'type':'group_join','code':group['code']})
        await g.handle(people[0],{'type':'invite'})
        r=g.rounds[group['id']]; rid=r['id']
        assert r['phase']=='active' and r['participants']==['0']
        with pytest.raises(ActionError, match='至少一位'):
            await g.handle(people[0],{'type':'submit','id':rid,'bodies':{}})
        await g.handle(people[1],{'type':'respond','id':rid,'accept':True})
        await g.handle(people[2],{'type':'respond','id':rid,'accept':False})
        await g.handle(people[0],{'type':'group_start','id':rid})
        assert r['participants']==['0','1']
        assert r['phase']=='active' and not r['locked']
        invalid=base64.b64encode(b'x'*40).decode()
        with pytest.raises(ActionError,match='收件人'):
            await g.handle(people[0],{'type':'submit','id':rid,'bodies':{'1':invalid,'2':invalid}})
        with pytest.raises(ActionError):
            await g.handle(people[2],{'type':'submit','id':rid,'bodies':{'0':base64.b64encode(b'x'*40).decode()}})
        for a,b in [('0','1'),('1','0')]:
            body=base64.b64encode((a*40).encode()).decode()
            await g.handle(people[int(a)],{'type':'submit','id':rid,'bodies':{b:body}})
        assert r['phase']=='complete' and r['locked']
        with pytest.raises(ActionError):
            await g.handle(people[3],{'type':'respond','id':rid,'accept':True})
        await g.handle(people[0],{'type':'group_retry','id':rid})
        for uid in ['0','1']:
            event=next(e for e in reversed(g.connections[uid].events) if e['type']=='group_records')
            assert len(event['records'])==1 and event['records'][0]['sender']!=uid
        assert not any(e['type']=='group_records' for e in g.connections['2'].events)
        persisted=json.loads(g.file.read_text(encoding='utf-8'))
        assert set(persisted[group['id']])=={'id','members','code','codeUntil'}
        for p in people[:2]: await g.handle(p,{'type':'ack','id':rid})
        assert not g.rounds and not r['bodies']
    asyncio.run(scenario())


def test_group_does_not_replace_duo_pair(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        a,_=online(relay,'a'); b,_=online(relay,'b')
        relay.pairs['duo']={'id':'duo','members':{'a':a,'b':b}}
        await relay.friends.handle(a,{'type':'group_create'})
        assert relay.pair_for('a')['id']=='duo'
        await relay.friends.handle(a,{'type':'group_leave'})
        assert relay.pair_for('a')['id']=='duo'
        assert not relay.friends.groups
    asyncio.run(scenario())


def test_expired_code_and_late_member_cannot_read_round(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        a,_=online(relay,'a'); b,_=online(relay,'b'); c,_=online(relay,'c')
        g=relay.friends
        for p in [a,b,c]:g.connections[p['id']]=relay.connections[p['id']]['ws']
        await g.handle(a,{'type':'group_create'})
        group=g.group('a'); group['codeUntil']=0
        with pytest.raises(ActionError):await g.handle(b,{'type':'group_join','code':group['code']})
        await g.handle(a,{'type':'group_code'})
        await g.handle(b,{'type':'group_join','code':group['code']})
        await g.handle(a,{'type':'invite'})
        await g.handle(c,{'type':'group_join','code':group['code']})
        assert not any(e['type']=='group_round' for e in g.connections['c'].events)
        r=g.rounds[group['id']]
        with pytest.raises(ActionError):await g.handle(c,{'type':'respond','id':r['id'],'accept':True})
        r['expires']=0
        await g.sweep()
        assert not g.rounds
    asyncio.run(scenario())


def test_websocket_group_route(tmp_path):
    from fastapi.testclient import TestClient
    from app import create_app
    from test_app import identity, receive
    with TestClient(create_app(tmp_path)) as c:
        a,b=identity(c,'one'),identity(c,'two')
        with c.websocket_connect('/connect') as wa, c.websocket_connect('/connect') as wb:
            wa.send_json({'token':a['token'],'mode':'friends'})
            wb.send_json({'token':b['token'],'mode':'friends'})
            assert receive(wa,'group_state')['group'] is None
            receive(wb,'group_state')
            wa.send_json({'type':'group_create'})
            code=receive(wa,'group_state')['group']['code']
            wb.send_json({'type':'group_join','code':code})
            assert len(receive(wb,'group_state')['group']['members'])==2
            wa.send_json({'type':'invite'})
            rid=receive(wa,'group_round')['id']
            assert receive(wb,'group_round')['id']==rid
            wb.send_json({'type':'respond','id':rid,'accept':True})
            assert len(receive(wb,'group_round')['accepted'])==2
            wa.send_json({'type':'group_start','id':rid})
            while receive(wa,'group_round')['phase']!='active': pass
            assert receive(wb,'group_round')['phase']=='active'


def test_nonparticipant_leaving_does_not_cancel_active_round(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        people=[online(relay,str(i))[0] for i in range(3)]
        g=relay.friends
        for p in people:g.connections[p['id']]=relay.connections[p['id']]['ws']
        await g.handle(people[0],{'type':'group_create'})
        group=g.group('0')
        for p in people[1:]:await g.handle(p,{'type':'group_join','code':group['code']})
        await g.handle(people[0],{'type':'invite'})
        r=g.rounds[group['id']]
        await g.handle(people[1],{'type':'respond','id':r['id'],'accept':True})
        await g.handle(people[0],{'type':'group_start','id':r['id']})
        await g.handle(people[2],{'type':'group_leave'})
        assert r['phase']=='active' and group['id'] in g.rounds
    asyncio.run(scenario())


def test_immediate_capture_offline_join_and_roster_race(tmp_path):
    async def scenario():
        relay=Relay(tmp_path)
        people=[online(relay,str(i))[0] for i in range(3)]
        g=relay.friends
        await g.handle(people[0],{'type':'group_create'})
        group=g.group('0')
        for p in people[1:]: await g.handle(p,{'type':'group_join','code':group['code']})
        g.connections['0']=relay.connections['0']['ws']
        await g.handle(people[0],{'type':'invite'})
        r=g.rounds[group['id']]; rid=r['id']
        assert r['phase']=='active' and r['accepted']==['0']
        for p in people[1:]:
            g.connections[p['id']]=relay.connections[p['id']]['ws']
            await g.state(p['id'])
            assert g.connections[p['id']].events[-1]['type']=='group_round'
            await g.handle(p,{'type':'respond','id':rid,'accept':True})
        body=base64.b64encode(b'x'*40).decode()
        with pytest.raises(ActionError,match='收件人'):
            await g.handle(people[0],{'type':'submit','id':rid,'bodies':{'1':body}})
        assert not r['locked'] and not r['bodies']
        await g.handle(people[0],{'type':'submit','id':rid,'bodies':{'1':body,'2':body}})
        assert r['locked'] and r['phase']=='active'
        assert not any(e['type']=='group_records' for e in g.connections['1'].events)
    asyncio.run(scenario())
