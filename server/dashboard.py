"""Password-protected daily usage dashboard."""
import hashlib
import hmac
import time
from urllib.parse import urlsplit
from datetime import date as CalendarDate
from pydantic import BaseModel, Field
import json
import secrets
from pathlib import Path

from fastapi import Depends, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.security import HTTPBasic


def mount_dashboard(app, relay):
    security = HTTPBasic(auto_error=False)

    def settings():
        auth_file = relay.directory / 'admin-auth.json'
        if not auth_file.exists():
            raise HTTPException(503, 'Dashboard login is not configured')
        return json.loads(auth_file.read_text(encoding='utf-8'))

    def valid_password(username, password):
        auth = settings()
        return (secrets.compare_digest(username.encode(), auth['username'].encode()) &
                secrets.compare_digest(hashlib.sha256(password.encode()).hexdigest(), auth['password_sha256']))

    def signature(payload):
        auth = settings()
        return hmac.new(relay.key, (auth['username'] + ':' + auth['password_sha256'] + ':' + payload).encode(), hashlib.sha256).hexdigest()

    def session_valid(request):
        cookie = request.cookies.get('sharetime_admin', '')
        try:
            payload, sig = cookie.rsplit('.', 1)
            if not hmac.compare_digest(sig, signature(payload)):
                return False
            expiry, nonce = payload.split(':')
            return len(nonce) == 32 and time.time() < int(expiry) <= time.time() + 8 * 3600
        except (ValueError, TypeError):
            return False

    def authenticate(request: Request, credentials=Depends(security)):
        if session_valid(request):
            return
        if credentials and valid_password(credentials.username, credentials.password):
            return
        raise HTTPException(401, 'Login required', headers={'Cache-Control': 'no-store'})

    def same_origin(request):
        origin = request.headers.get('origin')
        if origin and urlsplit(origin).netloc != request.headers.get('host'):
            raise HTTPException(403, 'Invalid origin')

    @app.get('/admin', response_class=HTMLResponse)
    @app.get('/admin/', response_class=HTMLResponse)
    async def dashboard():
        return HTMLResponse(Path(__file__).with_name('dashboard.html').read_text(encoding='utf-8'),
                            headers={'Cache-Control': 'no-store', 'X-Frame-Options': 'DENY',
                                     'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
                                     'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'"})

    class Login(BaseModel):
        username: str = Field(max_length=80)
        password: str = Field(max_length=256)

    @app.post('/admin/login')
    async def login(body: Login, request: Request):
        same_origin(request)
        if relay.limited('admin-login:' + request.client.host, 8):
            raise HTTPException(429, 'Too many login attempts')
        if not valid_password(body.username, body.password):
            raise HTTPException(401, 'Invalid username or password', headers={'Cache-Control': 'no-store'})
        payload = str(int(time.time()) + 8 * 3600) + ':' + secrets.token_hex(16)
        response = JSONResponse({'ok': True}, headers={'Cache-Control': 'no-store'})
        response.set_cookie('sharetime_admin', payload + '.' + signature(payload), max_age=8 * 3600,
                            secure=True, httponly=True, samesite='strict', path='/')
        return response

    @app.post('/admin/logout')
    async def logout(request: Request):
        same_origin(request)
        response = JSONResponse({'ok': True}, headers={'Cache-Control': 'no-store'})
        response.delete_cookie('sharetime_admin', path='/', secure=True, httponly=True, samesite='strict')
        return response

    @app.get('/admin/session')
    async def session(request: Request):
        return JSONResponse({'admin': session_valid(request)}, headers={'Cache-Control': 'no-store'})

    def selected_stats(date, online, profiles=None):
        if date:
            try:
                parsed = CalendarDate.fromisoformat(date)
                if parsed.isoformat() != date:
                    raise ValueError()
            except ValueError:
                raise HTTPException(400, 'Invalid date') from None
        try:
            return relay.metrics.for_date(date, online, profiles)
        except KeyError:
            raise HTTPException(404, 'No statistics for this date') from None

    @app.get('/admin/dates')
    async def dates():
        async with relay.lock:
            online = set(relay.connections) | set(relay.friends.connections)
            return JSONResponse({'dates': relay.metrics.dates(online), 'today': relay.metrics.data['date']}, headers={'Cache-Control': 'no-store'})

    @app.get('/admin/public-stats')
    async def public_stats(date: str | None = None):
        async with relay.lock:
            online = set(relay.connections) | set(relay.friends.connections)
            summary = selected_stats(date, online)
            result = {key: summary[key] for key in ['date', 'timezone', 'today_users', 'today_records', 'online_users', 'started_at']}
            result['registered_devices'] = relay.stats['registered_devices']
            for key in ['historical', 'history_partial']:
                if key in summary: result[key] = summary[key]
        return JSONResponse(result, headers={'Cache-Control': 'no-store'})

    @app.get('/admin/stats', dependencies=[Depends(authenticate)])
    async def dashboard_stats(date: str | None = None):
        async with relay.lock:
            online = set(relay.connections) | set(relay.friends.connections)
            profiles = {}
            for group in relay.friends.groups.values():
                profiles.update(group['members'])
            for pair in relay.pairs.values():
                profiles.update(pair['members'])
            # Include unpaired online users, including across midnight.
            profiles.update({uid: connection.get('profile', connection['device']) for uid, connection in relay.connections.items()})
            profiles.update(relay.friends.profiles)
            result = selected_stats(date, online, profiles)
            result['registered_devices'] = relay.stats['registered_devices']
        return JSONResponse(result, headers={'Cache-Control': 'no-store'})
