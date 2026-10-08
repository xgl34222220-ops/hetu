#!/usr/bin/env python3
"""Reproduce the integration from the verified effective source, then compare checkout bytes.

The historical patch chain is reversed only in a temporary fixture for before/after
regressions. It is never replayed against a checkout containing the new UI.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from ui_source_scope import BASE_COMMIT, BASE_RUN, PREVIOUS_INPUTS_SHA256, validate_scope, validate_version_only
from auth_source_scope import (validate_layer as validate_auth_layer,
                               HISTORY_FIXTURE, validate_history_fixture_only,
                               SAFETY_FIXTURE, validate_safety_diagnostics_only,
                               validate_version_only as validate_auth_version_only,
                               PREVIOUS_INPUTS_SHA256 as AUTH_PREVIOUS_INPUTS_SHA256,
                               PREVIOUS_PATCH_SHA256 as AUTH_PREVIOUS_PATCH_SHA256)

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / 'updates/v2082-new-ui/inputs.json'
LAYER_INPUTS = ROOT / 'updates/v2083-ui-polish/inputs.json'
AUTH_INPUTS = ROOT / 'updates/v2084-controller-auth/inputs.json'

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def apply(patch, cwd, reverse=False):
    flags = ['-R'] if reverse else []
    subprocess.run(['git', 'apply', '--check', *flags, str(patch)], cwd=cwd, check=True)
    subprocess.run(['git', 'apply', *flags, str(patch)], cwd=cwd, check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    assert digest(INPUTS) == PREVIOUS_INPUTS_SHA256, 'Verified V20.82 inputs changed'
    spec = json.loads(INPUTS.read_text())
    assert digest(args.archive) == spec['archiveSha256'], 'Wrong effective source archive'
    patch = ROOT / 'updates/v2082-new-ui/integration.patch'
    assert digest(patch) == spec['patchSha256'], 'Integration patch changed without input update'
    out = ROOT / 'out/verification'
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='hetu-newui-') as directory:
        work = Path(directory)
        with tarfile.open(args.archive) as archive:
            archive.extractall(work, filter='data')
        for name, sha in spec['baselineFiles'].items():
            assert digest(work / name) == sha, 'Baseline mismatch: ' + name
        # Capture precise historic production sources for the existing fault fixtures.
        fixture = work / 'historical'
        fixture.mkdir()
        shutil.copytree(work / 'android-app', fixture / 'android-app')
        def capture(name, source):
            shutil.copyfile(fixture / ('android-app/app/src/main/java/io/github/xgl34222220/hetu/' + source), out / name)
        for version, folder in [(2081, 'v2081-trace-symbols'), (2080, 'v2080-journal-bounds'),
                                (2079, 'v2079-recovery-backpressure'), (2078, 'v2078-network-traces'),
                                (2077, 'v2077-network-recovery'), (2076, 'v2076-session-filter')]:
            old = ROOT / 'updates' / folder / 'runtime.patch'
            assert digest(old) == spec['historicalPatches'][str(version)]
            apply(old, fixture, reverse=True)
            if version == 2081: capture('v2080-network-journal.java', 'ProxyNetworkJournal.java')
            if version == 2080: capture('v2079-network-journal.java', 'ProxyNetworkJournal.java')
            if version == 2079: capture('v2078-network-service.java', 'ProxyNetworkMatchService.java')
            if version == 2076: capture('v2075-adblock.java', 'ProxyAdblockRules.java')
        apply(patch, work)
        for name, sha in spec['integratedFiles'].items():
            assert digest(work / name) == sha, 'Generated source mismatch: ' + name
        final_files = {**spec['baselineFiles'], **spec['integratedFiles']}
        layer_report = None
        if LAYER_INPUTS.exists():
            layer = json.loads(LAYER_INPUTS.read_text())
            assert layer['schema'] == 1
            assert layer['baseCommit'] == BASE_COMMIT and layer['baseRun'] == BASE_RUN
            assert layer['previousInputsSha256'] == digest(INPUTS), 'V20.82 inputs changed'
            assert layer['previousPatchSha256'] == spec['patchSha256']
            assert layer['versionCode'] == 2083 and layer['versionName'] == '0.12.13-v20-ui'
            changes = layer['changedOrAddedFiles']
            validate_scope(changes)
            ui_patch = LAYER_INPUTS.with_name('ui.patch')
            assert digest(ui_patch) == layer['patchSha256'], 'Presentation patch changed without input update'
            previous_gradle = (work / 'android-app/app/build.gradle.kts').read_text()
            apply(ui_patch, work)
            validate_version_only(previous_gradle, (work / 'android-app/app/build.gradle.kts').read_text())
            for name, sha in changes.items():
                assert digest(work / name) == sha, 'Presentation source mismatch: ' + name
                assert sha != final_files.get(name), 'Unchanged input recorded as a delta: ' + name
            final_files.update(changes)
            layer_report = {'baseCommit': layer['baseCommit'], 'baseRun': layer['baseRun'],
                            'patchSha256': layer['patchSha256'], 'changedOrAddedFiles': len(changes),
                            'protectedRuntimeUnchanged': True}
        auth_report = None
        if AUTH_INPUTS.exists():
            auth = json.loads(AUTH_INPUTS.read_text())
            validate_auth_layer(auth)
            assert digest(LAYER_INPUTS) == AUTH_PREVIOUS_INPUTS_SHA256, 'Verified V20.83 inputs changed'
            assert digest(LAYER_INPUTS.with_name('ui.patch')) == AUTH_PREVIOUS_PATCH_SHA256
            # Establish the precise V20.83 input before the separate runtime layer.
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'Authentication baseline mismatch: ' + name
            for source, name in [('MihomoControllerClient.java', 'v2083-controller-client.java'),
                                 ('LegacyAppMigrator.java', 'v2083-legacy-migrator.java')]:
                shutil.copyfile(work / ('android-app/app/src/main/java/io/github/xgl34222220/hetu/' + source), out / name)
            runtime_patch = AUTH_INPUTS.with_name('runtime.patch')
            assert digest(runtime_patch) == auth['patchSha256'], 'Authentication patch changed without input update'
            previous_gradle = (work / 'android-app/app/build.gradle.kts').read_text()
            previous_history_fixture = (work / HISTORY_FIXTURE).read_text()
            previous_safety_fixture = (work / SAFETY_FIXTURE).read_text()
            apply(runtime_patch, work)
            validate_auth_version_only(previous_gradle, (work / 'android-app/app/build.gradle.kts').read_text())
            if HISTORY_FIXTURE in auth['changedOrAddedFiles']:
                validate_history_fixture_only(previous_history_fixture, (work / HISTORY_FIXTURE).read_text())
            if SAFETY_FIXTURE in auth['changedOrAddedFiles']:
                validate_safety_diagnostics_only(previous_safety_fixture, (work / SAFETY_FIXTURE).read_text())
            for name, sha in auth['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'Authentication source mismatch: ' + name
                assert sha != final_files.get(name), 'Unchanged input recorded as an authentication delta: ' + name
            final_files.update(auth['changedOrAddedFiles'])
            auth_report = {'baseCommit': auth['baseCommit'], 'baseRun': auth['baseRun'],
                           'baseAndroidAppTree': auth['baseAndroidAppTree'],
                           'patchSha256': auth['patchSha256'],
                           'changedOrAddedFiles': len(auth['changedOrAddedFiles']),
                           'runtimePayloadsUnchanged': True, 'dependenciesUnchanged': True,
                           'manifestAndPermissionsUnchanged': True,
                           'unchangedOutsideAuthScope': True,
                           'baselineUnitTests': auth['baselineUnitTests'],
                           'expectedUnitTests': auth['expectedUnitTests'],
                           'newTestCounts': auth['newTestCounts']}
        pdf_report = None
        pdf_inputs = ROOT / 'updates/v2085-pdf-tools-settings/inputs.json'
        if pdf_inputs.exists():
            from pdf85_source_scope import validate_layer as validate_pdf_layer, validate_changes, validate_shared_page, validate_webui_reference_header, validate_native_settings_typography, validate_settings_picker_expectations, PICKER_EXPECTATIONS_FILE
            pdf = json.loads(pdf_inputs.read_text())
            validate_pdf_layer(pdf)
            assert digest(AUTH_INPUTS)==pdf['previousInputsSha256']
            assert digest(AUTH_INPUTS.with_name('runtime.patch'))==pdf['previousPatchSha256']
            pdf_patch=pdf_inputs.with_name('runtime.patch')
            assert digest(pdf_patch)==pdf['patchSha256']
            gradle_path='android-app/app/build.gradle.kts'
            root_path='android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java'
            before_gradle=(work/gradle_path).read_text()
            before_root=(work/root_path).read_text()
            shared_path='android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt'
            before_shared=(work/shared_path).read_text()
            webui_path='android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyLocalWebUiActivity.kt'
            before_webui=(work/webui_path).read_text()
            native_settings_path='android-app/app/src/main/java/io/github/xgl34222220/hetu/RootTproxyActivity.java'
            before_native_settings=(work/native_settings_path).read_text()
            before_picker_expectations=(work/PICKER_EXPECTATIONS_FILE).read_text()
            for name, sha in pdf['frozenFiles'].items():
                # Verify the precise prior layer here.  Checkout bytes are
                # compared after every additive layer has been applied, so
                # explicitly recorded intake deltas are verified, not skipped.
                assert digest(work/name)==sha, 'Frozen PDF baseline changed: '+name
            apply(pdf_patch,work)
            validate_shared_page(before_shared,(work/shared_path).read_text())
            validate_native_settings_typography(before_native_settings,(work/native_settings_path).read_text())
            validate_settings_picker_expectations(before_picker_expectations,(work/PICKER_EXPECTATIONS_FILE).read_text())
            validate_webui_reference_header(before_webui,(work/webui_path).read_text())
            validate_changes(before_gradle,(work/gradle_path).read_text(),before_root,(work/root_path).read_text())
            for name, sha in pdf['changedOrAddedFiles'].items():
                assert digest(work/name)==sha and sha!=final_files.get(name), 'PDF source mismatch: '+name
            final_files.update(pdf['changedOrAddedFiles'])
            pdf_report={'patchSha256':pdf['patchSha256'],'frozenInputs':len(pdf['frozenFiles']),
                        'homeAndPanelUnchanged':True,'runtimePayloadsUnchanged':True,
                        'manifestDependenciesAndSigningUnchanged':True,'expectedUnitTests':479}
        intake_report = None
        intake_inputs = ROOT / 'updates/v2085-tools-intake/inputs.json'
        from tools_intake_source_scope import (validate_layer as validate_intake_layer,
                                               validate_new_source_files,
                                               validate_checkout_matches_generated,
                                               verified_previous_files)
        if intake_inputs.exists():
            assert pdf_report is not None, 'Tools intake requires the complete PDF layer'
            previous_files = verified_previous_files(ROOT)
            assert final_files == previous_files, 'Intake did not follow the exact verified source layers'
            intake = json.loads(intake_inputs.read_text())
            validate_intake_layer(intake, previous_files)
            intake_patch = intake_inputs.with_name('runtime.patch')
            assert digest(intake_patch) == intake['patchSha256'], 'Tools intake patch changed without input update'
            for name, sha in previous_files.items():
                assert digest(work / name) == sha, 'Tools intake baseline mismatch: ' + name
            apply(intake_patch, work)
            for name, sha in intake['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'Tools intake source mismatch: ' + name
            for name, sha in intake['frozenFiles'].items():
                assert digest(work / name) == sha, 'Tools intake changed frozen input: ' + name
            validate_new_source_files(work, intake)
            final_files.update(intake['changedOrAddedFiles'])
            intake_report = {'baseCommit': intake['baseCommit'],
                             'baseAndroidAppTree': intake['baseAndroidAppTree'],
                             'patchSha256': intake['patchSha256'],
                             'changedOrAddedFiles': len(intake['changedOrAddedFiles']),
                             'frozenInputs': len(intake['frozenFiles']),
                             'homeAndPanelUnchanged': True, 'runtimePayloadsUnchanged': True,
                             'manifestDependenciesAndSigningUnchanged': True,
                             'baselineUnitTests': intake['baselineUnitTests'],
                             'expectedUnitTests': intake['expectedUnitTests'],
                             'newTestCounts': intake['newTestCounts']}
        final_pdf_report = None
        final_pdf_inputs = ROOT / 'updates/v2085-pdf-final-geometry/inputs.json'
        if final_pdf_inputs.exists():
            from pdf85_final_source_scope import validate_layer as validate_final_pdf, validate_presentation
            assert intake_report is not None, 'Final PDF geometry requires the verified tools intake'
            final_pdf = json.loads(final_pdf_inputs.read_text())
            validate_final_pdf(final_pdf, final_files)
            final_pdf_patch = final_pdf_inputs.with_name('runtime.patch')
            assert digest(final_pdf_patch) == final_pdf['patchSha256']
            before_final_pdf = {name: (work / name).read_text() for name in final_pdf['changedOrAddedFiles']}
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'Final PDF baseline changed: ' + name
            apply(final_pdf_patch, work)
            for name, before in before_final_pdf.items():
                validate_presentation(before, (work / name).read_text(), name)
            for name, sha in final_pdf['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'Final PDF source mismatch: ' + name
            for name, sha in final_pdf['frozenFiles'].items():
                assert digest(work / name) == sha, 'Final PDF changed frozen source: ' + name
            final_files.update(final_pdf['changedOrAddedFiles'])
            final_pdf_report = {'baseCommit': final_pdf['baseCommit'], 'patchSha256': final_pdf['patchSha256'],
                                'changedOrAddedFiles': 4, 'frozenInputs': 375, 'expectedUnitTests': 487,
                                'exactWholeFilePresentationTransforms': True, 'priorLayersUnchanged': True}
        function_report = None
        function_inputs = ROOT / 'updates/v2086-functionfix/inputs.json'
        if function_inputs.exists():
            from functionfix_source_scope import (validate_layer as validate_function_layer,
                                                  validate_source_transforms, validate_new_tests,
                                                  previous_files as function_previous_files)
            assert final_pdf_report is not None, 'Function fixes require all complete prior layers'
            previous = function_previous_files(ROOT)
            assert final_files == previous, 'Function fix predecessor source is not exact'
            function = json.loads(function_inputs.read_text())
            validate_function_layer(function, previous)
            function_patch = function_inputs.with_name('runtime.patch')
            assert digest(function_patch) == function['patchSha256']
            function_before = work / 'function-predecessor'
            shutil.copytree(work / 'android-app', function_before / 'android-app')
            for name, sha in previous.items():
                assert digest(work / name) == sha, 'Function baseline source changed: ' + name
            apply(function_patch, work)
            validate_source_transforms(function_before, work)
            validate_new_tests(work)
            for name, sha in function['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'Function source mismatch: ' + name
            for name, sha in function['frozenFiles'].items():
                assert digest(work / name) == sha, 'Function layer changed frozen input: ' + name
            final_files.update(function['changedOrAddedFiles'])
            function_report = {'baseCommit': function['baseCommit'], 'patchSha256': function['patchSha256'],
                               'changedOrAddedFiles': len(function['changedOrAddedFiles']),
                               'frozenInputs': len(function['frozenFiles']),
                               'baselineUnitTests': function['baselineUnitTests'],
                               'expectedUnitTests': function['expectedUnitTests'],
                               'expectedTestXmlFiles': function['expectedTestXmlFiles'],
                               'newTestCounts': function['newTestCounts'],
                               'allOriginal487TestsPreserved': True,
                               'rootScriptSha256': function['rootScriptSha256'],
                               'runtimePayloadCount': 23}
        homepanel_report = None
        homepanel_inputs = ROOT / 'updates/v2087-home-panel-refactor/inputs.json'
        if homepanel_inputs.exists():
            from homepanel87_source_scope import validate_layer as validate_homepanel_layer
            assert function_report is not None, 'Home/panel refactor requires the verified function-fix layer'
            homepanel = json.loads(homepanel_inputs.read_text())
            validate_homepanel_layer(homepanel, final_files)
            homepanel_patch = homepanel_inputs.with_name('ui.patch')
            assert digest(homepanel_patch) == homepanel['patchSha256'], \
                'Home/panel patch changed without input update'
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'Home/panel refactor baseline mismatch: ' + name
            apply(homepanel_patch, work)
            for name, sha in homepanel['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'Home/panel refactor source mismatch: ' + name
                assert sha != final_files.get(name), 'Unchanged input recorded as a delta: ' + name
            final_files.update(homepanel['changedOrAddedFiles'])
            homepanel_report = {'baseCommit': homepanel['baseCommit'],
                                'patchSha256': homepanel['patchSha256'],
                                'changedOrAddedFiles': len(homepanel['changedOrAddedFiles']),
                                'versionCode': homepanel['versionCode'],
                                'versionName': homepanel['versionName'],
                                'protectedRuntimeUnchanged': True,
                                'reproducedSourceMatchesCheckout': True}
        ui88_report = None
        ui88_inputs = ROOT / 'updates/v2088-ui-refactor/inputs.json'
        if ui88_inputs.exists():
            from ui88_source_scope import validate_layer as validate_ui88_layer
            assert homepanel_report is not None, 'UI unification requires the verified home/panel layer'
            ui88 = json.loads(ui88_inputs.read_text())
            validate_ui88_layer(ui88, final_files)
            ui88_patch = ui88_inputs.with_name('ui.patch')
            assert digest(ui88_patch) == ui88['patchSha256'], \
                'UI unification patch changed without input update'
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'UI unification baseline mismatch: ' + name
            apply(ui88_patch, work)
            for name, sha in ui88['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'UI unification source mismatch: ' + name
                assert sha != final_files.get(name), 'Unchanged input recorded as a delta: ' + name
            final_files.update(ui88['changedOrAddedFiles'])
            ui88_report = {'baseCommit': ui88['baseCommit'],
                           'patchSha256': ui88['patchSha256'],
                           'changedOrAddedFiles': len(ui88['changedOrAddedFiles']),
                           'versionCode': ui88['versionCode'],
                           'versionName': ui88['versionName'],
                           'protectedRuntimeUnchanged': True,
                           'reproducedSourceMatchesCheckout': True}
        r153_report = None
        r153_inputs = ROOT / 'updates/v2089-runtime153/inputs.json'
        if r153_inputs.exists():
            from runtime153_source_scope import validate_layer as validate_r153_layer
            assert ui88_report is not None, 'r153 runtime requires the verified v2088 UI layer'
            r153 = json.loads(r153_inputs.read_text())
            validate_r153_layer(r153, final_files)
            r153_patch = r153_inputs.with_name('runtime.patch')
            assert digest(r153_patch) == r153['patchSha256'], \
                'r153 runtime patch changed without input update'
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'r153 baseline mismatch: ' + name
            apply(r153_patch, work)
            for name, sha in r153['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'r153 source mismatch: ' + name
                assert sha != final_files.get(name), 'Unchanged input recorded as a delta: ' + name
            final_files.update(r153['changedOrAddedFiles'])
            r153_report = {'baseCommit': r153['baseCommit'],
                           'patchSha256': r153['patchSha256'],
                           'changedOrAddedFiles': len(r153['changedOrAddedFiles']),
                           'runtimeRevision': r153['runtimeRevision'],
                           'versionCode': r153['versionCode'],
                           'versionName': r153['versionName'],
                           'protectedPresentationUnchanged': True,
                           'reproducedSourceMatchesCheckout': True}
        stability90_report = None
        stability90_inputs = ROOT / 'updates/v2090-stability-glass/inputs.json'
        if stability90_inputs.exists():
            from stability90_source_scope import validate_layer as validate_stability90_layer, validate_version as validate_stability90_version
            assert r153_report is not None, 'V20.90 requires every verified predecessor layer'
            stability90 = json.loads(stability90_inputs.read_text())
            validate_stability90_layer(stability90, final_files)
            stability90_patch = stability90_inputs.with_name('runtime.patch')
            assert digest(stability90_patch) == stability90['patchSha256']
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'V20.90 baseline mismatch: ' + name
            before_build = (work / 'android-app/app/build.gradle.kts').read_text()
            apply(stability90_patch, work)
            validate_stability90_version(before_build, (work / 'android-app/app/build.gradle.kts').read_text())
            for name, sha in stability90['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'V20.90 generated source mismatch: ' + name
            for name, sha in stability90['frozenFiles'].items():
                assert digest(work / name) == sha, 'V20.90 changed frozen input: ' + name
            final_files.update(stability90['changedOrAddedFiles'])
            stability90_report = {'baseCommit': stability90['baseCommit'],
                                  'patchSha256': stability90['patchSha256'],
                                  'changedOrAddedFiles': len(stability90['changedOrAddedFiles']),
                                  'frozenInputs': len(stability90['frozenFiles']),
                                  'versionCode': stability90['versionCode'],
                                  'versionName': stability90['versionName'],
                                  'expectedUnitTests': stability90['expectedUnitTests'],
                                  'expectedTestXmlFiles': stability90['expectedTestXmlFiles'],
                                  'allOriginal545TestcaseIdentitiesPreserved': True,
                                  'reproducedSourceMatchesCheckout': True}
        continuity91_report = None
        continuity91_inputs = ROOT / 'updates/v2091-continuity-glass/inputs.json'
        if continuity91_inputs.exists():
            from continuity91_source_scope import validate_layer as validate_continuity91_layer, validate_version as validate_continuity91_version
            assert stability90_report is not None, 'V20.91 requires the complete verified V20.90 source'
            continuity91 = json.loads(continuity91_inputs.read_text())
            validate_continuity91_layer(continuity91, final_files)
            continuity91_patch = continuity91_inputs.with_name('runtime.patch')
            assert digest(continuity91_patch) == continuity91['patchSha256']
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'V20.91 baseline mismatch: ' + name
            before_build = (work / 'android-app/app/build.gradle.kts').read_text()
            apply(continuity91_patch, work)
            validate_continuity91_version(before_build, (work / 'android-app/app/build.gradle.kts').read_text())
            for name, sha in continuity91['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'V20.91 generated source mismatch: ' + name
            for name, sha in continuity91['frozenFiles'].items():
                assert digest(work / name) == sha, 'V20.91 changed a frozen input: ' + name
            final_files.update(continuity91['changedOrAddedFiles'])
            continuity91_report = {'baseCommit': continuity91['baseCommit'],
                                   'patchSha256': continuity91['patchSha256'],
                                   'changedOrAddedFiles': len(continuity91['changedOrAddedFiles']),
                                   'frozenInputs': len(continuity91['frozenFiles']),
                                   'versionCode': continuity91['versionCode'],
                                   'versionName': continuity91['versionName'],
                                   'expectedUnitTests': continuity91['expectedUnitTests'],
                                   'expectedTestXmlFiles': continuity91['expectedTestXmlFiles'],
                                   'allPredecessor631TestcaseIdentitiesPreserved': True,
                                   'reproducedSourceMatchesCheckout': True}
        continuity92_report = None
        continuity92_inputs = ROOT / 'updates/v2092-rule-mode-continuity/inputs.json'
        if continuity92_inputs.exists():
            from continuity92_source_scope import validate_layer as validate_continuity92_layer, validate_version as validate_continuity92_version
            assert continuity91_report is not None, 'V20.92 requires the complete pinned V20.91 source'
            continuity92 = json.loads(continuity92_inputs.read_text())
            validate_continuity92_layer(continuity92, final_files)
            continuity92_patch = continuity92_inputs.with_name('runtime.patch')
            assert digest(continuity92_patch) == continuity92['patchSha256']
            for name, sha in final_files.items():
                assert digest(work / name) == sha, 'V20.92 baseline mismatch: ' + name
            before_build = (work / 'android-app/app/build.gradle.kts').read_text()
            apply(continuity92_patch, work)
            validate_continuity92_version(before_build, (work / 'android-app/app/build.gradle.kts').read_text())
            for name, sha in continuity92['changedOrAddedFiles'].items():
                assert digest(work / name) == sha, 'V20.92 generated source mismatch: ' + name
            for name, sha in continuity92['frozenFiles'].items():
                assert digest(work / name) == sha, 'V20.92 changed a frozen input: ' + name
            final_files.update(continuity92['changedOrAddedFiles'])
            continuity92_report = {'baseCommit': continuity92['baseCommit'],
                                   'patchSha256': continuity92['patchSha256'],
                                   'changedOrAddedFiles': len(continuity92['changedOrAddedFiles']),
                                   'frozenInputs': len(continuity92['frozenFiles']),
                                   'versionCode': continuity92['versionCode'],
                                   'versionName': continuity92['versionName'],
                                   'expectedUnitTests': continuity92['expectedUnitTests'],
                                   'expectedTestXmlFiles': continuity92['expectedTestXmlFiles'],
                                   'allPredecessor639TestcaseIdentitiesPreserved': True,
                                   'reproducedSourceMatchesCheckout': True}
        # Every prior frozen input is compared against its precise final SHA.
        # Only recorded, bounded deltas supersede predecessor input hashes.
        validate_checkout_matches_generated(work, ROOT, final_files)
        report = {'baseCommit': spec['baseCommit'], 'baseRun': spec['baseRun'],
                  'archiveSha256': spec['archiveSha256'], 'integrationPatchSha256': spec['patchSha256'],
                  'baselineFiles': len(spec['baselineFiles']), 'changedOrAddedFiles': len(spec['integratedFiles']),
                  'checkoutMatchesGeneratedSource': True, 'historicPatchesAppliedToCheckout': False}
        if layer_report is not None:
            report['presentationLayer'] = layer_report
        if auth_report is not None:
            report['controllerAuthenticationLayer'] = auth_report
        if pdf_report is not None:
            report['pdfReferenceLayer'] = pdf_report
        if intake_report is not None:
            report['toolsIntakeLayer'] = intake_report
        if final_pdf_report is not None:
            report['finalPdfGeometryLayer'] = final_pdf_report
        if function_report is not None:
            report['functionFixLayer'] = function_report
        if homepanel_report is not None:
            report['homePanelLayer'] = homepanel_report
        if ui88_report is not None:
            report['toolsSettingsUiLayer'] = ui88_report
        if r153_report is not None:
            report['runtime153Layer'] = r153_report
        if stability90_report is not None:
            report['stability90Layer'] = stability90_report
        if continuity91_report is not None:
            report['continuity91Layer'] = continuity91_report
        if continuity92_report is not None:
            report['continuity92Layer'] = continuity92_report
        (out / 'effective-source-proof.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report))

if __name__ == '__main__':
    main()
