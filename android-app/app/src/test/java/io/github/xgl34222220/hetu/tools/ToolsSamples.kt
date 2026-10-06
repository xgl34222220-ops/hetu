package io.github.xgl34222220.hetu.tools

/** Sample data for previews; mirrors the 示例数据 of concept pages 1–25. */
internal object ToolsSamples {
    val configs = listOf(
        ToolsConfig("日常.yaml", current = true),
        ToolsConfig("工作.yaml"),
        ToolsConfig("默认.yaml", kind = ToolsConfigKind.Bundled),
    )

    val subscriptions = listOf(
        ToolsSubscription("主订阅", "https://example.com/subscription"),
        ToolsSubscription("备用订阅", "https://example.org/subscription"),
    )

    val configState = ToolsConfigState(ToolsLoad.Ready, configs, subscriptions)
    val configStateNoSubscriptions = configState.copy(subscriptions = emptyList())

    val rootSearch = ToolsRootState(searching = true, query = "网络")
    val rootSearchEmpty = ToolsRootState(searching = true, query = "蓝牙")

    val addSubscription = ToolsSubscriptionForm.add().copy(name = "旅行订阅", url = "https://example.com/subscription")
    val addSubscriptionNameEmpty = ToolsSubscriptionForm.add().copy(url = "https://example.com/subscription", nameError = "名称不能为空")
    val addSubscriptionUrlInvalid = ToolsSubscriptionForm.add().copy(name = "旅行订阅", url = "example.com/subscription", urlError = ToolsRules.UrlError)
    val editSubscriptionUrlInvalid = ToolsSubscriptionForm.edit(subscriptions[0]).copy(url = "example.com/subscription", urlError = ToolsRules.UrlError)

    val importLink = ToolsImportForm(url = "https://example.com/config.yaml", name = "旅行.yaml")
    val importLinkInvalid = ToolsImportForm(url = "example.com/config.yaml", name = "旅行.yaml", urlError = ToolsRules.UrlError)

    val yaml = """
        mixed-port: 7890
        allow-lan: true
        mode: rule
        log-level: info
        ipv6: true

        dns:
          enable: true
          enhanced-mode: fake-ip
          nameserver:
            - 223.5.5.5

        proxy-providers:
          main:
            type: http
            path: ./providers/main.yaml
            interval: 86400

        proxy-groups:
          - name: 节点选择
            type: select
            use:
              - main

        rules:
          - GEOIP,CN,DIRECT
          - MATCH,节点选择
    """.trimIndent() + "\n"

    val yamlInvalid = yaml.replace("mixed-port: 7890", "mixed-port: invalid")

    val editorDraft = ToolsEditorState(ToolsLoad.Ready, "日常.yaml", dirty = true, canUndo = true)
    val editorInvalid = editorDraft.copy(banner = "配置无效：mixed-port 必须为数字", errorLine = 1)
    val editorLoadFailed = ToolsEditorState(ToolsLoad.Failed("源文件暂时无法读取"), "日常.yaml")
}
