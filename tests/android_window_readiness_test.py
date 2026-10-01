#!/usr/bin/env python3
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from android_window_readiness import component_name, home_component, home_readiness


class HomeReadinessTest(unittest.TestCase):
    component = 'com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher'
    events = 'I/wm_activity_launch_time(542): [0,147888423,com.android.launcher3/.uioverrides.QuickstepLauncher,888]\n'
    activity = 'topResumedActivity=ActivityRecord{abc u0 com.android.launcher3/.uioverrides.QuickstepLauncher t7}\n'
    windows = 'mCurrentFocus=Window{abc u0 com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}\n'

    def result(self, **kw):
        args = dict(component=self.component, events=self.events, activity=self.activity, windows=self.windows)
        args.update(kw)
        return home_readiness(**args)

    def test_actual_frame_and_both_focus_sources_are_required(self):
        self.assertTrue(self.result()['ready'])
        for key in ('component', 'events', 'activity', 'windows'):
            with self.subTest(key=key):
                self.assertFalse(self.result(**{key: ''})['ready'])

    def test_start_or_resume_request_without_first_frame_does_not_pass(self):
        self.assertFalse(self.result(events='I/wm_set_resumed_activity: [0,com.android.launcher3/.uioverrides.QuickstepLauncher,start]')['ready'])

    def test_boot_completed_is_not_ui_readiness(self):
        self.assertFalse(self.result(events='sys.boot_completed=1')['ready'])

    def test_fallback_home_first_frame_is_not_launcher_first_frame(self):
        self.assertFalse(self.result(events=self.events.replace('com.android.launcher3/.uioverrides.QuickstepLauncher', 'com.android.settings/.FallbackHome'))['ready'])

    def test_any_anr_fails_even_when_launcher_has_drawn(self):
        self.assertFalse(self.result(events=self.events + 'I/am_anr: [0,1310,com.android.launcher3,Input dispatching timed out]')['ready'])

    def test_other_foreground_dialog_fails(self):
        self.assertFalse(self.result(windows='mCurrentFocus=Window{abc u0 Application Not Responding: com.android.launcher3}')['ready'])

    def test_crash_loop_fails(self):
        self.assertFalse(self.result(events=self.events + 'I/am_crash: [x]\n' * 3)['ready'])

    def test_drawn_visible_home_surface_is_frame_evidence_even_without_event(self):
        drawn = self.windows + ('  Window #2 Window{abc u0 ' + self.component + '}:\n'
            '    mHasSurface=true\n    Surface: shown=true mDrawState=HAS_DRAWN\n    isOnScreen=true\n')
        result = self.result(events='', windows=drawn)
        self.assertTrue(result['ready'])
        self.assertTrue(result['homeWindowDrawn'])
        self.assertFalse(result['homeFirstFrameEvent'])

    def test_other_or_unshown_surface_does_not_supply_home_frame(self):
        drawn = self.windows + ('  Window #2 Window{abc u0 ' + self.component + '}:\n'
            '    mHasSurface=true\n    Surface: shown=true mDrawState=HAS_DRAWN\n    isOnScreen=true\n')
        for replacement in [drawn.replace('shown=true', 'shown=false'), drawn.replace('HAS_DRAWN', 'DRAW_PENDING'),
                            drawn.replace('isOnScreen=true', 'isOnScreen=false'),
                            drawn.replace('Window #2 Window{abc u0 ' + self.component, 'Window #2 Window{abc u0 com.android.settings/.FallbackHome')]:
            self.assertFalse(self.result(events='', windows=replacement)['ready'])

    def test_component_resolution_is_strict(self):
        self.assertEqual(home_component('priority=0\ncom.android.launcher3/.uioverrides.QuickstepLauncher\n'), self.component)
        self.assertIsNone(home_component('android/com.android.internal.app.ResolverActivity'))
        self.assertIsNone(home_component('com.android.launcher3/.A\ncom.android.launcher3/.B'))
        self.assertIsNone(component_name('Application Not Responding'))


if __name__ == '__main__':
    unittest.main()
