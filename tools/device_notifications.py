"""Read only ShareTime notification details, using cmd notification keys."""
import json
import pathlib
import re
import shlex
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
serial = sys.argv[1]
base = ['adb', '-s', serial, 'shell', 'cmd', 'notification']
keys = subprocess.check_output(base + ['list']).decode('utf-8', errors='replace').splitlines()
result = []
for key in keys:
    if '|cn.sharetime.app|' not in key:
        continue
    text = subprocess.check_output(['adb', '-s', serial, 'shell', 'cmd notification get ' + shlex.quote(key)]).decode('utf-8', errors='replace')
    fields = [line.strip() for line in text.splitlines()
              if any(line.strip().startswith(prefix) for prefix in
                     ['NotificationRecord(', 'android.title=', 'android.text=', 'mImportance=', 'mIntercept=', 'mSuppressedVisualEffects='])]
    result.append({'key': key, 'details': fields})
print(json.dumps(result, ensure_ascii=False, indent=2))
if len(sys.argv) > 2:
    pathlib.Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
