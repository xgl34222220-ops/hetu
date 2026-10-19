package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.tools.ToolsCnIpState
import io.github.xgl34222220.hetu.tools.ToolsLoad
import io.github.xgl34222220.hetu.tools.cnIpKernelStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 6.0.0 (domestic video calls lagging): UDP to CN addresses skips the core by default (policy UDPONLY + AUTO),
 * through ipset or, without it, the eBPF CN programs; the CNIP page says which one runs.
 */
class CnUdpDirect600Test {
    private val idle = ToolsCnIpState(ToolsLoad.Ready)

    @Test fun thePolicyBodyIsUdpOnlyAndAutomatic() {
        assertEquals("V4=1\nV6=1\nFORCE=10123\nTCPONLY=0\nUDPONLY=1\nAUTO=1\n", CnipFakeIp.udpPolicyBody("10123"))
        assertEquals("proxyCnIpUdpDirect", CnipFakeIp.PREF_UDP_DIRECT)
        assertTrue("on by default", idle.udpDirect)
    }

    @Test fun theStatusLineNamesTheBackendOfTheDefault() {
        val ebpf = idle.copy(kernelStatus = "ebpf", kernelUdpOnly = true, kernelV4Entries = 5432)
        assertEquals("国内 UDP 内核直连生效（eBPF） · IPv4 5432 段", cnIpKernelStatusText(ebpf))
        assertEquals("国内 UDP 内核直连生效（ipset）", cnIpKernelStatusText(ebpf.copy(kernelStatus = "ipset", kernelV4Entries = 0)))
        assertEquals("本机内核不支持国内 UDP 直连，UDP 仍经核心（ipset-unavailable,bpf-selinux-denied）",
            cnIpKernelStatusText(idle.copy(kernelStatus = "off", kernelReason = "ipset-unavailable,bpf-selinux-denied")))
        assertEquals("重启代理后生效", cnIpKernelStatusText(idle))
        assertEquals("未开启", cnIpKernelStatusText(idle.copy(udpDirect = false)))
    }

    @Test fun theFullBypassKeepsItsOwnLinesAndNamesEbpf() {
        val full = idle.copy(kernelV4 = true, kernelStatus = "ebpf", kernelV4Entries = 10)
        assertEquals("内核 eBPF 生效（本机没有 ipset） · IPv4 10 段", cnIpKernelStatusText(full))
        assertEquals("内核 ipset 生效 · IPv4 10 段", cnIpKernelStatusText(full.copy(kernelStatus = "ipset")))
        assertTrue(cnIpKernelStatusText(full.copy(kernelStatus = "degraded", kernelReason = "ipset-unavailable,bpf-load-permission"))
            .startsWith("设备没有 ipset，eBPF 也不可用"))
    }

    @Test fun everyNewLineIsInTheWordTable() {
        val table = File("src/main/java/io/github/xgl34222220/hetu/ui/HetuLanguage.kt").readText()
        for (key in listOf("国内 UDP 内核直连", "国内 UDP 内核直连生效（eBPF）", "国内 UDP 内核直连生效（ipset）",
                "本机内核不支持国内 UDP 直连，UDP 仍经核心（%s）", "内核 eBPF 生效（本机没有 ipset）",
                "设备没有 ipset，eBPF 也不可用，已降级为核心内规则（%s）",
                "视频 / 语音通话、游戏发往国内 IP 的 UDP 不经过核心，减少卡顿；TCP 仍经核心规则。默认开启")) {
            assertTrue(key, table.contains("\n$key|"))
        }
    }
}
