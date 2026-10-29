"""One-time migration using the existing local FinalShell connection adapter.

Credentials and service keys stay in process memory during transfer.
"""
import importlib.util
import pathlib
import shlex
import sys
import time
import paramiko

ROOT = pathlib.Path(__file__).resolve().parents[1]

def main():
    adapter = pathlib.Path.home() / 'Desktop/codex/messageClaud/deployment-local/aliyun_ssh.py'
    spec = importlib.util.spec_from_file_location('cloud_adapter', adapter)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    cloud = module.connect()
    source = paramiko.SSHClient()
    source.load_system_host_keys()
    source.set_missing_host_key_policy(paramiko.RejectPolicy())
    password = sys.stdin.readline().rstrip('\r\n')
    source.connect('192.168.1.191', username='cy', password=password, look_for_keys=False, allow_agent=False)
    def local_root(cmd):
        i, o, e = source.exec_command('su - root -c ' + shlex.quote(cmd))
        i.write(password + '\n'); i.flush()
        data = o.read()
        if o.channel.recv_exit_status(): raise RuntimeError('Source operation failed')
        return data
    def run(cmd):
        result = module.run(cloud, cmd)
        if result: print(result, flush=True)
    release = '/opt/sharetime/releases/' + time.strftime('%Y%m%d-%H%M%S')
    try:
        run("set -eu; test ! -e /opt/sharetime/data/signing.key; id sharetime >/dev/null 2>&1 || useradd --system --home /opt/sharetime --shell /usr/sbin/nologin sharetime; install -d -m 755 " + release + "; install -d -m 700 -o sharetime -g sharetime /opt/sharetime/data; python3 -m venv " + release + '/venv')
        service = (ROOT / 'server/sharetime.service').read_text().replace('--host 0.0.0.0 --port 8443', '--host 127.0.0.1 --port 18843').replace(' --ssl-certfile /opt/sharetime/tls/server.crt --ssl-keyfile /opt/sharetime/tls/server.key', '')
        with cloud.open_sftp() as sftp:
            for name in ['app.py', 'requirements.txt']:
                sftp.put(str(ROOT / 'server' / name), release + '/' + name)
            with sftp.file('/etc/systemd/system/sharetime.service', 'w') as f: f.write(service)
        run(release + '/venv/bin/pip install --disable-pip-version-check -q -r ' + release + '/requirements.txt')
        # Freeze the original count and identities before taking the final snapshot.
        local_root('systemctl stop sharetime')
        with cloud.open_sftp() as sftp:
            for name in ['signing.key', 'stats.json']:
                data = local_root('cat /opt/sharetime/data/' + name)
                with sftp.file('/opt/sharetime/data/' + name, 'wb') as f: f.write(data)
                sftp.chmod('/opt/sharetime/data/' + name, 0o600)
            config = '/etc/nginx/conf.d/myweb.conf'
            with sftp.file(config) as f: original = f.read().decode()
            backup = config + '.sharetime-backup-' + time.strftime('%Y%m%d%H%M%S')
            with sftp.file(backup, 'w') as f: f.write(original)
            location = '''
    location = /sharetime { return 308 /sharetime/; }
    location ^~ /sharetime/ {
        proxy_pass http://127.0.0.1:18843/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 90s;
        proxy_buffering off;
        proxy_request_buffering off;
        client_max_body_size 5m;
        access_log off;
    }
'''
            assert '/sharetime/' not in original
            assert 'location /{' in original
            with sftp.file(config, 'w') as f: f.write(original.replace('location /{', location + '\n\tlocation /{', 1))
        run('set -eu; chown -R sharetime:sharetime /opt/sharetime/data; ln -sfn ' + release + ' /opt/sharetime/current; systemctl daemon-reload; systemctl enable --now sharetime; nginx -t; systemctl reload nginx; systemctl is-active sharetime; curl --retry 5 --retry-connrefused --retry-delay 1 --fail -s http://127.0.0.1:18843/health')
        local_root('systemctl disable sharetime')
        print('MIGRATED https://cyadmin.xyz/sharetime')
    finally:
        source.close(); cloud.close()

if __name__ == '__main__': main()
