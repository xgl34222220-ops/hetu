#!/usr/bin/env python3
"""One approved disposable CI run: grant only its UID, then restore the exact ACL."""
import argparse
import grp
import json
import os
from pathlib import Path
import pwd
import shutil
import stat
import subprocess

DEVICE = Path('/dev/kvm')
BASE = ['user::rw-', 'group::rw-', 'other::---']


def command(args):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=30).stdout


def acl():
    return command(['getfacl', '-p', '-n', str(DEVICE)])


def entries(value):
    return [line for line in value.splitlines() if line and not line.startswith('#')]


def identity():
    value = DEVICE.lstat()
    if not stat.S_ISCHR(value.st_mode):
        raise RuntimeError('KVM must be the actual character device, not a link or file')
    return {key: getattr(value, key) for key in ('st_dev', 'st_ino', 'st_rdev', 'st_uid', 'st_gid', 'st_mode')}


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def restore(folder):
    state_file = folder / 'original.json'
    if not state_file.exists():
        print('No KVM grant was prepared; nothing to restore', flush=True)
        return
    state = json.loads(state_file.read_text())
    report = {'restored': False}
    try:
        current = identity()
        if current != state['device']:
            raise RuntimeError('KVM device identity or mode changed; refusing restore onto another device')
        original = (folder / 'original.acl').read_text()
        if entries(original) != BASE or state['originalAcl'] != original:
            raise RuntimeError('Saved original ACL does not match the verified baseline')
        if acl() != original:
            command(['sudo', '-n', 'setfacl', '--restore=' + str((folder / 'original.acl').resolve())])
        after = acl()
        (folder / 'restored.acl').write_text(after)
        if after != original or identity() != state['device']:
            raise RuntimeError('Restored KVM ACL or metadata does not match the original')
        report = {'restored': True, 'uid': state['uid'], 'deviceMatches': True, 'aclMatches': True,
                  'currentUidReadWrite': os.access(DEVICE, os.R_OK | os.W_OK)}
        print('KVM original ACL and device metadata restored and verified', flush=True)
    except BaseException as error:
        report['error'] = str(error)
        raise
    finally:
        save(folder / 'restore-result.json', report)


def grant(folder):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('HETU_KVM_ONCE') != 'true':
        raise RuntimeError('This grant is restricted to the explicitly approved CI operation')
    if (os.environ.get('GITHUB_REPOSITORY') != 'xgl34222220-ops/hetu' or
        os.environ.get('GITHUB_REF') != 'refs/heads/test/dot-v2048-ui-safety' or
        os.environ.get('GITHUB_EVENT_NAME') != 'push' or os.environ.get('GITHUB_RUN_ATTEMPT') != '1'):
        raise RuntimeError('Unexpected repository, branch, event or repeated run')
    uid = os.getuid()
    if uid == 0 or pwd.getpwuid(uid).pw_name != 'runner':
        raise RuntimeError('Only the current non-root disposable runner UID may receive access')
    for tool in ('getfacl', 'setfacl', 'sudo'):
        if not shutil.which(tool):
            raise RuntimeError('Missing existing ACL tool: ' + tool)
    info = identity()
    if (info['st_uid'] != 0 or grp.getgrgid(info['st_gid']).gr_name != 'kvm' or
        stat.S_IMODE(info['st_mode']) != 0o660):
        raise RuntimeError('KVM owner, group or baseline mode differs from the reviewed runner')
    original = acl()
    if entries(original) != BASE or os.access(DEVICE, os.R_OK | os.W_OK):
        raise RuntimeError('Unexpected original KVM ACL or existing access; no mutation made')
    folder.mkdir(parents=True, exist_ok=True)
    if (folder / 'original.json').exists():
        raise RuntimeError('Existing KVM transaction: restore it instead of issuing another grant')
    (folder / 'original.acl').write_text(original)
    save(folder / 'original.json', {'uid': uid, 'device': info, 'originalAcl': original})
    try:
        command(['sudo', '-n', 'setfacl', '-m', f'u:{uid}:rw', str(DEVICE)])
        granted = acl()
        (folder / 'granted.acl').write_text(granted)
        expected = ['user::rw-', f'user:{uid}:rw-', 'group::rw-', 'mask::rw-', 'other::---']
        if entries(granted) != expected or identity() != info or not os.access(DEVICE, os.R_OK | os.W_OK):
            raise RuntimeError('UID-only KVM grant failed verification')
        save(folder / 'grant-result.json', {'granted': True, 'uid': uid, 'deviceMatches': True,
                                          'aclOnlyAddsCurrentUid': True, 'currentUidReadWrite': True})
        print(f'KVM access verified for current UID {uid} only', flush=True)
    except BaseException:
        restore(folder)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['grant', 'restore'])
    parser.add_argument('state_dir', type=Path)
    args = parser.parse_args()
    {'grant': grant, 'restore': restore}[args.action](args.state_dir)


if __name__ == '__main__':
    main()
