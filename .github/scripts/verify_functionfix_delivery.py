#!/usr/bin/env python3
"""Offline verification of every supplied V20.86 delivery artifact.

Require APK, transport manifest, UI, API35/36 and immutable source inputs. Count
actual testcase elements and compare every original 487 identity with the pinned
ec211b9 ZIP. Check the original wrapper chain, all history/source Git blobs,
23 APK payloads, part hashes, ABI, lint, authentication and fixture cleanup.
Run/head/attempt arguments are caller identities, not verified GitHub ownership.
Do not substitute these checks for a source-tar rehash, cryptographic apksigner,
real-phone networking or Google Play authentication. Never overwrite reports.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

CERT_SHA = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'
RUNTIME_INPUTS_SHA = 'c14b5eac12dd01471580944cdee56f183f226f92d6495e7c68ea9473c1cdfdbf'
PREVIOUS_ROOT_SHA = '6b59cc14c687f067ab942d0b4855fcdde6183a874275e1acda6105cbfff0a16b'
AUTOSTART_SHA = '3a1215f4e9093ae2298803020d697da0c14c35ee5cf28f84f2a81608093783f9'
BASELINE_ZIP_SHA = '1d4dd82a05cc3e8e2d38d209104deaa1fbe2f7a9daf547d475e152314f59ea18'
COUNTS = ('tests', 'failures', 'errors', 'skipped')
PREFIX = 'io.github.xgl34222220.hetu.'
INTAKE_SUITES = {PREFIX + 'tools.ToolsDiagIntakeTest': 3,
                 PREFIX + 'tools.ToolsDnsConsentIntakeTest': 5}
AUTH_SUITES = {PREFIX + name: count for name, count in {
    'ToastFeedbackTest': 6, 'ControllerAuthenticationTest': 11,
    'LegacyControllerCredentialTest': 3, 'ControllerReadStateTest': 6,
    'PanelControllerErrorTest': 4}.items()}
PDF_SUITES = {PREFIX + 'GoogleConnectionEvidenceTest': 8,
              PREFIX + 'tools.ToolsPdf85Test': 49}
AUTH_CHECK_NAMES = ['panel-controller-401-first-read', 'panel-controller-401-api-settings',
                    'panel-controller-401-retry', 'panel-controller-200-recovered',
                    'panel-controller-fixture-cleanup']


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while data := stream.read(1024 * 1024):
            digest.update(data)
    return digest.hexdigest()


class EvidenceZip:
    def __init__(self, path):
        self.path = Path(path)
        self.archive = zipfile.ZipFile(self.path)
        self.files = [name for name in self.archive.namelist() if not name.endswith('/')]
        if len(self.files) != len(set(self.files)):
            raise ValueError('Duplicate ZIP member names')
        if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts
               or '\\' in n for n in self.files):
            raise ValueError('Unsafe ZIP member path')

    def named(self, name):
        candidates = [n for n in self.files if PurePosixPath(n).name == name]
        if len(candidates) != 1:
            raise ValueError(f'Expected one {name}; found {candidates}')
        return candidates[0]

    def data(self, name):
        return self.archive.read(self.named(name))

    def json(self, name):
        return json.loads(self.data(name))

    def text(self, name):
        return self.data(name).decode('utf-8')


class Verification:
    def __init__(self, args):
        self.args, self.repo = args, args.repo.resolve()
        self.layer, self.runtime = None, None
        self.final_inputs, self.payloads = {}, {}
        self.checks, self.details, self.not_executed = [], {}, []

    def check(self, name, passed, detail=None):
        row = {'name': name, 'result': 'PASS' if passed else 'FAIL'}
        if detail is not None:
            row['detail'] = detail
        self.checks.append(row)
        return bool(passed)

    def stage(self, name, callback):
        try:
            callback()
        except Exception as error:
            self.check(name + '-read-or-schema', False, f'{type(error).__name__}: {error}')

    def module(self, name):
        directory = str(self.repo / '.github/scripts')
        if directory not in sys.path:
            sys.path.insert(0, directory)
        module = importlib.import_module(name)
        if not Path(module.__file__).resolve().is_relative_to(self.repo):
            raise ValueError('Repository guard resolved outside supplied repository')
        return module

    def git_blobs(self, commit, names):
        names = sorted(set(names))
        if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts
               or any(c in n for c in '\n\r\\\0') for n in names):
            raise ValueError('Unsafe Git blob input path')
        request = ''.join(f'{commit}:{name}\n' for name in names).encode()
        command = subprocess.run(['git', 'cat-file', '--batch'], cwd=self.repo, input=request,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        stream, result = io.BytesIO(command.stdout), {}
        for name in names:
            header = stream.readline().decode().strip().split()
            if len(header) != 3 or header[1] != 'blob':
                raise ValueError('Required source blob absent from expected commit: ' + name)
            size = int(header[2])
            result[name] = stream.read(size)
            if len(result[name]) != size or stream.read(1) != b'\n':
                raise ValueError('Truncated Git blob: ' + name)
        if stream.read(1):
            raise ValueError('Unexpected trailing Git batch bytes')
        return result

    def load_layer(self):
        if not __debug__:
            raise ValueError('Original assertion guards require Python without -O')
        inputs_path = self.args.function_inputs.resolve()
        if inputs_path != self.repo / 'updates/v2086-functionfix/inputs.json':
            raise ValueError('Function inputs must be the canonical repository layer')
        layer = json.loads(inputs_path.read_text())
        scope = self.module('functionfix_source_scope')
        previous = scope.previous_files(self.repo)
        scope.validate_layer(layer, previous, self.repo)
        scope.validate_new_tests(self.repo)
        self.check('function-layer-full-original-guard', True)
        self.check('function-layer-fixed-base-version-and-baseline', layer['schema'] == 1
                   and layer['baseCommit'] == '1dbe40cc4e312c230524092dfe32a2c6cfb5f25d'
                   and layer['baseAndroidAppTree'] == 'c5f2c1a9e070fe4c6e1940b5d6729ccc4810fdaa'
                   and layer['versionCode'] == 2086 and layer['versionName'] == '0.12.16-v20-fix'
                   and layer['baselineUnitTests'] == 487 and layer['baselineTestXmlFiles'] == 51)
        self.check('function-layer-dynamic-test-count-and-56XML',
                   layer['expectedUnitTests'] == 487 + sum(layer['newTestCounts'].values())
                   and layer['expectedTestXmlFiles'] == 51 + len(layer['newTestCounts']) == 56)
        self.check('function-layer-patch-actual-bytes', file_sha(inputs_path.with_name('runtime.patch')) == layer['patchSha256'])
        self.check('function-layer-all-history-actual-bytes', all(file_sha(self.repo / n) == digest
                   for n, digest in layer['historicalFilesSha256'].items()))
        self.check('function-layer-complete-historical-inventory',
                   layer['historicalFilesSha256'] == scope.HISTORICAL
                   and len(previous) == 379 and len(layer['externalPayloadFiles']) == 4)
        final = {**previous, **layer['changedOrAddedFiles']}
        self.check('function-layer-386-distinct-complete-inputs', len(final) == 386
                   and set(final) == set(layer['changedOrAddedFiles']) | set(layer['frozenFiles'])
                   and not set(layer['changedOrAddedFiles']) & set(layer['frozenFiles']))
        actual = scope.compilation_inputs(self.repo)
        missing = set(final) - actual
        self.check('checkout-input-inventory-386-except-four-restored-payloads',
                   not actual - set(final) and missing <= set(layer['externalPayloadFiles']),
                   {'present': len(actual), 'absentUnchangedNativePayloads': sorted(missing)})
        bad_local = [n for n in actual & set(final) if file_sha(self.repo / n) != final[n]]
        self.check('checkout-all-available-input-bytes-match-layer', not bad_local, bad_local)
        self.check('function-root-only-runtime-payload-change',
                   set(layer['runtimePayloadChanges']) == {'assets/hetu-root.sh'}
                   and layer['runtimePayloadChanges']['assets/hetu-root.sh']['before'] == PREVIOUS_ROOT_SHA
                   and layer['runtimePayloadChanges']['assets/hetu-root.sh']['after'] == layer['rootScriptSha256']
                   and layer['autostartScriptSha256'] == AUTOSTART_SHA and layer['runtimePayloadCount'] == 23)
        provenance = self.repo / 'UI92_RUNTIME146_INPUTS.json'
        if file_sha(provenance) != RUNTIME_INPUTS_SHA:
            raise ValueError('Original 146 payload provenance changed')
        original = json.loads(provenance.read_text())['original146_payload']
        if len(original) != 22 or 'assets/hetu-root.sh' not in original:
            raise ValueError('Original runtime inventory is incomplete')
        payloads = {**original, 'assets/hetu-root.sh': layer['rootScriptSha256'],
                    'assets/hetu-autostart.sh': AUTOSTART_SHA}
        self.check('fixed-original-payload-provenance-and23-current-payloads', len(payloads) == 23
                   and layer['originalRuntimeInputsSha256'] == RUNTIME_INPUTS_SHA)
        relative_inputs = str(inputs_path.relative_to(self.repo))
        relative_patch = str(inputs_path.with_name('runtime.patch').relative_to(self.repo))
        names = (set(final) - set(layer['externalPayloadFiles'])) | set(layer['historicalFilesSha256'])
        names |= {relative_inputs, relative_patch, '.github/scripts/functionfix_test_plan.json', 'UI92_RUNTIME146_INPUTS.json'}
        blobs = self.git_blobs(self.args.commit, names)
        expected = {**final, **layer['historicalFilesSha256'], relative_inputs: file_sha(inputs_path),
                    relative_patch: layer['patchSha256'],
                    '.github/scripts/functionfix_test_plan.json': layer['testPlanSha256'],
                    'UI92_RUNTIME146_INPUTS.json': RUNTIME_INPUTS_SHA}
        bad_git = [n for n, data in blobs.items() if sha(data) != expected[n]]
        self.check('expected-commit-all-source-and-layer-blob-digests', not bad_git, bad_git)
        transforms = [scope.BUILD_FILE, scope.REVISION_FILE, scope.CONTRACT_FILE,
                      scope.ROOT_SCRIPT, scope.PACKAGE + 'RootProxyManager.java']
        before = self.git_blobs(layer['baseCommit'], transforms)
        with tempfile.TemporaryDirectory(prefix='function-delivery-before-') as directory:
            for name, data in before.items():
                path = Path(directory) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            scope.validate_source_transforms(Path(directory), self.repo)
        self.check('original-runtime-contract-only-revision-and-start-protocol-preserved', True)
        self.layer, self.final_inputs, self.payloads = layer, final, payloads
        self.details['functionInputs'] = {'path': str(inputs_path), 'sha256': file_sha(inputs_path),
            'expectedUnitTests': layer['expectedUnitTests'], 'expectedTestXmlFiles': layer['expectedTestXmlFiles'],
            'changedOrAddedFiles': len(layer['changedOrAddedFiles']), 'frozenInputs': len(layer['frozenFiles']),
            'completeInputs': len(final), 'historicalFilesSha256': layer['historicalFilesSha256']}

    def require_layer(self):
        if self.layer is None:
            raise ValueError('Function layer guard failed or inputs were not supplied')
        return self.layer

    @staticmethod
    def xml_cases(bundle):
        identities = set()
        for name in bundle.files:
            if PurePosixPath(name).name.startswith('TEST-') and name.endswith('.xml'):
                root = ET.fromstring(bundle.archive.read(name))
                for case in root.findall('.//testcase'):
                    identity = (root.attrib['name'], case.get('classname', ''), case.attrib['name'])
                    if identity in identities:
                        raise ValueError('Duplicate testcase identity: ' + str(identity))
                    identities.add(identity)
        return identities

    def raw_tests(self, ui):
        layer = self.require_layer()
        names = [n for n in ui.files if PurePosixPath(n).name.startswith('TEST-') and n.endswith('.xml')]
        self.check('actual-XML-files-match-function-inputs', len(names) == layer['expectedTestXmlFiles'], len(names))
        suites, totals, identities = {}, Counter(), set()
        with tempfile.TemporaryDirectory(prefix='function-delivery-xml-') as directory:
            for name in sorted(names):
                raw = ui.archive.read(name)
                root = ET.fromstring(raw)
                if root.tag != 'testsuite':
                    raise ValueError('Unexpected test XML root: ' + root.tag)
                suite = root.attrib['name']
                if suite in suites:
                    raise ValueError('Duplicate test suite: ' + suite)
                declared = {key: int(root.get(key, '0')) for key in COUNTS}
                cases, actual = root.findall('.//testcase'), Counter()
                actual['tests'] = len(cases)
                for case in cases:
                    identity = (suite, case.get('classname', ''), case.attrib['name'])
                    if identity in identities:
                        raise ValueError('Duplicate testcase: ' + str(identity))
                    identities.add(identity)
                    for key, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
                        actual[key] += len(case.findall(tag))
                observed = {key: actual[key] for key in COUNTS}
                self.check('actual-XML-' + suite, declared == observed and actual['tests'] > 0
                           and all(actual[key] == 0 for key in COUNTS[1:]),
                           {'declared': declared, 'actualTestcases': observed})
                if suite in layer['newTestCounts']:
                    self.check('new-suite-actual-case-class-' + suite,
                               all(case.get('classname') == suite for case in cases))
                suites[suite] = observed
                totals.update(observed)
                (Path(directory) / PurePosixPath(name).name).write_bytes(raw)
            try:
                wrapper = self.module('verify_functionfix_test_results').verify(Path(directory), layer)
            except Exception as error:
                wrapper = None
                self.check('unchanged-original487-wrapper-chain-pass', False,
                           f'{type(error).__name__}: {error}')
            else:
                self.check('unchanged-original487-wrapper-chain-pass', True)
        self.check('actual-function-testcases-zero-failure', dict(totals) ==
                   dict(tests=layer['expectedUnitTests'], failures=0, errors=0, skipped=0), dict(totals))
        for name, count in {**INTAKE_SUITES, **AUTH_SUITES, **PDF_SUITES, **layer['newTestCounts']}.items():
            self.check('required-suite-' + name, suites.get(name) ==
                       dict(tests=count, failures=0, errors=0, skipped=0), suites.get(name))
        baseline_suites = {name: count for name, count in suites.items() if name not in layer['newTestCounts']}
        self.check('retained-original51XML-and487-actual-testcases', len(baseline_suites) == 51
                   and sum(count['tests'] for count in baseline_suites.values()) == 487)
        baseline = EvidenceZip(self.args.baseline_ui_zip)
        self.check('pinned-ec211b9-original-baseline-ZIP-byte-digest', file_sha(self.args.baseline_ui_zip) == BASELINE_ZIP_SHA)
        originals = self.xml_cases(baseline)
        actual_originals = {identity for identity in identities if identity[0] not in layer['newTestCounts']}
        self.check('every-original487-case-identity-retained', len(originals) == 487 and actual_originals == originals,
                   {'original': len(originals), 'actualOriginal': len(actual_originals),
                    'missing': sorted(originals - actual_originals), 'unexpected': sorted(actual_originals - originals)})
        self.details['unitTests'] = {'xmlFiles': len(names), 'actualTestcases': dict(totals), 'suites': suites,
                                   'originalCaseIdentitiesChecked': len(originals), 'originalWrapperResult': wrapper}
        if wrapper is None:
            self.check('function-report-not-accepted-after-wrapper-failure', False)
        else:
            self.check('function-report-equals-full-original-wrapper-result', ui.json('functionfix-tests.json') == wrapper)

    def source(self, ui):
        function = self.require_layer()
        def inputs(path):
            return json.loads((self.repo / path).read_text())
        base = inputs('updates/v2082-new-ui/inputs.json')
        presentation = inputs('updates/v2083-ui-polish/inputs.json')
        auth = inputs('updates/v2084-controller-auth/inputs.json')
        pdf = inputs('updates/v2085-pdf-tools-settings/inputs.json')
        intake = inputs('updates/v2085-tools-intake/inputs.json')
        final = inputs('updates/v2085-pdf-final-geometry/inputs.json')
        proof = ui.json('effective-source-proof.json')
        top = {'baseCommit': base['baseCommit'], 'baseRun': base['baseRun'], 'archiveSha256': base['archiveSha256'],
               'integrationPatchSha256': base['patchSha256'], 'baselineFiles': len(base['baselineFiles']),
               'changedOrAddedFiles': len(base['integratedFiles']), 'checkoutMatchesGeneratedSource': True,
               'historicPatchesAppliedToCheckout': False}
        self.check('effective-source-original-integration-proof', all(proof.get(k) == v for k, v in top.items()))
        expected = {
            'presentationLayer': {'baseCommit': presentation['baseCommit'], 'baseRun': presentation['baseRun'],
                'patchSha256': presentation['patchSha256'], 'changedOrAddedFiles': len(presentation['changedOrAddedFiles']),
                'protectedRuntimeUnchanged': True},
            'controllerAuthenticationLayer': {'baseCommit': auth['baseCommit'], 'baseRun': auth['baseRun'],
                'baseAndroidAppTree': auth['baseAndroidAppTree'], 'patchSha256': auth['patchSha256'],
                'changedOrAddedFiles': len(auth['changedOrAddedFiles']), 'runtimePayloadsUnchanged': True,
                'dependenciesUnchanged': True, 'manifestAndPermissionsUnchanged': True, 'unchangedOutsideAuthScope': True,
                'baselineUnitTests': auth['baselineUnitTests'], 'expectedUnitTests': auth['expectedUnitTests'],
                'newTestCounts': auth['newTestCounts']},
            'pdfReferenceLayer': {'patchSha256': pdf['patchSha256'], 'frozenInputs': len(pdf['frozenFiles']),
                'homeAndPanelUnchanged': True, 'runtimePayloadsUnchanged': True,
                'manifestDependenciesAndSigningUnchanged': True, 'expectedUnitTests': 479},
            'toolsIntakeLayer': {'baseCommit': intake['baseCommit'], 'baseAndroidAppTree': intake['baseAndroidAppTree'],
                'patchSha256': intake['patchSha256'], 'changedOrAddedFiles': len(intake['changedOrAddedFiles']),
                'frozenInputs': len(intake['frozenFiles']), 'homeAndPanelUnchanged': True, 'runtimePayloadsUnchanged': True,
                'manifestDependenciesAndSigningUnchanged': True, 'baselineUnitTests': intake['baselineUnitTests'],
                'expectedUnitTests': intake['expectedUnitTests'], 'newTestCounts': intake['newTestCounts']},
            'finalPdfGeometryLayer': {'baseCommit': final['baseCommit'], 'patchSha256': final['patchSha256'],
                'changedOrAddedFiles': 4, 'frozenInputs': 375, 'expectedUnitTests': 487,
                'exactWholeFilePresentationTransforms': True, 'priorLayersUnchanged': True},
            'functionFixLayer': {'baseCommit': function['baseCommit'], 'patchSha256': function['patchSha256'],
                'changedOrAddedFiles': len(function['changedOrAddedFiles']), 'frozenInputs': len(function['frozenFiles']),
                'baselineUnitTests': 487, 'expectedUnitTests': function['expectedUnitTests'],
                'expectedTestXmlFiles': function['expectedTestXmlFiles'], 'newTestCounts': function['newTestCounts'],
                'allOriginal487TestsPreserved': True, 'rootScriptSha256': function['rootScriptSha256'], 'runtimePayloadCount': 23},
        }
        for name, required in expected.items():
            self.check('effective-source-' + name, proof.get(name) == required, proof.get(name))
        self.check('effective-source-proof-no-missing-or-extra-layers', set(proof) == set(top) | set(expected))
        concept = ui.json('concept-173-current.json')
        self.check('consolidation-173-and-current-test-count-zero-failure', concept['schema'] == 2
                   and concept['reference_pages'] == 173
                   and concept['test_counts'] == dict(tests=function['expectedUnitTests'], failures=0, errors=0, skipped=0)
                   and len(concept['pages']) == len({p['reference'] for p in concept['pages']}) == 173)
        self.check('consolidation-retains-visual-review-limits', all(p['pixel_exact_pass'] is False
                   and p['visual_acceptance'] == 'review_in_progress' for p in concept['pages']))
        reported = {n: digest for n, digest in self.final_inputs.items() if n.startswith('android-app/app/src/')
                    and PurePosixPath(n).suffix in {'.java', '.kt', '.xml', '.sh', '.png', '.webp'}}
        bad = [n for n, digest in reported.items() if concept['source_sha256'].get(n) != digest]
        self.check('artifact-all-reported-current-and-frozen-source-digests', not bad,
                   {'expectedReportedInputs': len(reported), 'mismatches': bad})
        self.details['sourceProof'] = proof
        self.not_executed.append('Rehash all 386 effective-source tarball members: no source-tar argument supplied; source guards and reported hashes are not a tarball rehash.')

    def payload_report(self, ui):
        self.require_layer()
        runtime = ui.json('packaged-runtime.json')
        self.runtime = runtime
        self.check('runtime-report-23-exact-pinned-payloads', runtime['expected'] == self.payloads
                   and len(runtime['expected']) == 23 and runtime['originalPayloadCount'] == 22
                   and runtime['editedPayloads'] == ['assets/hetu-root.sh']
                   and runtime['addedPayloads'] == ['assets/hetu-autostart.sh'] and runtime['apkVerified'] is True)
        self.check('runtime-report-apk-sha-format', bool(re.fullmatch('[0-9a-f]{64}', runtime['apkSha256'])))
        abi = ui.json('materialkolor-abi.json')
        self.check('ABI-687-zero-missing', abi['constructorReferences'] == 687
                   and abi['missingConstructors'] == [] and abi['compatible'] is True
                   and abi['expectedIncompatibleBaseline'] is False and abi['dexCount'] > 0)
        self.check('ABI-runtime-apk-sha-consistent', abi['apkSha256'] == runtime['apkSha256'])
        self.details['payloadReport'], self.details['ABI'] = runtime, abi

    def lint(self, ui):
        root = ET.fromstring(ui.data('lint-results-debug.xml'))
        if root.tag != 'issues' or not str(root.get('format', '')).isdigit():
            raise ValueError('Unexpected Android lint XML root or format')
        counts = Counter(issue.get('severity') for issue in root.findall('.//issue'))
        self.check('lint-zero-error-fatal', counts['Error'] == 0 and counts['Fatal'] == 0, dict(counts))
        self.details['lintSeverities'] = dict(counts)

    def apk(self, manifest_path, apk_path):
        self.require_layer()
        bundle = EvidenceZip(manifest_path)
        manifest = bundle.json('manifest.json')
        self.check('APK-manifest-schema', manifest['schema'] == 1
                   and manifest['apkName'] == 'Hetu-0.12.16-v20-fix.apk'
                   and manifest['chunkBytes'] == 24 * 1024 * 1024 and 1 <= manifest['partCount'] <= 6)
        parts = manifest['parts']
        self.check('APK-manifest-parts-complete', len(parts) == manifest['partCount']
                   and [p['index'] for p in parts] == list(range(len(parts)))
                   and [p['name'] for p in parts] == [f'part{i:02d}.bin' for i in range(len(parts))]
                   and all(0 < p['bytes'] <= manifest['chunkBytes']
                           and re.fullmatch('[0-9a-f]{64}', p['sha256']) for p in parts)
                   and all(p['bytes'] == manifest['chunkBytes'] for p in parts[:-1])
                   and manifest['partCount'] == (manifest['apkBytes'] + manifest['chunkBytes'] - 1) // manifest['chunkBytes']
                   and sum(p['bytes'] for p in parts) == manifest['apkBytes'])
        metadata = bundle.text('apk-metadata.txt')
        self.check('APK-metadata-package-and-version', bool(re.search(
            r"package: name='io.github.xgl34222220.hetu'[^\n]*versionCode='2086'[^\n]*versionName='0.12.16-v20-fix'", metadata)))
        signature = bundle.text('apk-signature.txt')
        certs = re.findall(r'^(?:Signer #\d+|V2 Signer:?) certificate SHA-256 digest: ([0-9a-fA-F]+)$', signature, re.MULTILINE)
        self.check('APK-fixed-certificate-report', [digest.lower() for digest in certs] == [CERT_SHA],
                   {'reportedCertificateSha256': [digest.lower() for digest in certs],
                    'acceptedReportForms': ['Signer #N', 'V2 Signer:']})
        if self.runtime is None:
            raise ValueError('Current UI packaged-runtime report missing; cannot bind APK hash')
        self.check('APK-manifest-runtime-sha-consistent', manifest['apkSha256'] == self.runtime['apkSha256'])
        digest, size = file_sha(apk_path), Path(apk_path).stat().st_size
        self.check('actual-whole-APK-manifest-byte-verification', digest == manifest['apkSha256']
                   and size == manifest['apkBytes'], {'sha256': digest, 'bytes': size})
        with Path(apk_path).open('rb') as stream:
            for part in parts:
                data = stream.read(part['bytes'])
                self.check('actual-APK-part-' + part['name'], len(data) == part['bytes'] and sha(data) == part['sha256'])
            self.check('actual-APK-no-trailing-bytes', not stream.read(1))
        self.check('actual-APK-runtime-report-sha', digest == self.runtime['apkSha256'])
        with zipfile.ZipFile(apk_path) as archive:
            names = archive.namelist()
            self.check('actual-APK-no-duplicate-members', len(names) == len(set(names)))
            for name, expected in self.payloads.items():
                self.check('actual-payload-' + name, sha(archive.read(name)) == expected)
        self.details['APKManifest'] = manifest
        self.details['actualAPK'] = {'path': str(Path(apk_path).resolve()), 'sha256': digest,
                                    'bytes': size, 'pinnedPayloads': 23}

    def smoke(self, path, api):
        bundle = EvidenceZip(path)
        results = bundle.json('results.json')
        checks = results['checks']
        self.check(f'api{api}-64-equals59plus5-and21-palettes', results['passed'] == 64
                   and results['legacyChecksPassed'] == 59 and results['paletteCasesPassed'] == 21
                   and len(checks) == len({c['name'] for c in checks}) == 64
                   and all(c['result'] == 'passed' for c in checks)
                   and sum(c['name'].startswith('custom-palette-') for c in checks) == 21)
        self.check(f'api{api}-fixture-and-zero-root-mutations', results['apiLevel'] == api
                   and results['fixtureOnly'] is True and results['rootMutationActions'] == 0
                   and results['rootSetupDenialAttempts'] == 1)
        auth = bundle.json('panel-controller-auth-fixture.json')
        new_checks = results['newPanelAuthChecks']
        self.check(f'api{api}-five-auth-checks-match', new_checks == checks[59:] == auth['newChecks']
                   and [c['name'] for c in new_checks] == AUTH_CHECK_NAMES)
        self.check(f'api{api}-auth-fixture-pass', auth['result'] == 'PASS' and auth['fixtureOnly'] is True
                   and auth['syntheticCachedRuntimeHint'] is True and auth['rootMutationActions'] == 0
                   and auth['controllerHost'] == '127.0.0.1' and auth['actualBearerAuthorizationVerified'] is True
                   and auth['observedNativeCounts'] == {'策略': 12, '规则': 3, '当前连接': 18})
        self.check(f'api{api}-auth-no-health-or-exclusive-retry-claim', all(auth[key] is False for key in
                   ['rootHealthEvidence', 'toast401Claimed', 'exclusiveRetryRecoveryClaimed', 'continuityServiceObserved']))
        self.check(f'api{api}-retry-ready-before-200', auth['retryReadyBeforeFixture200']['enabled'] is True
                   and auth['retryReadyBeforeFixture200']['observedAtMonotonic']
                   <= auth['fixture200AtMonotonic'] <= auth['first200AtMonotonic'])
        self.check(f'api{api}-preference-and-reverse-restored', auth['originalPreferencesRestored'] is True
                   and auth['adbReverseRestored'] is True
                   and auth['originalPreferencesSha256'] == auth['restoredPreferencesSha256']
                   and bool(re.fullmatch('[0-9a-f]{64}', auth['originalPreferencesSha256'])))
        requests = bundle.json('panel-controller-fixture-requests.json')
        successes = [r for r in requests if r['status'] == 200]
        paths = {r['path'] for r in successes if r['timeMonotonic'] >= auth['fixture200AtMonotonic']}
        self.check(f'api{api}-actual401-after-retry', any(r['method'] == 'GET' and r['status'] == 401 for r in requests)
                   and any(r['status'] == 401 and r['timeMonotonic'] >= auth['retryWhile401']['timeMonotonic'] for r in requests))
        self.check(f'api{api}-actual-authorized200-controller-paths', bool(successes)
                   and all(r['valid_fixture_authorization'] is True for r in successes)
                   and {'/configs', '/proxies', '/providers/proxies', '/connections', '/rules', '/version'} <= paths,
                   sorted(paths))
        target = bundle.json('panel-target-preflight.json')
        self.check(f'api{api}-owned-nonroot-AOSP-target', auth['target'] == target and target['apiLevel'] == api
                   and target['avd'] == 'hetu-smoke' and target['serial'] == 'emulator-5554'
                   and target['nonRootShell'] is True and bool(re.fullmatch('[0-9a-f]{32}', target['ownedSession']))
                   and all(type(target[key]) is int and target[key] > 0
                           for key in ['runnerPid', 'launcherPid', 'launcherStartTicks', 'supervisorPid'])
                   and bool(target['emulatorPids']) and bool(re.fullmatch('[0-9a-f]{64}', target['avdConfigSha256'])))
        runner = bundle.json('runner-lifecycle.json')
        self.check(f'api{api}-runner-cleanup-and-KVM-restored', runner['result'] == 'PASS' and runner['smoke_exit'] == 0
                   and runner['launcher_reaped'] is True and runner['kvm_before'] == runner['kvm_after']
                   and set(runner['kvm_before']) == {'uid', 'gid', 'mode', 'rdev'})
        cleanup = bundle.json('emulator-cleanup.json')
        self.check(f'api{api}-owned-emulator-reaped', cleanup['emulator_started'] is True
                   and cleanup['emulator_reaped'] is True and type(cleanup['pid']) is int
                   and cleanup['pid'] in target['emulatorPids'] and type(cleanup['returncode']) is int)
        forwards = bundle.json('webview-forward-cleanup.json')
        self.check(f'api{api}-webview-forward-cleanup', isinstance(forwards, list) and len(forwards) >= 2
                   and len({f['local_port'] for f in forwards}) == len(forwards)
                   and all(f['removed'] is True and type(f['app_pid']) is int and f['app_pid'] > 0
                           and type(f['local_port']) is int and 1 <= f['local_port'] <= 65535 for f in forwards))
        self.check(f'api{api}-installed-candidate-version', 'Success' in bundle.text('install.txt')
                   and 'versionCode=2086' in bundle.text('version.txt')
                   and 'versionName=0.12.16-v20-fix' in bundle.text('version.txt'))
        frames = bundle.json('native-webview-frame-hashes.json')
        expected_names = {f'reference-03B-{number:03d}-native-{state}.png' for number, state in
                          [(40, 'overview'), (41, 'groups'), (42, 'connections'),
                           (43, 'node-dialog'), (44, 'switch-failure')]}
        self.check(f'api{api}-five-distinct-native-webview-frame-bytes', set(frames) == expected_names
                   and len(set(frames.values())) == 5
                   and all(sha(bundle.data(name)) == digest for name, digest in frames.items()))
        self.details[f'api{api}'] = {'checksPassed': results['passed'], 'paletteCasesPassed': results['paletteCasesPassed'],
            'authorizationPaths200': sorted(paths), 'requests401': sum(r['status'] == 401 for r in requests),
            'requests200': len(successes), 'target': target, 'runner': runner, 'cleanup': cleanup, 'frameHashes': frames}


def write_report(requested, report):
    requested.parent.mkdir(parents=True, exist_ok=True)
    path = requested
    for index in range(100):
        try:
            with path.open('x', encoding='utf-8') as stream:
                stream.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            return path
        except FileExistsError:
            suffix = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
            path = requested.with_name(requested.stem + '-' + suffix + '-' + str(index) + requested.suffix)
    raise ValueError('Cannot allocate a new report without overwriting earlier evidence')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--attempt', type=int, required=True)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--function-inputs', type=Path, required=True)
    parser.add_argument('--ui-zip', type=Path, required=True)
    parser.add_argument('--apk-manifest-zip', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--api35-zip', type=Path, required=True)
    parser.add_argument('--api36-zip', type=Path, required=True)
    parser.add_argument('--baseline-ui-zip', type=Path,
                        default=Path(__file__).resolve().parents[2].parent / 'function-verification/baseline-ec211b9-ui.zip',
                        help='Pinned ec211b9 original 487-case UI ZIP; compare every original testcase identity.')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    verification = Verification(args)
    verification.check('expected-workflow-identity-argument-format', args.run_id > 0 and args.attempt >= 1
                       and bool(re.fullmatch('[0-9a-f]{40}', args.commit)))
    artifacts = {}
    for key in ('function_inputs', 'ui_zip', 'apk_manifest_zip', 'apk', 'api35_zip', 'api36_zip', 'baseline_ui_zip'):
        path = getattr(args, key)
        try:
            artifacts[key] = {'path': str(path.resolve()), 'bytes': path.stat().st_size, 'sha256': file_sha(path)}
        except Exception as error:
            verification.check(key + '-required-input-readable', False, str(error))
    verification.stage('function-layer-and-commit-source-guards', verification.load_layer)
    try:
        ui = EvidenceZip(args.ui_zip)
        for name, fn in [('unit-tests', verification.raw_tests), ('source-proof', verification.source),
                         ('payload-ABI-report', verification.payload_report), ('lint', verification.lint)]:
            verification.stage(name, lambda fn=fn: fn(ui))
    except Exception as error:
        verification.check('UI-artifact-readable', False, str(error))
    verification.stage('APK', lambda: verification.apk(args.apk_manifest_zip, args.apk))
    for api in (35, 36):
        path = getattr(args, f'api{api}_zip')
        verification.stage(f'api{api}', lambda api=api, path=path: verification.smoke(path, api))
    verification.not_executed.extend([
        'Cryptographic apksigner verification on actual APK is not run by this Python verifier; fixed certificate report is checked, not substituted for cryptographic verification.',
        'GitHub provider/run/head/attempt ownership is not verified by this offline script. Expected identity arguments must be bound to actual CI API responses by the caller.',
        'Real-phone Root networking, Google Play account authentication, long-running stability and exhaustive bug absence are not verified by fixture artifacts.'])
    failed = [row for row in verification.checks if row['result'] != 'PASS']
    report = {'schema': 2, 'expectedRunId': args.run_id, 'expectedRunAttempt': args.attempt,
              'expectedSourceCommit': args.commit, 'checkedAtUtc': datetime.now(timezone.utc).isoformat(),
              'result': 'FAIL' if failed else 'PASS',
              'scope': 'independent byte, testcase identity, immutable source guard and fixture checks of every required supplied artifact',
              'artifactProviderIdentityVerified': False, 'artifacts': artifacts,
              'passedEvidenceChecks': len(verification.checks) - len(failed), 'failedEvidenceChecks': failed,
              'checks': verification.checks, 'details': verification.details, 'notExecuted': verification.not_executed}
    output = write_report(args.output, report)
    print(json.dumps({'result': report['result'], 'passed': report['passedEvidenceChecks'], 'failed': len(failed),
                      'output': str(output.resolve()), 'failedChecks': failed}, ensure_ascii=False))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
