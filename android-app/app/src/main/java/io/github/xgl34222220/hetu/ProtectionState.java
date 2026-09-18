package io.github.xgl34222220.hetu;

/** A displayed state is not a diagnosis: only confirmed runtime facts turn it green. */
final class ProtectionState {
    enum State {
        CHECKING("正在检查状态"), UNKNOWN("状态待确认"), ABSENT("尚未安装模块"),
        PENDING_REBOOT("模块等待重启"), REMOVAL("模块等待卸载"), DISABLED("模块已停用"),
        PAUSED("模块保护已暂停"), UNVERIFIED("挂载待确认"), MODULE_ACTIVE("模块保护已开启"),
        VPN_STARTING("应用保护正在启动"), VPN_ACTIVE("应用保护已开启"), RESTORE_PENDING("原模块保护待恢复");
        final String title;
        State(String title) { this.title=title; }
        boolean active() { return this==MODULE_ACTIVE || this==VPN_ACTIVE; }
    }
    private ProtectionState() { }
    static State resolve(boolean loaded, boolean valid, boolean known, boolean installed,
                         boolean enabled, boolean mounted, boolean disabled, boolean removal,
                         boolean pending, boolean vpn, boolean wanted, boolean restoring) {
        if(vpn) return State.VPN_ACTIVE;
        if(wanted) return State.VPN_STARTING;
        if(restoring) return State.RESTORE_PENDING;
        if(!loaded) return State.CHECKING;
        if(!valid || !known) return State.UNKNOWN;
        if(pending) return State.PENDING_REBOOT;
        if(!installed) return State.ABSENT;
        if(removal) return State.REMOVAL;
        if(disabled) return State.DISABLED;
        if(enabled && mounted) return State.MODULE_ACTIVE;
        if(enabled || mounted) return State.UNVERIFIED;
        return State.PAUSED;
    }
}
