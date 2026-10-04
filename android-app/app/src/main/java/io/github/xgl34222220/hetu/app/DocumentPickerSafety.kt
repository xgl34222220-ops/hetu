package io.github.xgl34222220.hetu

import android.content.ActivityNotFoundException

/** Missing or disabled OEM DocumentsUI is an actionable error, not a click crash. */
internal fun launchDocumentPicker(onError: (String) -> Unit, launch: () -> Unit): Boolean = try {
    launch()
    true
} catch (_: ActivityNotFoundException) {
    onError("系统文件选择器不可用，请启用文件管理或文档应用后重试")
    false
} catch (_: SecurityException) {
    onError("系统拒绝打开文件选择器，请检查文档应用权限")
    false
}
