"""ADB helpers for the authorized ShareTime device test. Captures stay local."""
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = os.environ.get('SHARETIME_DEVICE', 'fd6e1506')
EVIDENCE = pathlib.Path(__file__).resolve().parents[1] / 'docs/device-test-20261029'
ADB = ['adb', '-s', SERIAL]


def adb(*args):
    return subprocess.check_output(ADB + list(args))


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/sharetime-ui.xml')
    return adb('exec-out', 'cat', '/sdcard/sharetime-ui.xml')


def tap(text):
    root = ET.fromstring(tree())
    nodes = [n for n in root.iter('node') if n.get('text') == text or n.get('content-desc') == text]
    if not nodes:
        raise RuntimeError('UI target missing: ' + text)
    nodes.sort(key=lambda node: (node.get('content-desc') != text, node.get('clickable') != 'true'))
    n = nodes[0]
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def snapshot(name):
    EVIDENCE.mkdir(exist_ok=True, parents=True)
    raw = tree()
    (EVIDENCE / (name + '.xml')).write_bytes(raw)
    (EVIDENCE / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    for n in ET.fromstring(raw).iter('node'):
        if n.get('text') or n.get('content-desc') or n.get('class') == 'android.widget.EditText':
            print(json.dumps({k: n.get(k) for k in ['text', 'content-desc', 'class', 'bounds', 'clickable', 'enabled']}, ensure_ascii=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if sys.argv[1] == 'snapshot':
        snapshot(sys.argv[2])
    elif sys.argv[1] == 'tap':
        tap(sys.argv[2])
