# Magisk / KernelSU (including ReSukiSU) / APatch source this with BusyBox.
SKIPUNZIP=0
if [ "${BOOTMODE:-false}" != true ]; then
  abort "请在已启动的 Android 系统中，通过 Root 管理器安装辟尘。"
fi
FRAMEWORK=
case "${KSU:-false}" in true|1) FRAMEWORK=kernelsu;; esac
if [ -z "$FRAMEWORK" ]; then
  case "${APATCH:-false}" in true|1) FRAMEWORK=apatch;; esac
fi
if [ -z "$FRAMEWORK" ]; then
  case "${MAGISK_VER_CODE:-}" in ''|*[!0-9]*) abort "未识别到 Magisk、KernelSU / ReSukiSU 或 APatch 安装环境。";; esac
  [ "$MAGISK_VER_CODE" -ge 26000 ] || abort "请先升级至 Magisk 26.0 或更新版本。"
  FRAMEWORK=magisk
fi
MODDIR=$MODPATH
. "$MODPATH/bin/runtime.sh" || abort "缺少运行环境脚本，请重新下载完整模块。"
bichen_runtime "$FRAMEWORK" || abort "未找到当前 Root 框架自带的 BusyBox，请检查 Root 管理器安装。"
for APPLET in ash awk flock nsenter sha256sum timeout mount umount chcon; do
  "$BB" --list | "$BB" grep -Fxq "$APPLET" || abort "当前 Root 框架 BusyBox 缺少 $APPLET，请更新 Root 管理器。"
done
printf '%s\n' "$FRAMEWORK" > "$MODPATH/root-framework" || abort "无法保存 Root 框架信息。"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/bichen" 0 0 0755
set_perm "$MODPATH/bin/boot-runner.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
ui_print "- Root 框架：$FRAMEWORK"
ui_print "- 已进入辟尘安装脚本，正在核对必要文件和内置规则 SHA-256"
ASH_STANDALONE=1 "$BB" ash "$MODPATH/bin/bichen" preflight || abort "模块校验失败，请重新导出 App 内置模块 ZIP；不要选择源码包或 APK。"
ui_print "- 检查离线规则，保留既有规则及暂停状态"
ASH_STANDALONE=1 BICHEN_INSTALL=1 "$BB" ash "$MODPATH/bin/bichen" install || abort "初始化失败；原有配置未被替换。"
ui_print "- 安装完成。重启后由辟尘 App 检查实际挂载。"
ui_print "- HaGeZi Light 为可选增强来源，默认关闭，可在 App 的规则页面启用。"
ui_print "- 版本为 beta，尚未完成 HyperOS / Android 16 真机验证。"
