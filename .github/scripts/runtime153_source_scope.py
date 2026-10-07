"""Narrow source boundary for the r153 runtime layer.

Updates the Root runtime from revision 152 to 153:
- assets/hetu-root.sh: r152 -> r153 (core runs as root:net_admin via setuidgid,
  system DNS takeover, 6 previously-blocked switches now functional)
- ProxyRuntimeSettings.java: RUNTIME_REVISION 152 -> 153
- RootProxyManager.java: system-dns-direct marker, fake-ip segment tracking
- DnsTakeoverProbe.java (new): probes system resolver capture
- ProxyComposeController.kt: caches new status fields
- ProxyAdvancedSettingsActivity.kt: DNS hijack UI (system resolver takeover switch)
- ui/HetuLanguage.kt: 16 new i18n entries
- Runtime146ContractTest.kt: pins 153
- tools/qa/test_core_identity_dns.py (new): 23 host tests
- tools/qa/test_google_firewall.py: protocol test update

No version, signing, permission, Manifest, core binary or autostart changes.
Applied on top of the v2088 UI layer. The checked-in sources must reproduce
the patch output byte-for-byte.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

VERSION_CODE = 2086
VERSION_NAME = '0.12.16-v20-fix'
RUNTIME_REVISION = 153

PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
ALLOWED_FILES = {
    'android-app/app/src/main/assets/hetu-root.sh',
    PACKAGE + 'DnsTakeoverProbe.java',
    PACKAGE + 'ProxyAdvancedSettingsActivity.kt',
    PACKAGE + 'ProxyComposeController.kt',
    PACKAGE + 'ProxyRuntimeSettings.java',
    PACKAGE + 'RootProxyManager.java',
    PACKAGE + 'ui/HetuLanguage.kt',
    'android-app/app/src/test/java/io/github/xgl34222220/hetu/Runtime146ContractTest.kt',
    'tools/qa/test_core_identity_dns.py',
    'tools/qa/test_google_firewall.py',
}
BUILD_FILE = 'android-app/app/build.gradle.kts'

LAYER_DIR = ROOT / 'updates/v2089-runtime153'
INPUTS_FILE = LAYER_DIR / 'inputs.json'
PATCH_FILE = LAYER_DIR / 'runtime.patch'
PREVIOUS_INPUTS_FILE = ROOT / 'updates/v2088-ui-refactor/inputs.json'
PREVIOUS_PATCH_FILE = ROOT / 'updates/v2088-ui-refactor/ui.patch'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_scope(changes):
    blocked = sorted(name for name in changes if name not in ALLOWED_FILES)
    assert not blocked, 'r153 runtime layer changes out-of-scope file: ' + str(blocked)
    assert BUILD_FILE not in changes, 'r153 must not touch the version'
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts
               for name in changes), 'Source path escapes the checkout'


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    assert layer['schema'] == 1, 'Unknown r153 layer schema'
    assert layer['versionCode'] == VERSION_CODE
    assert layer['versionName'] == VERSION_NAME
    assert layer['runtimeRevision'] == RUNTIME_REVISION
    assert layer['previousInputsSha256'] == digest(PREVIOUS_INPUTS_FILE), \
        'v2088 inputs changed under the r153 layer'
    assert layer['previousPatchSha256'] == digest(PREVIOUS_PATCH_FILE), \
        'v2088 patch changed under the r153 layer'
    assert digest(PATCH_FILE) == layer['patchSha256'], \
        'r153 patch changed without input update'
    changes = layer['changedOrAddedFiles']
    assert changes, 'r153 layer records no changes'
    validate_scope(changes)
    for name, sha in changes.items():
        assert isinstance(sha, str) and len(sha) == 64 and all(
            c in '0123456789abcdef' for c in sha), 'Bad sha256 for ' + name
        assert sha != previous.get(name), 'Unchanged input recorded as a delta: ' + name
    assert layer['rootScriptSha256'] == changes.get(
        'android-app/app/src/main/assets/hetu-root.sh'), 'rootScriptSha256 mismatch'
    assert layer['protectedPresentationUnchanged'] is True
    assert layer['reproducedSourceMatchesCheckout'] is True
