"""Daily counts, nicknames and self-reported device models. No images or raw device IDs are stored."""
import hashlib
import hmac
import json
from datetime import datetime, timedelta, timezone

CHINA = timezone(timedelta(hours=8))


class DailyMetrics:
    def __init__(self, directory, key, clock=None):
        self.file = directory / 'daily-stats.json'
        self.history_file = directory / 'stats-history.json'
        self.history = json.loads(self.history_file.read_text(encoding='utf-8')) if self.history_file.exists() else {}
        self.key = key
        self.clock = clock or (lambda: datetime.now(CHINA))
        self.data = json.loads(self.file.read_text(encoding='utf-8')) if self.file.exists() else {
            'started_at': self.clock().isoformat(timespec='seconds'),
            'date': '', 'devices': [], 'completed_rounds': 0,
        }
        # Preserve existing totals when adding the per-user view.
        self.dirty = 'users' not in self.data
        if self.dirty:
            self.data['users'] = {tag: {'name': '', 'records': 0} for tag in self.data['devices']}
            self.data['details_started_at'] = self.clock().isoformat(timespec='seconds')
            self.data['details_partial'] = self.data['completed_rounds'] > 0

    def tag(self, uid):
        day = self.data['date']
        return hmac.new(self.key, ('daily:' + day + ':' + uid).encode(), hashlib.sha256).hexdigest()

    def refresh(self, devices=()):
        day = self.clock().astimezone(CHINA).date().isoformat()
        changed = self.dirty
        if self.data['date'] != day:
            if self.data['date']:
                details = [{'name': user.get('name') or '待识别昵称', 'device_model': user.get('device_model') or '未上报',
                            'records': user['records'], 'device_tag': tag[:8]} for tag, user in self.data['users'].items() if user.get('history_consent')]
                self.history[self.data['date']] = {'users': len(self.data['devices']), 'records': self.data['completed_rounds'],
                    'partial': False, 'details': details, 'details_partial': len(details) < len(self.data['devices'])}
                self.save_history()
            self.data.update(date=day, devices=[], users={}, completed_rounds=0, details_partial=False)
            changed = True
        seen = set(self.data['devices'])
        for uid in devices:
            # Daily salted identifiers are discarded when the day changes.
            tag = self.tag(uid)
            seen.add(tag)
            if tag not in self.data['users']:
                self.data['users'][tag] = {'name': '', 'records': 0}
                changed = True
            if isinstance(devices, dict):
                device = devices[uid]
                profile = device if isinstance(device, dict) else {'name': str(device)}
                changed = self.update_profile(self.data['users'][tag], profile) or changed
        if seen != set(self.data['devices']):
            self.data['devices'] = sorted(seen)
            changed = True
        if changed:
            self.save()
            self.dirty = False

    def completed(self, participants):
        self.refresh(participants)
        self.data['completed_rounds'] += 1
        for uid in set(participants):
            self.data['users'][self.tag(uid)]['records'] += 1
        self.save()

    def save(self):
        temporary = self.file.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.data), encoding='utf-8')
        temporary.chmod(0o600)
        temporary.replace(self.file)

    @staticmethod
    def update_profile(user, profile):
        changed = False
        for source, target, limit in [('name', 'name', 24), ('deviceModel', 'device_model', 80)]:
            value = profile.get(source)
            if not isinstance(value, str):
                continue
            value = ' '.join(''.join(c for c in value if c.isprintable()).split())[:limit]
            if value and value != user.get(target):
                user[target] = value
                changed = True
        version = profile.get('privacyVersion')
        if isinstance(version, int) and not isinstance(version, bool) and version >= 6 and not user.get('history_consent'):
            user['history_consent'] = True
            changed = True
        return changed

    def identify(self, profiles):
        changed = False
        for uid, profile in profiles.items():
            user = self.data['users'].get(self.tag(uid))
            if user is not None:
                changed = self.update_profile(user, profile) or changed
        if changed:
            self.save()

    def summary(self, online, profiles=None):
        self.refresh(online)
        if profiles:
            self.identify(profiles)
        online_tags = {self.tag(uid) for uid in online}
        users = [{'name': value['name'] or '待识别昵称', 'device_tag': tag[:8],
                  'device_model': value.get('device_model') or '未上报',
                  'records': value['records'], 'online': tag in online_tags}
                 for tag, value in self.data['users'].items()]
        users.sort(key=lambda item: (-item['records'], item['name'], item['device_tag']))
        return {'date': self.data['date'], 'timezone': 'Asia/Shanghai',
                'today_users': len(self.data['devices']), 'today_records': self.data['completed_rounds'],
                'online_users': len(set(online)), 'started_at': self.data['started_at'],
                'users': users, 'details_started_at': self.data['details_started_at'],
                'details_partial': self.data['details_partial']}


    def save_history(self):
        temporary = self.history_file.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.history), encoding='utf-8')
        temporary.chmod(0o600)
        temporary.replace(self.history_file)

    def dates(self, online=()):
        self.refresh(online)
        return sorted(set(self.history) | {self.data['date']}, reverse=True)

    def for_date(self, date, online, profiles=None):
        current = self.summary(online, profiles)
        if not date or date == current['date']:
            return current
        day = self.history.get(date)
        if day is None:
            raise KeyError(date)
        return {**current, 'date': date, 'today_users': day['users'], 'today_records': day['records'],
                'users': [{**user, 'online': False} for user in day.get('details', [])],
                'historical': True, 'history_partial': day.get('partial', False),
                'history_details_saved': 'details' in day, 'history_details_partial': day.get('details_partial', True),
                'details_partial': False}
