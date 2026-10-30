"""Deploy only this app to Debian. Password is read interactively/stdin, never saved."""
import getpass
import pathlib
import sys
import time
import shlex
import secrets
import argparse

import paramiko

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wheels', type=pathlib.Path, help='Optional local Linux/Python 3.13 wheel directory')
    args = parser.parse_args()
    password = getpass.getpass('Debian root password: ') if sys.stdin.isatty() else sys.stdin.readline().rstrip('\r\n')
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect('192.168.1.191', username='cy', password=password, timeout=10, look_for_keys=False, allow_agent=False)
    release = '/opt/sharetime/releases/' + time.strftime('%Y%m%d-%H%M%S')
    staging = '/tmp/sharetime-' + secrets.token_hex(12)
    _, out, _ = ssh.exec_command('mkdir -m 700 ' + staging)
    assert out.channel.recv_exit_status() == 0
    def run(command):
        stdin, out, err = ssh.exec_command('su - root -c ' + shlex.quote(command), timeout=1800)
        stdin.write(password + '\n')
        stdin.flush()
        # Stream to prevent SSH channel window deadlocks during apt/pip.
        for line in out:
            print(line.rstrip(), flush=True)
        error = err.read().decode(errors='replace')
        code = out.channel.recv_exit_status()
        if error:
            print(error[-4000:], flush=True)
        if code:
            raise RuntimeError('Remote command failed: ' + str(code))
    try:
        run("set -eu\napt-get update -qq\nDEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv openssl\nid sharetime >/dev/null 2>&1 || useradd --system --home /opt/sharetime --shell /usr/sbin/nologin sharetime\ninstall -d -m 755 " + release + "\ninstall -d -o sharetime -g sharetime -m 700 /opt/sharetime/data\ninstall -d -m 750 -g sharetime /opt/sharetime/tls")
        with ssh.open_sftp() as sftp:
            for name in ['app.py', 'pairing.py', 'groups.py', 'requirements.txt']:
                sftp.put(str(ROOT / 'server' / name), staging + '/' + name)
            sftp.put(str(ROOT / 'server' / 'sharetime.service'), staging + '/sharetime.service')
            if args.wheels:
                sftp.mkdir(staging + '/wheels')
                for wheel in args.wheels.glob('*.whl'):
                    sftp.put(str(wheel), staging + '/wheels/' + wheel.name)
        run('install -m 644 ' + staging + '/app.py ' + staging + '/pairing.py ' + staging + '/groups.py ' + staging + '/requirements.txt ' + release)
        run("""set -eu
if [ ! -f /opt/sharetime/tls/ca.crt ]; then
 openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 -keyout /opt/sharetime/tls/ca.key -out /opt/sharetime/tls/ca.crt -subj '/CN=ShareTime Local CA' -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign'
 chmod 600 /opt/sharetime/tls/ca.key
fi
if [ ! -f /opt/sharetime/tls/server.crt ]; then
 openssl req -newkey rsa:2048 -nodes -keyout /opt/sharetime/tls/server.key -out /opt/sharetime/tls/server.csr -subj '/CN=192.168.1.191'
 printf 'subjectAltName=IP:192.168.1.191,DNS:localhost\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' > /opt/sharetime/tls/server.ext
 openssl x509 -req -in /opt/sharetime/tls/server.csr -CA /opt/sharetime/tls/ca.crt -CAkey /opt/sharetime/tls/ca.key -CAcreateserial -out /opt/sharetime/tls/server.crt -days 825 -sha256 -extfile /opt/sharetime/tls/server.ext
fi
chown root:sharetime /opt/sharetime/tls/server.key /opt/sharetime/tls/server.crt /opt/sharetime/tls/ca.crt
chmod 640 /opt/sharetime/tls/server.key
"""
            + "python3 -m venv " + release + "/venv\n"
            + release + "/venv/bin/pip install --disable-pip-version-check -q " + ("--no-index --find-links=" + staging + "/wheels " if args.wheels else '') + "-r " + release + "/requirements.txt\n"
            + "ln -sfn " + release + " /opt/sharetime/current\n"
            + "if [ -f /etc/systemd/system/sharetime.service ]; then cp /etc/systemd/system/sharetime.service /opt/sharetime/sharetime.service.previous; fi\n"
            + "install -m 644 " + staging + "/sharetime.service /etc/systemd/system/sharetime.service\nsystemctl daemon-reload\nsystemctl enable --now sharetime\nsystemctl restart sharetime\nsystemctl is-active sharetime\n"
            + "install -m 644 /opt/sharetime/tls/ca.crt " + staging + "/ca.crt\nchown cy:cy " + staging + "/ca.crt")
        public_ca = ROOT / 'android/app/src/main/res/raw/sharetime_ca.pem'
        public_ca.parent.mkdir(parents=True, exist_ok=True)
        with ssh.open_sftp() as sftp:
            sftp.get(staging + '/ca.crt', str(public_ca))
        print('DEPLOYED', release)
    finally:
        ssh.close()


if __name__ == '__main__':
    main()
