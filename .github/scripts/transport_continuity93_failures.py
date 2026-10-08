#!/usr/bin/env python3
"""Read-only bounded diagnostics from exact current JUnit XML paths; never an acceptance gate."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from continuity93_source_scope import LAYER_FOLDER

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'HETU_JUNIT93_FAILURE '
LAYER = LAYER_FOLDER + '/inputs.json'
MAX_XML_BYTES = 8 * 1024 * 1024
MAX_TOTAL_XML_BYTES = 64 * 1024 * 1024
MAX_LOG_BYTES = 512 * 1024
MAX_STACK_BYTES = 32 * 1024
MAX_MESSAGE_BYTES = 4096
MAX_TYPE_BYTES = 512
CHUNK_CHARACTERS = 2048
SUPPLEMENTAL = ('CompactHomeDashboardTest', 'Ui92IntegrationTest', 'NodeSelectionContinuityTest')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def bounded(value, limit):
    raw = value.encode('utf-8')
    return {'text': raw[:limit].decode('utf-8', errors='ignore'), 'originalUtf8Bytes': len(raw),
            'sha256': sha(raw), 'truncated': len(raw) > limit}


def transport(root, expected, commit, output=print):
    """The caller supplies test identities, never arbitrary diagnostic file paths."""
    root = Path(root).resolve()
    names = sorted(expected)
    assert all(re.fullmatch(r'io\.github\.xgl34222220\.hetu\.[A-Za-z0-9_.$]+', name) for name in names)
    groups = [('current', 'android-app/app/build/test-results/testDebugUnitTest', names),
              ('supplemental', 'out/supplemental36/test-results',
               ['io.github.xgl34222220.hetu.' + name for name in SUPPLEMENTAL])]
    log_bytes = read_bytes = missing = failures = visited = omitted = 0
    total_truncated = False

    def emit(record, final=False):
        nonlocal log_bytes, omitted, total_truncated
        line = PREFIX + json.dumps({'commit': commit, **record}, ensure_ascii=True, separators=(',', ':'))
        size = len(line.encode('utf-8')) + 1
        if log_bytes + size > MAX_LOG_BYTES - (0 if final else 4096):
            omitted += 1
            total_truncated = True
            return False
        output(line)
        log_bytes += size
        return True

    emit({'record': 'limits', 'maxXmlBytes': MAX_XML_BYTES, 'maxTotalXmlBytes': MAX_TOTAL_XML_BYTES,
          'maxLogBytes': MAX_LOG_BYTES, 'maxStackBytesPerFailure': MAX_STACK_BYTES,
          'maxMessageBytes': MAX_MESSAGE_BYTES, 'allowlistedFiles': sum(len(x[2]) for x in groups),
          'scope': 'failure/error elements only; no system-out/system-err; diagnostics do not replace XML gates'})
    for group, folder, suites in groups:
        for name in suites:
            relative = folder + '/TEST-' + name + '.xml'
            path = root / relative
            base = {'group': group, 'path': relative, 'suite': name}
            if not path.exists():
                missing += 1
                emit({'record': 'file', **base, 'status': 'missing'})
                continue
            if path.is_symlink() or path.resolve() != path.absolute() or not path.is_file():
                emit({'record': 'file', **base, 'status': 'unsafe_path'})
                continue
            size = path.stat().st_size
            if size > MAX_XML_BYTES or read_bytes + size > MAX_TOTAL_XML_BYTES:
                emit({'record': 'file', **base, 'status': 'oversize', 'bytes': size})
                continue
            data = path.read_bytes()
            read_bytes += len(data)
            identity = {**base, 'bytes': len(data), 'xmlSha256': sha(data)}
            if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
                emit({'record': 'file', **identity, 'status': 'refused_xml_declaration'})
                continue
            try:
                suite = ET.fromstring(data)
            except ET.ParseError:
                emit({'record': 'file', **identity, 'status': 'invalid_xml'})
                continue
            if suite.tag != 'testsuite' or suite.get('name') != name:
                emit({'record': 'file', **identity, 'status': 'suite_identity_mismatch'})
                continue
            visited += 1
            cases = list(suite.findall('testcase'))
            bad = [(case, child) for case in cases for child in case if child.tag in ('failure', 'error')]
            emit({'record': 'file', **identity, 'status': 'parsed', 'testcaseElements': len(cases),
                  'failureOrErrorElements': len(bad), 'skippedElements': sum(case.find('skipped') is not None for case in cases)})
            for case, child in bad:
                failures += 1
                method = case.get('name', '')
                if case.get('classname') != name or (group == 'current' and method not in expected[name]['methods']):
                    emit({'record': 'case', **identity, 'status': 'testcase_identity_mismatch'})
                    continue
                stack = bounded(child.text or '', MAX_STACK_BYTES)
                chunks = [stack['text'][i:i + CHUNK_CHARACTERS] for i in range(0, len(stack['text']), CHUNK_CHARACTERS)]
                if not emit({'record': 'case', **identity, 'testcase': method, 'element': child.tag,
                             'type': bounded(child.get('type', ''), MAX_TYPE_BYTES),
                             'message': bounded(child.get('message', ''), MAX_MESSAGE_BYTES),
                             'stack': {key: value for key, value in stack.items() if key != 'text'},
                             'stackChunks': len(chunks)}):
                    continue
                for sequence, chunk in enumerate(chunks, 1):
                    if not emit({'record': 'stack-chunk', **base, 'testcase': method, 'sequence': sequence,
                                 'totalChunks': len(chunks), 'text': chunk}):
                        break
    emit({'record': 'summary', 'parsedFiles': visited, 'missingFiles': missing,
          'failureOrErrorElements': failures, 'readXmlBytes': read_bytes,
          'logBytesBeforeSummary': log_bytes, 'truncated': total_truncated, 'omittedRecords': omitted,
          'acceptance': 'not evaluated; unchanged original XML gates remain authoritative'}, final=True)


if __name__ == '__main__':
    layer = json.loads((ROOT / LAYER).read_text())
    observed = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
    if os.environ.get('GITHUB_SHA'):
        assert observed == os.environ['GITHUB_SHA'], 'Diagnostic checkout differs from workflow SHA'
    transport(ROOT, layer['finalUnitTestMethods'], observed)
