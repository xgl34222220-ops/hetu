# V20.69 internal validation candidate

## Measured file-menu refinement
At the same normalized width, the prior file popup was about167px high against about220px in the reference. File actions now have43dp rows; folder actions36dp. Entry-specific heading, icon, pointer, selected-row highlight and overlapping anchor placement match their two references. Configuration menus retain their prior geometry. Three scoped menu/cancel/narrow-name regressions pass, including action-row geometry assertions. No filesystem action semantics change.

## Actual WebView verification
V20.68 passed native home/language/panel/tools/settings/about/Web-panel navigation. Its real APK visibly rendered the built-in WebUI, but UiAutomator exposed an empty WebView node. That remained a failed assertion, not a passed content check. Cold start was2.743seconds and the recorded ANR state was empty.

The bugreport showed the app's existing `webview_devtools_remote` socket. This candidate reads real DOM and activates real buttons through that already-enabled debug channel, while screenshots still come from Android. It does not enable debugging or edit page data. Temporary forwarding is removed and checked afterward; the controlled API reverse mapping is also removed. DOM checks retain disconnected/error assertions and add exact metric/group/connection counts, dialog state and preserved current-node checks. Host driver tests pass3 protocol cases; native execution still needs CI evidence.

Android documents automatic WebView debugging for debuggable apps in newer WebView versions: https://developer.android.com/reference/android/webkit/WebView.html#setWebContentsDebuggingEnabled(boolean)

CI continues232 selected Android regressions and all prior gates. No claim of173-state pixel equality, K80/root behavior, main merge or release.
