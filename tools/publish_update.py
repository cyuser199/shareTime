"""Publish an APK atomically over authenticated SSH, with no public write endpoint.

Example: python tools/publish_update.py --host host --user root --key ~/.ssh/id_ed25519 --apk app.apk --code 6 --version 0.4.0 --notes 'Changes'
Uses existing SSH keys/agent. Never stores credentials.
"""
import argparse
import hashlib
import json
import pathlib
import shlex
import paramiko

def publish(client, apk, code, version, notes, base='https://cyadmin.xyz/sharetime'):
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    directory = '/opt/sharetime/updates'
    _, out, err = client.exec_command('install -d -o sharetime -g sharetime -m 755 ' + directory)
    if out.channel.recv_exit_status(): raise RuntimeError(err.read().decode())
    with client.open_sftp() as sftp:
        target = directory + '/' + digest + '.apk'
        sftp.put(str(apk), target + '.tmp')
        sftp.chmod(target + '.tmp', 0o644)
        # Check the actual remote upload before publishing the manifest.
        _, out, _ = client.exec_command('sha256sum ' + shlex.quote(target + '.tmp'))
        if out.read().decode().split()[0] != digest: raise RuntimeError('Upload hash mismatch')
        sftp.posix_rename(target + '.tmp', target)
        data = {'versionCode': code, 'versionName': version, 'notes': notes,
                'apkUrl': base.rstrip('/') + '/updates/apk/' + digest, 'sha256': digest, 'size': apk.stat().st_size}
        with sftp.file(directory + '/latest.json.tmp', 'w') as f:
            f.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))
        sftp.chmod(directory + '/latest.json.tmp', 0o644)
        sftp.posix_rename(directory + '/latest.json.tmp', directory + '/latest.json')
    return data

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', required=True)
    parser.add_argument('--user', default='root')
    parser.add_argument('--key')
    parser.add_argument('--apk', required=True, type=pathlib.Path)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--version', required=True)
    parser.add_argument('--notes', required=True)
    parser.add_argument('--base', default='https://cyadmin.xyz/sharetime')
    args = parser.parse_args()
    with paramiko.SSHClient() as client:
        client.load_system_host_keys()
        client.set_missing_host_key_policy(paramiko.RejectPolicy())
        client.connect(args.host, username=args.user, key_filename=args.key)
        print(json.dumps(publish(client, args.apk, args.code, args.version, args.notes, args.base), ensure_ascii=False))
