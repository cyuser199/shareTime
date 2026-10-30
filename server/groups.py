"""Independent friend groups. Durable membership only; encrypted records stay in RAM."""
import asyncio
import base64
import json
import secrets
import time
from contextlib import suppress


class FriendGroups:
    def __init__(self, relay):
        self.relay = relay
        self.file = relay.directory / 'groups.json'
        self.groups = json.loads(self.file.read_text(encoding='utf-8')) if self.file.exists() else {}
        self.connections = {}
        self.rounds = {}

    def save(self):
        tmp = self.file.with_suffix('.tmp')
        tmp.write_text(json.dumps(self.groups, ensure_ascii=False), encoding='utf-8')
        tmp.chmod(0o600)
        tmp.replace(self.file)

    def group(self, uid):
        return next((g for g in self.groups.values() if uid in g['members']), None)

    async def send(self, uid, event):
        ws = self.connections.get(uid)
        if ws:
            with suppress(RuntimeError, OSError, asyncio.TimeoutError):
                await asyncio.wait_for(ws.send_json(event), 5)

    async def state(self, uid):
        group = self.group(uid)
        await self.send(uid, {'type': 'group_state', 'group': group, 'online': [m for m in group['members'] if m in self.connections] if group else []})
        if group and group['id'] in self.rounds:
            record = self.rounds[group['id']]
            if record['expires'] <= time.monotonic():
                await self.end(group, '本轮已超时')
                return
            # A member joining after invitation cannot see or participate in it.
            if uid not in record['eligible']:
                return
            await self.send(uid, {'type': 'group_round', **{k:record[k] for k in ['id','phase','initiator','accepted','participants','locked']},
                                  'eligible': record['eligible'], 'submitted': list(record['bodies']),
                                  'remaining': max(0, int(record['expires']-time.monotonic()))})
            if record['phase'] == 'complete' and uid in record['participants']:
                await self.send(uid, {'type': 'group_records', 'id': record['id'],
                                     'records': [{'sender': m, 'body': record['bodies'][m][uid]} for m in record['participants'] if m != uid]})

    async def broadcast(self, group):
        for uid in list(group['members']):
            await self.state(uid)

    async def end(self, group, reason):
        record = self.rounds.pop(group['id'], None)
        if record:
            record['bodies'].clear()
            for uid in record['eligible']:
                await self.send(uid, {'type': 'ended', 'reason': reason})

    async def sweep(self):
        changed = False
        for group in self.groups.values():
            if group['code'] and group['codeUntil'] <= time.time()*1000:
                group['code'], group['codeUntil'] = '', 0
                changed = True
        if changed: self.save()
        for gid, r in list(self.rounds.items()):
            if r['expires'] <= time.monotonic():
                await self.end(self.groups[gid], '本轮已超时')

    async def handle(self, device, obj):
        from app import ActionError, MAX_BODY
        uid, kind = device['id'], obj.get('type')
        if kind == 'ping':
            await self.send(uid, {'type':'pong'})
            return
        if self.relay.limited('group-events:'+uid, 90):
            raise ActionError('操作过于频繁，请稍后再试')
        group = self.group(uid)
        if kind == 'group_create':
            if group: raise ActionError('请先退出当前好友群组')
            if len(self.groups) >= 1000: raise ActionError('服务器群组已满')
            gid = secrets.token_urlsafe(24)
            group = {'id':gid, 'members':{uid:device}, 'code':'', 'codeUntil':0}
            self.groups[gid] = group
            self.renew(group)
            await self.broadcast(group)
            return
        if kind == 'group_join':
            if group: raise ActionError('你已加入好友群组')
            if self.relay.limited('group-join:'+uid, 6): raise ActionError('尝试过于频繁')
            code = obj.get('code')
            group = next((g for g in self.groups.values() if g['code'] == code and g['codeUntil'] > time.time()*1000), None)
            if not group: raise ActionError('群组码无效或已过期')
            if len(group['members']) >= 5: raise ActionError('好友群组最多 5 人，已满员')
            group['members'][uid] = device
            self.save()
            await self.broadcast(group)
            return
        if not group: raise ActionError('请先创建或加入好友群组')
        if kind == 'group_code':
            self.renew(group)
            await self.broadcast(group)
            return
        if kind == 'group_leave':
            current = self.rounds.get(group['id'])
            if current:
                if uid in current['participants'] or uid == current['initiator']:
                    await self.end(group, '参与成员退出，本轮已结束')
                else:
                    if uid in current['accepted']: current['accepted'].remove(uid)
                    if uid in current['eligible']: current['eligible'].remove(uid)
                    await self.send(uid, {'type':'ended','reason':'你已退出群组'})
            group['members'].pop(uid)
            if not group['members']: self.groups.pop(group['id'])
            self.save()
            await self.state(uid)
            await self.broadcast(group)
            return
        if kind == 'invite':
            if group['id'] in self.rounds: raise ActionError('已有进行中的记录')
            eligible = list(group['members'])
            if len(self.rounds) >= 100: raise ActionError('服务器繁忙')
            self.rounds[group['id']] = {'id':secrets.token_urlsafe(24), 'initiator':uid, 'phase':'active', 'locked':False,
                'eligible':eligible, 'accepted':[uid], 'participants':[uid], 'expires':time.monotonic()+600, 'bodies':{}, 'acked':set()}
            await self.broadcast(group)
            return
        r = self.rounds.get(group['id'])
        if not r or obj.get('id') != r['id'] or uid not in r['eligible']: raise ActionError('本轮已结束或你未受邀')
        if r['expires'] <= time.monotonic():
            await self.end(group, '本轮已超时')
            return
        if kind == 'respond':
            if r['phase'] != 'active' or r['locked'] or uid == r['initiator']: raise ActionError('本轮已锁定，不能再加入')
            if obj.get('accept'):
                if uid not in r['accepted']: r['accepted'].append(uid)
                if uid not in r['participants']: r['participants'].append(uid)
            else:
                if uid in r['accepted']: r['accepted'].remove(uid)
                if uid in r['participants']: r['participants'].remove(uid)
                r['eligible'].remove(uid)
                await self.send(uid, {'type':'ended','reason':'你已选择不参加本轮'})
        elif kind == 'group_start':
            # Compatibility for older clients; capture already started at invitation.
            if uid != r['initiator']: raise ActionError('只有发起人可以开始')
        elif kind == 'submit':
            if r['phase'] != 'active' or uid not in r['participants'] or uid in r['bodies']: raise ActionError('当前不能提交')
            if len(r['participants']) < 2: raise ActionError('请等待至少一位好友加入后再提交')
            bodies = obj.get('bodies')
            recipients = set(r['participants'])-{uid}
            if not isinstance(bodies,dict) or set(bodies) != recipients: raise ActionError('收件人已变化，请重新提交')
            if sum(len(v) if isinstance(v,str) else MAX_BODY*5 for v in bodies.values()) > MAX_BODY*4: raise ActionError('图片过大')
            for body in bodies.values():
                if not isinstance(body,str) or not 40 <= len(body) <= MAX_BODY: raise ActionError('无效图片')
                try: base64.b64decode(body,validate=True)
                except ValueError: raise ActionError('无效密文') from None
            used = sum(len(body) for record in self.rounds.values() for payload in record['bodies'].values() for body in payload.values())
            if used + sum(map(len, bodies.values())) > 128 * 1024 * 1024: raise ActionError('中转空间暂满，请稍后重试')
            if not r['locked']:
                r['locked'] = True
                excluded = set(r['eligible']) - set(r['participants'])
                r['eligible'] = r['participants'][:]
                for member in excluded: await self.send(member, {'type':'ended','reason':'图片已开始提交，本轮加入已截止'})
            r['bodies'][uid] = bodies
            if len(r['bodies']) == len(r['participants']): r['phase'] = 'complete'
        elif kind == 'group_retry':
            if uid not in r['participants']: raise ActionError('你未参加本轮')
            await self.state(uid)
            return
        elif kind == 'ack':
            if r['phase'] == 'complete' and uid in r['participants']:
                r['acked'].add(uid)
                if len(r['acked']) == len(r['participants']):
                    r['bodies'].clear()
                    self.rounds.pop(group['id'])
            return
        elif kind == 'cancel':
            if uid not in r['accepted']: raise ActionError('你未参加本轮')
            await self.end(group,'本轮记录已结束')
            return
        else: raise ActionError('不支持的操作')
        await self.broadcast(group)

    def renew(self, group):
        code = ''.join(secrets.choice('0123456789') for _ in range(8))
        while any(g['code']==code for g in self.groups.values()): code=''.join(secrets.choice('0123456789') for _ in range(8))
        group['code'],group['codeUntil'] = code, int((time.time()+3*86400)*1000)
        self.save()

    async def connect(self, ws, device):
        from app import ActionError, MAX_BODY
        uid = device['id']
        try:
            async with self.relay.lock:
                if uid in self.connections: await self.connections[uid].close(code=4001)
                if len(self.connections) >= 200: raise ActionError('服务器繁忙')
                self.connections[uid]=ws
                group=self.group(uid)
                if group: await self.broadcast(group)
                else: await self.state(uid)
            while True:
                raw=await asyncio.wait_for(ws.receive_text(),90)
                if len(raw)>MAX_BODY*4+4096: raise ValueError('Payload too large')
                try:
                    obj=json.loads(raw)
                    if not isinstance(obj,dict): raise ValueError()
                    async with self.relay.lock: await self.handle(device,obj)
                except (ValueError,KeyError,TypeError) as error:
                    await self.send(uid,{'type':'error','message':str(error) if isinstance(error,ActionError) else '消息格式有误'})
        finally:
            async with self.relay.lock:
                if self.connections.get(uid) is ws:
                    self.connections.pop(uid,None)
                    group=self.group(uid)
                    if group: await self.broadcast(group)
