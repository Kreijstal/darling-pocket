#!/usr/bin/env python3
"""Termux debugging for Darling Pocket: commands run as the app, on the phone.

Turn on Termux debugging in Darling Pocket, copy its connection token, then:

    python pocketctl.py pair          # paste the token
    python pocketctl.py status
    python pocketctl.py logcat        # the app's log (Java, the display, crashes)
    python pocketctl.py shell         # /system/bin/sh as the app, in its files
    python pocketctl.py darling ls /Applications
    python pocketctl.py pull files/logs/Cydia.log Cydia.log

Paths relative to the app are in its data directory. Needs only Python 3.
The protocol is aosp-pocket's owner console (AdminServer.java).
"""
import argparse
import getpass
import hmac
import io
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import sys
import threading

MAGIC = b'PKADMIN1'
CHUNK = 65536
PORT = 2229
DATA = '/data/user/0/dev.kreijstal.darlingpocket'


def exact(sock, count):
    data = bytearray()
    while len(data) < count:
        part = sock.recv(count-len(data))
        if not part:
            raise OSError('Darling Pocket closed the connection')
        data.extend(part)
    return bytes(data)


def connect(config):
    key = bytes.fromhex(config['token'])
    if len(key) != 32:
        raise ValueError('Invalid connection token; pair again')
    try:
        peer = socket.create_connection(('127.0.0.1', config['port']), timeout=5)
    except ConnectionRefusedError:
        raise OSError('Nothing listens on port %d: turn on Termux debugging in Darling Pocket' % config['port'])
    try:
        if exact(peer, len(MAGIC)) != MAGIC:
            raise OSError('This port is not Darling Pocket')
        nonce = exact(peer, 32)
        proof = hmac.digest(key, MAGIC+nonce, 'sha256')
        peer.sendall(proof)
        if exact(peer, 1) != b'\x01':
            raise OSError('Connection token rejected; copy the current token and pair again')
        expected = hmac.digest(key, MAGIC+nonce+proof, 'sha256')
        if not hmac.compare_digest(exact(peer, 32), expected):
            raise OSError('Darling Pocket failed to authenticate itself')
        peer.settimeout(None)
        return peer
    except BaseException:
        peer.close()
        raise


def string(value):
    data = value.encode('utf-8')
    if b'\x00' in data or len(data) > CHUNK:
        raise ValueError('Command argument is invalid or too long')
    return struct.pack('!I', len(data))+data


def request(config, operation, argv=(), cwd=DATA, source=None, stdout=None, stderr=None):
    stdout = sys.stdout.buffer if stdout is None else stdout
    stderr = sys.stderr.buffer if stderr is None else stderr
    with connect(config) as peer:
        message = bytes([operation])
        if operation == 2:
            if not 1 <= len(argv) <= 128:
                raise ValueError('Expected 1–128 arguments')
            message += string(cwd)+struct.pack('!I', len(argv))+b''.join(map(string, argv))
        peer.sendall(message)

        def send_input():
            try:
                if source is not None:
                    try:
                        descriptor = source.fileno()
                    except (AttributeError, io.UnsupportedOperation):
                        descriptor = None
                    while True:
                        data = os.read(descriptor, CHUNK) if descriptor is not None else source.read(CHUNK)
                        if not data:
                            break
                        peer.sendall(b'\x01'+struct.pack('!I', len(data))+data)
                peer.sendall(b'\x02\x00\x00\x00\x00')
            except (OSError, ValueError):
                pass  # the receiver reports transport failures or the exit status

        if operation == 2:
            threading.Thread(target=send_input, daemon=True).start()
        while True:
            kind, count = struct.unpack('!BI', exact(peer, 5))
            if count > CHUNK*16:
                raise OSError('Invalid output frame')
            data = exact(peer, count)
            if kind == 3:
                if count != 4:
                    raise OSError('Invalid exit status')
                return struct.unpack('!i', data)[0]
            if kind not in (1, 2, 4):
                raise OSError('Unknown output frame')
            target = stdout if kind == 1 else stderr
            target.write(data+(b'\n' if kind == 4 else b''))
            target.flush()


def save_config(path, config):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name+'.'+secrets.token_hex(8)+'.tmp')
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as out:
            json.dump(config, out)
            out.write('\n')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--config', type=Path, default=Path.home()/'.config/darling-pocket/pocketctl.json')
    sub = parser.add_subparsers(dest='operation', required=True)
    pair = sub.add_parser('pair', help='Authorize this client with the token from Darling Pocket')
    pair.add_argument('--port', type=int, default=PORT)
    sub.add_parser('status', help="Darling Pocket's state, as JSON")
    sub.add_parser('start', help='Start the home screen (SpringBoard)')
    sub.add_parser('stop', help='Stop the running app and the container')
    sub.add_parser('logcat', help="The app's log; logcat's own options pass through (default -d)")
    shell = sub.add_parser('shell', help='/system/bin/sh as the app (pipes; no terminal)')
    shell.add_argument('-c', dest='command')
    shell.add_argument('--cwd', default=DATA)
    execute = sub.add_parser('exec', help='Run a program as the app, keeping argument boundaries')
    execute.add_argument('--cwd', default=DATA)
    execute.add_argument('argv', nargs=argparse.REMAINDER)
    darling = sub.add_parser('darling', help='Run a command in the container: darling shell, as the app starts apps')
    darling.add_argument('argv', nargs=argparse.REMAINDER)
    push = sub.add_parser('push', help='Copy a file to the app (relative: its data directory)')
    push.add_argument('local', type=Path)
    push.add_argument('remote')
    pull = sub.add_parser('pull', help='Copy a file from the app (relative: its data directory)')
    pull.add_argument('remote')
    pull.add_argument('local', type=Path)
    args, extra = parser.parse_known_args()
    if extra and args.operation != 'logcat':
        parser.error('unrecognized arguments: '+' '.join(extra))
    if args.operation == 'pair':
        token = getpass.getpass('Darling Pocket connection token: ').strip()
        config = {'port': args.port, 'token': token}
        code = request(config, 1, stdout=io.BytesIO())
        if code:
            return code
        save_config(args.config, config)
        print('Paired. Try: pocketctl.py status, pocketctl.py logcat, pocketctl.py shell')
        return 0
    try:
        config = json.loads(args.config.read_text())
    except FileNotFoundError:
        raise OSError('Not paired yet: pocketctl.py pair')
    if args.operation in ('status', 'start', 'stop'):
        return request(config, {'status': 1, 'start': 3, 'stop': 4}[args.operation])
    if args.operation == 'logcat':
        return request(config, 2, ['/system/bin/logcat']+(extra or ['-d']))
    if args.operation == 'shell':
        argv = ['/system/bin/sh', '-i'] if args.command is None else ['/system/bin/sh', '-c', args.command]
        return request(config, 2, argv, args.cwd, sys.stdin.buffer)
    if args.operation == 'exec':
        argv = args.argv[1:] if args.argv[:1] == ['--'] else args.argv
        return request(config, 2, argv, args.cwd, sys.stdin.buffer)
    if args.operation == 'darling':
        argv = args.argv[1:] if args.argv[:1] == ['--'] else args.argv
        return request(config, 2, ['darling']+(argv or ['/bin/bash']), source=sys.stdin.buffer)
    if args.operation == 'push':
        with args.local.open('rb') as source:
            return request(config, 2, ['/system/bin/sh', '-c', 'cat > "$1"', 'pocketctl-push', args.remote],
                           source=source)
    if args.operation == 'pull':
        temporary = args.local.with_name(args.local.name+'.'+secrets.token_hex(8)+'.tmp')
        try:
            with temporary.open('xb') as out:
                code = request(config, 2, ['/system/bin/cat', args.remote], stdout=out)
            if code == 0:
                temporary.replace(args.local)
            return code
        finally:
            temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        status = main()
    except KeyboardInterrupt:
        status = 130
    except (OSError, ValueError, KeyError, EOFError) as error:
        print('pocketctl:', error, file=sys.stderr)
        status = 1
    sys.exit(status if 0 <= status <= 255 else 1)
