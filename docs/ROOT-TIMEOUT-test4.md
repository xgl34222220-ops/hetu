# test.4：Root 状态读取超时修复候选

基于 f45b084，保留刚恢复的原APK图标、ModuleArchive校验与既有改进。独立分支避免覆盖正在进行的test.3图标/安装修复。

用户截图显示 ReSukiSU 中 bichen 0.3.0-beta.1 已安装、开关开启，App收到ok:true状态却退出码124。不能归因为用户刷错。RootBridge原有后台计时shell、/proc子进程清理与等待看门狗已改为前台exec框架BusyBox原生timeout + ash。run/status末尾exec实际CLI。保留App总时限、32MiB输出上限、后台读管道和非零退出检查，未把有JSON但实际超时强行当成功。

错误摘要限长，原始返回存details。模块引擎、规则及用户开启/暂停状态不变。enabled:false、mounted:false不是管理器模块开关关闭；它们表示hosts当前暂停/未挂载。

新增root_process_test.py使用实际RootBridge与RootShellCommand、宿主API占位及假su，检查快速返回、非零退出、引用、stdout/stderr、真实超时及忽略TERM。CI额外运行mksh。宿主测试不代表Android Root授权、SELinux或实际设备已通过。

预览0.3.0-test.4/code304，包名仍io.github.xgl34222220.bichen.preview。CI只交接未签名APK，在本地用已提供test.2的同一测试签名签署并比对证书。直接覆盖测试App，不卸载、不重复刷已安装的0.3.0-beta.1模块。没有合并main或发布正式版。真机问题是否完全解决，仍以这次设备测试为准。
