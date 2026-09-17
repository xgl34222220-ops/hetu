from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = path.read_text()
start = s.index('    if (portsInfo) {\n')
end = s.index('\n    notice?.let { text ->', start)
replacement = '''    if (portsInfo) {
        RefInfoBottomSheet(
            title = "端口细则",
            text = """TProxy：${MihomoStartupConfig.TPROXY_PORT}
Redirect：${MihomoStartupConfig.REDIRECT_PORT}
控制器：127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}

这些是辟尘运行副本使用的安全端口。源订阅文件不会被直接修改。""".trimIndent(),
            actionLabel = "关闭",
            onDismiss = { portsInfo = false },
        )
    }
'''
s = s[:start] + replacement + s[end:]
path.write_text(s)
print('fixed Bottom Sheet port text with Kotlin multiline string')
