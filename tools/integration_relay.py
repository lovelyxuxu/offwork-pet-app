"""Local-only integration: real Java receiver + existing desktop relay protocol.

No product service, real identities, credentials or persistent business data are used.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid
from websockets.sync.client import connect

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--relay-project', type=Path, default=ROOT.parent / 'offwork-pet')
    parser.add_argument('--java', default='java')
    options = parser.parse_args()
    classpath = (ROOT / 'outputs/relay-probe-classpath.txt').read_text('utf-8')
    with socket.socket() as free:
        free.bind(('127.0.0.1', 0)); port = free.getsockname()[1]
    endpoint = f'http://127.0.0.1:{port}'
    events = []
    with tempfile.TemporaryDirectory(prefix='offwork-app-test-') as temporary:
        with open(Path(temporary) / 'relay.log', 'w', encoding='utf-8') as relaylog:
            server = subprocess.Popen([os.sys.executable, '-m', 'server', '--port', str(port)],
                cwd=options.relay_project, env=dict(os.environ, OFFWORK_DB=str(Path(temporary) / 'test.sqlite3')),
                stdout=relaylog, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            probe = None
            try:
                deadline = time.monotonic() + 15
                while True:
                    try:
                        with urllib.request.urlopen(endpoint + '/health', timeout=1) as response:
                            assert json.load(response)['ok']
                        break
                    except OSError:
                        if time.monotonic() > deadline: raise RuntimeError('Temporary relay failed to start')
                        time.sleep(.1)
                request = urllib.request.Request(endpoint + '/v2/devices', data=b'{}', headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(request) as response: token = json.load(response)['token']
                with connect(endpoint.replace('http:', 'ws:') + '/v2/connect', additional_headers={'Authorization': 'Bearer ' + token}) as sender:
                    def command(kind, **data):
                        identifier = uuid.uuid4().hex
                        sender.send(json.dumps(dict(type=kind, request_id=identifier, **data)))
                        while True:
                            message = json.loads(sender.recv(timeout=10))
                            if message.get('request_id') == identifier:
                                assert message['ok'], message
                                return message.get('data', {})
                    command('profile', paused=False, visible=True, capabilities=None, visitSupport=False, allowVisits=False)
                    group = command('rally_create', name='安卓接收本地验收', member_name='电脑测试端')['rally']
                    probe = subprocess.Popen([options.java, '-Dfile.encoding=UTF-8', '-cp', classpath,
                        'com.offworkpet.receiver.RelayProbe', endpoint, group['code']], cwd=ROOT,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW)
                    messages = queue.Queue()
                    def read():
                        for line in probe.stdout:
                            try: messages.put(json.loads(line))
                            except ValueError: messages.put({'event': 'diagnostic', 'text': line.strip()})
                    threading.Thread(target=read, daemon=True).start()
                    deadline = time.monotonic() + 20
                    while time.monotonic() < deadline:
                        event = messages.get(timeout=max(.1, deadline-time.monotonic())); events.append(event)
                        if event.get('event') == 'status' and event.get('text', '').startswith('正在接收'): break
                    else: raise AssertionError('Java receiver did not join')
                    for level, label, color in [('safe', '安全', '#3FA47A'), ('warning', '警戒', '#D39A32'), ('danger', '准备出发', '#A55ED0')]:
                        result = command('rally_alert', level=level, alert_id=uuid.uuid4().hex, label=label, color=color)
                        assert result['delivered'] == 1, result
                        event = messages.get(timeout=10); events.append(event)
                        assert event['event'] == 'alert' and event['label'] == label and event['level'] == level, event
                        assert 0 < event['remaining_ms'] <= 6000, event
                    event = messages.get(timeout=10); events.append(event); assert event['event'] == 'passed', event
                    assert probe.wait(timeout=10) == 0
                    command('rally_leave')
            finally:
                if probe and probe.poll() is None: probe.terminate(); probe.wait(timeout=10)
                server.terminate(); server.wait(timeout=10)
    report = {'passed': True, 'scope': 'local real relay and Java client; no Android device acceptance', 'events': events}
    output = ROOT / 'outputs/integration-relay.json'; output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS: registration, authentication, membership and three live alerts (including a custom signal).')

if __name__ == '__main__': main()
