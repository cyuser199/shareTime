import asyncio
import base64
import pytest
from app import Relay, ActionError
from test_fixed_pairing import online

BODY=base64.b64encode(b'encrypted-synthetic-payload'*4).decode()

async def setup(relay, count=3):
    people=[online(relay,str(i))[0] for i in range(count)]
    g=relay.friends
    for p in people:
        g.connections[p['id']]=relay.connections[p['id']]['ws']
        g.protocols[p['id']]=2
    await g.handle(people[0],{'type':'group_create'})
    group=g.group('0')
    for p in people[1:]:await g.handle(p,{'type':'group_join','code':group['code']})
    await g.handle(people[0],{'type':'invite','async':True})
    return people,g,group,g.rounds[group['id']]

def test_async_late_join_after_sender_disconnect_and_progressive_delivery(tmp_path):
    async def run():
        relay=Relay(tmp_path); people,g,group,r=await setup(relay)
        rid=r['id'];assert r['async'] and r['expires']>0
        await g.handle(people[0],{'type':'submit','id':rid,'bodies':{'1':BODY,'2':BODY}})
        assert not any(e['type']=='group_records' for e in g.connections['1'].events)
        sender_socket=g.connections.pop('0')
        await g.handle(people[1],{'type':'respond','id':rid,'accept':True})
        await g.handle(people[1],{'type':'submit','id':rid,'bodies':{'0':BODY,'2':BODY}})
        event=next(e for e in reversed(g.connections['1'].events) if e['type']=='group_records')
        assert event['revision']==2 and event['records']==[{'sender':'0','body':BODY}]
        assert r['phase']=='active'
        await g.handle(people[1],{'type':'ack','id':rid,'revision':2})
        g.connections['0']=sender_socket
        await g.state('0')
        assert sender_socket.events[-1]['type']=='group_records'
        await g.handle(people[0],{'type':'ack','id':rid,'revision':2})
        assert group['id'] in g.rounds
        await g.handle(people[2],{'type':'respond','id':rid,'accept':True})
        await g.handle(people[2],{'type':'submit','id':rid,'bodies':{'0':BODY,'1':BODY}})
        assert r['phase']=='complete'
        for uid in ['0','1','2']:
            event=next(e for e in reversed(g.connections[uid].events) if e['type']=='group_records')
            assert event['revision']==3 and len(event['records'])==2
        counts=relay.metrics.summary([])
        assert counts['today_records']==1 and [u['records'] for u in counts['users']]==[1,1,1]
        for p in people:await g.handle(p,{'type':'ack','id':rid,'revision':3})
        assert not g.rounds and not r['bodies']
    asyncio.run(run())

def test_async_declined_and_unsubmitted_cannot_read_and_legacy_update_required(tmp_path):
    async def run():
        people,g,group,r=await setup(Relay(tmp_path))
        rid=r['id']
        with pytest.raises(ActionError):await g.handle(people[0],{'type':'submit','id':rid,'bodies':{'1':BODY}})
        await g.handle(people[0],{'type':'submit','id':rid,'bodies':{'1':BODY,'2':BODY}})
        g.protocols['2']=1
        with pytest.raises(ActionError,match='更新'):await g.handle(people[2],{'type':'respond','id':rid,'accept':True})
        g.protocols['2']=2
        await g.handle(people[2],{'type':'respond','id':rid,'accept':False})
        await g.handle(people[1],{'type':'respond','id':rid,'accept':True})
        await g.handle(people[1],{'type':'submit','id':rid,'bodies':{'0':BODY,'2':BODY}})
        assert not any(e['type']=='group_records' for e in g.connections['2'].events)
        with pytest.raises(ActionError):await g.handle(people[2],{'type':'group_retry','id':rid})
        assert r['phase']=='complete'
    asyncio.run(run())

def test_async_round_expiry_clears_opaque_payloads(tmp_path):
    async def run():
        people,g,group,r=await setup(Relay(tmp_path))
        await g.handle(people[0],{'type':'submit','id':r['id'],'bodies':{'1':BODY,'2':BODY}})
        r['expires']=0
        await g.sweep()
        assert not g.rounds and not r['bodies']
    asyncio.run(run())

def test_duo_can_submit_before_offline_partner_accepts(tmp_path):
    async def run():
        relay=Relay(tmp_path);a,sa=online(relay,'a');b,sb=online(relay,'b')
        relay.pairs['pair']={'id':'pair','members':{'a':a,'b':b}}
        relay.connections.pop('b')
        await relay.handle(a,{'type':'invite','async':True})
        r=relay.round_for('pair');rid=r['id']
        await relay.handle(a,{'type':'submit','id':rid,'body':BODY})
        assert r['phase']=='waiting' and 'a' in r['bodies']
        relay.connections['b']={'device':b,'ws':sb}
        await relay.state('b')
        assert sb.events[-1]['type']=='round'
        await relay.handle(b,{'type':'respond','id':rid,'accept':True})
        await relay.handle(b,{'type':'submit','id':rid,'body':BODY})
        assert r['phase']=='complete'
        assert any(e['type']=='record' for e in sa.events) and any(e['type']=='record' for e in sb.events)
    asyncio.run(run())


def test_websocket_async_reconnect_and_late_recipient(tmp_path):
    from fastapi.testclient import TestClient
    from app import create_app
    from test_app import identity,receive
    with TestClient(create_app(tmp_path)) as client:
        a,b,c=[identity(client,n) for n in ['A','B','C']]
        with client.websocket_connect('/connect') as wa:
            wa.send_json({'token':a['token'],'mode':'friends','recordProtocol':2})
            receive(wa,'group_state')
            wa.send_json({'type':'group_create'})
            code=receive(wa,'group_state')['group']['code']
            for person in [b,c]:
                with client.websocket_connect('/connect') as ws:
                    ws.send_json({'token':person['token'],'mode':'friends','recordProtocol':2})
                    receive(ws,'group_state')
                    ws.send_json({'type':'group_join','code':code})
                    receive(ws,'group_state')
            wa.send_json({'type':'invite','async':True})
            r=receive(wa,'group_round');rid=r['id']
            assert r['async'] and r['remaining']>86000
            wa.send_json({'type':'submit','id':rid,'bodies':{b['id']:BODY,c['id']:BODY}})
            assert a['id'] in receive(wa,'group_round')['submitted']
        with client.websocket_connect('/connect') as wb:
            wb.send_json({'token':b['token'],'mode':'friends','recordProtocol':2})
            assert receive(wb,'group_state')['activeRound']==rid
            receive(wb,'group_round')
            wb.send_json({'type':'respond','id':rid,'accept':True});receive(wb,'group_round')
            wb.send_json({'type':'submit','id':rid,'bodies':{a['id']:BODY,c['id']:BODY}})
            event=receive(wb,'group_records')
            assert event['records']==[{'sender':a['id'],'body':BODY}] and event['revision']==2
            wb.send_json({'type':'ack','id':rid,'revision':2})
            wb.send_json({'type':'ping'});receive(wb,'pong')
            with client.websocket_connect('/connect') as wc:
                wc.send_json({'token':c['token'],'mode':'friends','recordProtocol':2})
                receive(wc,'group_state');receive(wc,'group_round')
                wc.send_json({'type':'respond','id':rid,'accept':True});receive(wc,'group_round')
                wc.send_json({'type':'submit','id':rid,'bodies':{a['id']:BODY,b['id']:BODY}})
                assert receive(wc,'group_records')['revision']==3
                assert receive(wb,'group_records')['revision']==3


def test_shared_memory_budget_rejects_group_upload_when_duo_budget_full(tmp_path):
    class Huge(str):
        def __len__(self):return 128*1024*1024
    async def run():
        relay=Relay(tmp_path);people,g,group,r=await setup(relay)
        relay.rounds['occupied']={'bodies':{'other':Huge(BODY)}}
        with pytest.raises(ActionError,match='中转空间'):
            await g.handle(people[0],{'type':'submit','id':r['id'],'bodies':{'1':BODY,'2':BODY}})
        assert not r['bodies']
    asyncio.run(run())
