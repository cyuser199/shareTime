"""Bounded, durable pairing reservations. Contains no record images or private keys."""
import hashlib
import hmac
import json
import time

TTL = 3 * 86400


class PairingRegistry:
    def __init__(self, relay):
        self.relay = relay
        self.file = relay.directory / 'pairing.json'
        self.data = json.loads(self.file.read_text()) if self.file.exists() else {'codes': {}, 'pending': {}, 'deliveries': {}}
        self.clean()

    def save(self):
        temporary = self.file.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.data, ensure_ascii=False), encoding='utf-8')
        temporary.chmod(0o600)
        temporary.replace(self.file)

    def clean(self):
        changed = False
        for section in self.data.values():
            for key, value in list(section.items()):
                if value['expires'] <= time.time():
                    section.pop(key)
                    changed = True
        if changed:
            self.save()

    def code(self, device):
        # The same authenticated identity always receives the same code.
        value = hmac.digest(self.relay.key, ('pair-code:' + device['id']).encode(), 'sha256')
        return str(int.from_bytes(value[:8], 'big') % 100_000_000).zfill(8)

    async def reserve(self, device, ticket=None, renew=False):
        from app import ActionError
        self.clean()
        uid = device['id']
        code = self.code(device)
        expires = time.time() + TTL
        if ticket and not renew:
            claim = self.relay.verify(ticket, 'reservation')
            if claim['uid'] != uid or claim['code'] != code:
                raise ActionError('无效配对登记')
            expires = min(expires, claim['expires'])
        if not self.relay.pair_for(uid) and expires > time.time():
            old = self.data['codes'].get(code)
            if old and old['device']['id'] != uid:
                raise ActionError('配对码暂时冲突，请稍后重新登记')
            if len(self.data['codes']) >= 10000 and not old:
                raise ActionError('服务器登记已满，请稍后重试')
            self.data['codes'][code] = {'device': device, 'expires': expires}
            self.save()
        elif self.relay.pair_for(uid):
            expires = 0
        reservation = self.relay.sign({'kind': 'reservation', 'uid': uid, 'code': code, 'expires': expires})
        await self.relay.send(uid, {'type': 'fixed_code', 'code': code, 'ticket': reservation,
                                    'expiresAt': int(expires * 1000)})

    async def join(self, device, code):
        from app import ActionError
        self.clean()
        relay = self.relay
        uid = device['id']
        if relay.pair_for(uid) or relay.limited('fixed-join:' + uid, 6):
            raise ActionError('已有配对，或尝试次数过多')
        owner = self.data['codes'].get(code) if isinstance(code, str) else None
        guest = self.data['codes'].get(self.code(device))
        if not owner or not guest or owner['device']['id'] == uid:
            raise ActionError('配对码未登记或登记已过期，请对方重新登记原码')
        if relay.pair_for(owner['device']['id']):
            raise ActionError('对方已配对')
        # One outgoing request per guest; repeating it is idempotent.
        request = {'owner': owner['device'], 'guest': device, 'expires': min(owner['expires'], guest['expires'])}
        self.data['pending'][uid] = request
        self.save()
        if owner['device']['id'] in relay.connections:
            await self.complete(uid, request)
        else:
            await relay.send(uid, {'type': 'pair_waiting', 'message': '已发送，对方不在线，等待对方上线后配对'})

    async def complete(self, key, request):
        import secrets
        relay = self.relay
        members = {d['id']: d for d in [request['owner'], request['guest']]}
        if any(relay.pair_for(uid) for uid in members):
            self.data['pending'].pop(key, None)
            self.save()
            await relay.send(request['guest']['id'], {'type': 'pair_waiting', 'message': '配对申请已结束，有设备已经配对'})
            return
        pair = {'id': secrets.token_urlsafe(24), 'members': members}
        certificate = relay.sign({'kind': 'pair', **pair})
        relay.pairs[pair['id']] = pair
        self.data['pending'] = {k: v for k, v in self.data['pending'].items()
                                if v['owner']['id'] not in members and v['guest']['id'] not in members}
        for uid in members:
            self.data['deliveries'][uid] = {'certificate': certificate, 'expires': request['expires']}
        self.save()
        await relay.broadcast_pair(pair, {'type': 'paired', 'certificate': certificate})
        for uid in members:
            await relay.state(uid)

    async def reconnect(self, device, ticket):
        self.clean()
        uid = device['id']
        delivery = self.data['deliveries'].get(uid)
        if delivery:
            pair = self.relay.verify(delivery['certificate'], 'pair')
            if not any(self.relay.pair_for(m) and self.relay.pair_for(m)['id'] != pair['id'] for m in pair['members']):
                self.relay.pairs[pair['id']] = {'id': pair['id'], 'members': pair['members']}
                await self.relay.send(uid, {'type': 'paired', 'certificate': delivery['certificate']})
        for key, request in list(self.data['pending'].items()):
            if request['owner']['id'] == uid:
                await self.complete(key, request)
        await self.reserve(device, ticket)
        if uid in self.data['pending']:
            await self.relay.send(uid, {'type': 'pair_waiting', 'message': '已发送，对方不在线，等待对方上线后配对'})

    def ack(self, device):
        if not self.relay.pair_for(device['id']):
            return
        self.data['deliveries'].pop(device['id'], None)
        self.data['codes'].pop(self.code(device), None)
        self.save()

    def unpair(self, members):
        for uid in members:
            self.data['deliveries'].pop(uid, None)
        self.data['pending'] = {k: v for k, v in self.data['pending'].items()
                                if v['owner']['id'] not in members and v['guest']['id'] not in members}
        self.save()
