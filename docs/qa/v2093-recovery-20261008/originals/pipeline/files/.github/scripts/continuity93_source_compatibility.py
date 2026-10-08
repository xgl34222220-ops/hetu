#!/usr/bin/env python3
"""Exact old behavior with explicitly retained current API shape for C/F compilation."""
import difflib
import hashlib
import json
from pathlib import Path
import re

from continuity93_source_scope import ROOT, BASE_COMMIT, PACKAGE, committed_bytes, digest

FOLDER = 'docs/qa/20261008-feedback93-before-api-compatibility'
BINDING = FOLDER + '/binding.json'
CONTROLS = {
    'motion-only': (PACKAGE + 'panel/PanelComponents.kt', FOLDER + '/panel-components-cff-with-current-call-signature.kt',
                    FOLDER + '/panel-components-exact-api-diff.patch'),
    'root-stop-result': (PACKAGE + 'RootProxyManager.java', FOLDER + '/root-manager-cff-with-current-timeout-api.java',
                         FOLDER + '/root-manager-exact-api-diff.patch'),
}
PANEL_SIGNATURE = 'internal fun PanelDelayLabel(delay: PanelDelay?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onCard: Boolean = false) {'
PANEL_CURRENT_SIGNATURE = PANEL_SIGNATURE.replace(') {', ', onClickLabel: String = "测速") {')
ROOT_ANCHOR = '    private static final ProxyControlEpoch CONTROL_LOCK=new ProxyControlEpoch();\n'
ROOT_BUDGET_FIELD = '    private static final ThreadLocal<long[]> AUTOMATIC_START_BUDGET=new ThreadLocal<>();\n'
ROOT_TIMEOUT_METHOD = '    static long automaticRootTimeout(long requested)throws IOException{'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def timeout_api(current):
    text = current.decode()
    assert text.count(ROOT_BUDGET_FIELD) == text.count(ROOT_TIMEOUT_METHOD) == 1
    start = text.index(ROOT_TIMEOUT_METHOD)
    token = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]')
    depth = 0
    for part in token.finditer(text, start):
        if part.group() == '{': depth += 1
        elif part.group() == '}':
            depth -= 1
            if depth == 0:
                assert text[part.end():part.end() + 1] == '\n'
                return (ROOT_BUDGET_FIELD + text[start:part.end() + 1]).encode()
    raise AssertionError('Unterminated current timeout API')


def projection(original, current, variant):
    text = original.decode()
    if variant == 'motion-only':
        assert text.count(PANEL_SIGNATURE) == 1 and PANEL_CURRENT_SIGNATURE in current.decode()
        return text.replace(PANEL_SIGNATURE, PANEL_CURRENT_SIGNATURE, 1).encode(), PANEL_CURRENT_SIGNATURE.encode()
    assert variant == 'root-stop-result'
    assert text.count(ROOT_ANCHOR) == 1 and 'AUTOMATIC_START_BUDGET' not in text and 'automaticRootTimeout' not in text
    api = timeout_api(current)
    return text.replace(ROOT_ANCHOR, ROOT_ANCHOR + api.decode(), 1).encode(), api


def exact_diff(original, compatible, name):
    return ''.join(difflib.unified_diff(original.decode().splitlines(keepends=True),
        compatible.decode().splitlines(keepends=True), fromfile='cff/' + name, tofile='compatible/' + name)).encode()


def validate_projection(original, current, compatible, variant):
    expected, api = projection(original, current, variant)
    assert compatible == expected, 'Before API adapter changed old production behavior: ' + variant
    return api


def controls(root=ROOT):
    root = Path(root)
    result = {}
    for variant, (name, archive, patch) in CONTROLS.items():
        original = committed_bytes(name, root)
        current = (root / name).read_bytes()
        compatible = (root / archive).read_bytes()
        api = validate_projection(original, current, compatible, variant)
        diff = exact_diff(original, compatible, name)
        assert (root / patch).read_bytes() == diff, 'Compatibility adapter diff changed'
        result[variant] = {'productionSource': name, 'archive': archive, 'exactDiff': patch,
            'pinnedBehaviorBaseCommit': BASE_COMMIT, 'originalCffSourceSha256': sha(original),
            'projectedSourceSha256': sha(compatible), 'apiShapeFragmentSha256': sha(api),
            'exactDiffSha256': sha(diff), 'sourceIsByteExactPinnedBaseCommit': False,
            'oldProductionBodiesUnmodified': True,
            'adapterBoundary': ('Only adds the optional unused onClickLabel parameter; the complete old delay/motion body is byte unchanged.'
                if variant == 'motion-only' else
                'Only inserts the exact current timeout API and its ThreadLocal field; complete old start/stop bodies and fields are byte unchanged. Old code never sets this new field, so its timeout remains the original requested value.')}
    binding = json.loads((root / BINDING).read_text())
    assert binding == {'schema': 1, 'baseCommit': BASE_COMMIT, 'controls': result,
        'limitations': 'Explicit source/API compatibility controls; compilation errors never count as behavioral regressions. Actual same-SHA Android XML must prove the specified defects.'}, 'Before API adapters are not exactly registered'
    return result
