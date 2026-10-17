package io.github.xgl34222220.hetu

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * eBPF 规则匹配 · App 侧（phase 3）：载荷与 policy/ebpf 部署片段（真实 sh 执行，chown 用假命令）、
 * 原因文案、热更新判定（仅名单字段变化、参数与启动参数 19/14/17 同串）以及高级代理配置开关接线。
 */
class EbpfMatcherApp105Test {

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Runs [fragment] like deploymentCommand does (`set -e` first) with a no-op chown on PATH. */
    private fun run(dir: File, fragment: String): Int {
        val fake = File(dir, "fakebin").apply { mkdirs() }
        File(fake, "chown").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        val process = ProcessBuilder("sh", "-c", "set -e$fragment")
            .apply { environment()["PATH"] = fake.absolutePath + ":" + System.getenv("PATH") }
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor()
    }

    private fun mode(file: File): String =
        Files.getPosixFilePermissions(file.toPath()).let { java.nio.file.attribute.PosixFilePermissions.toString(it) }

    @Test fun loaderAssetFollowsTheFirstSupportedAbiWithALoader() {
        assertEquals("hetu-bpf/arm64-v8a/hetu-bpf", EbpfMatcher.loaderAsset(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("hetu-bpf/x86_64/hetu-bpf", EbpfMatcher.loaderAsset(arrayOf("x86", "x86_64")))
        assertEquals("hetu-bpf/arm64-v8a/hetu-bpf", EbpfMatcher.loaderAsset(arrayOf("ARM64-V8A")))
        assertNull(EbpfMatcher.loaderAsset(arrayOf("armeabi-v7a", "x86")))
        assertNull(EbpfMatcher.loaderAsset(null))
        assertEquals("hetu-bpf/hetu_match.o", EbpfMatcher.OBJECT_ASSET)
    }

    @Test fun policyFileIsWrittenAndRemovedLikeCnip() {
        val dir = Files.createTempDirectory("hetu-ebpf-policy").toFile()
        try {
            val policyDir = File(dir, "policy").apply { mkdirs() }
            assertEquals(0, run(dir, EbpfMatcher.policyCommand(policyDir.absolutePath, true)))
            val policy = File(policyDir, "ebpf")
            assertEquals("ENABLED=1\n", policy.readText())
            assertEquals("rw-------", mode(policy))
            assertFalse(File(policyDir, "ebpf.new").exists())
            assertEquals(0, run(dir, EbpfMatcher.policyCommand(policyDir.absolutePath, false)))
            assertFalse(policy.exists())
            // Removing an absent policy is not an error under set -e.
            assertEquals(0, run(dir, EbpfMatcher.policyCommand(policyDir.absolutePath, false)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun payloadsAreInstalledWithModesThroughTemporaryFiles() {
        val dir = Files.createTempDirectory("hetu-ebpf-deploy").toFile()
        try {
            val loader = File(dir, "stage-loader").apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3)) }
            val obj = File(dir, "stage-object").apply { writeBytes("bpf-object".toByteArray()) }
            val bin = File(dir, "bin")
            val fragment = EbpfMatcher.payloadCommand(bin.absolutePath, loader.absolutePath, sha(loader.readBytes()),
                obj.absolutePath, sha(obj.readBytes()), ".new.1")
            assertEquals(0, run(dir, fragment))
            val tool = File(bin, "hetu-bpf"); val match = File(bin, "hetu_match.o")
            assertArrayEquals(loader.readBytes(), tool.readBytes())
            assertArrayEquals(obj.readBytes(), match.readBytes())
            assertEquals("rwx------", mode(tool))
            assertEquals("rw-------", mode(match))
            assertTrue(bin.list()!!.none { it.contains(".new.") })
            // Same bytes: kept; a tampered or loosened install is replaced and re-moded.
            tool.writeBytes("tampered".toByteArray()); match.setReadable(true, false)
            assertEquals(0, run(dir, fragment))
            assertArrayEquals(loader.readBytes(), tool.readBytes())
            assertEquals("rw-------", mode(match))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aDigestMismatchFailsTheDeploymentAndLeavesNoTarget() {
        val dir = Files.createTempDirectory("hetu-ebpf-mismatch").toFile()
        try {
            val loader = File(dir, "stage-loader").apply { writeBytes("loader".toByteArray()) }
            val obj = File(dir, "stage-object").apply { writeBytes("object".toByteArray()) }
            val bin = File(dir, "bin")
            val fragment = EbpfMatcher.payloadCommand(bin.absolutePath, loader.absolutePath, "0".repeat(64),
                obj.absolutePath, sha(obj.readBytes()), ".new.2")
            assertNotEquals(0, run(dir, fragment))
            assertFalse(File(bin, "hetu-bpf").exists())
            try {
                EbpfMatcher.payloadCommand(bin.absolutePath, loader.absolutePath, "not-a-digest", obj.absolutePath, sha(obj.readBytes()), ".x")
                throw AssertionError("invalid digest accepted")
            } catch (expected: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }

    @Test fun updateArgumentsAreStartArguments19And14And17() {
        assertArrayEquals(arrayOf("ebpf-update", "10123,10200-10202", "10300", "10.0.0.0/8,fd00::/8"),
            EbpfMatcher.updateArgs("10123,10200-10202", "10300", "10.0.0.0/8,fd00::/8"))
        assertArrayEquals(arrayOf("ebpf-update", "", "", ""), EbpfMatcher.updateArgs(null, null, null))
        // autostartArgs (start arguments 1..30 after "start"): 14 = uidRanges, 17 = cidrs, 19 = directUidRanges.
        val source = File("src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java").readText()
        val body = source.substringAfter("private String[] autostartArgs(Prepared p){").substringAfter("return new String[]{").substringBefore("};")
        val args = topLevelSplit(body)
        assertEquals(30, args.size)
        assertEquals("policy.uidRanges", args[13])
        assertEquals("policy.cidrs", args[16])
        assertEquals("policy.directUidRanges", args[18])
        assertTrue(source.contains("EbpfMatcher.updateArgs(p.policy.directUidRanges,p.policy.uidRanges,p.policy.cidrs)"))
    }

    private fun topLevelSplit(text: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var quoted = false; val cur = StringBuilder()
        for (ch in text) {
            if (ch == '"') quoted = !quoted
            if (!quoted && (ch == '(' || ch == '{')) depth++
            if (!quoted && (ch == ')' || ch == '}')) depth--
            if (!quoted && depth == 0 && ch == ',') { out += cur.toString().trim(); cur.setLength(0) } else cur.append(ch)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private val live = "mihomo|tproxy|bypass|tproxy|tcp=1|udp=1|quic=0|dnsForward=1|dnsTcp=1|dnsUdp=1|perf=0|cpu=|mem=|io=|vendorClean=0" +
        "|systemDns=1|scope=blacklist|uids=10100,10200|share=0|kill=0|cidrs=10.0.0.0/8|ifaces=|sharedMacs=|direct=10050|directGids=" +
        "|fake4=198.18.0.1/16|fake6=|lan4=|lan6=|ebpf=1"

    @Test fun onlyTheMapFieldsMayDifferForAHotUpdate() {
        assertTrue(EbpfMatcher.onlyListsChanged(live, live))
        assertTrue(EbpfMatcher.onlyListsChanged(live, live.replace("uids=10100,10200", "uids=10100-10102")))
        assertTrue(EbpfMatcher.onlyListsChanged(live, live.replace("cidrs=10.0.0.0/8", "cidrs=").replace("direct=10050", "direct=10050,10060")))
        assertFalse(EbpfMatcher.onlyListsChanged(live, live.replace("scope=blacklist", "scope=whitelist")))
        assertFalse(EbpfMatcher.onlyListsChanged(live, live.replace("tproxy|bypass", "redirect|bypass")))
        assertFalse(EbpfMatcher.onlyListsChanged(live, live.replace("ifaces=", "ifaces=wlan0")))
        assertFalse(EbpfMatcher.onlyListsChanged(live, live.replace("directGids=", "directGids=20000")))
        // The switch itself is a restart-only field.
        assertFalse(EbpfMatcher.onlyListsChanged(live, live.removeSuffix("|ebpf=1")))
        assertFalse(EbpfMatcher.onlyListsChanged("", live))
        assertFalse(EbpfMatcher.onlyListsChanged(live, null))
        assertTrue(EbpfMatcher.hotUpdatable("active"))
        assertFalse(EbpfMatcher.hotUpdatable("fallback")); assertFalse(EbpfMatcher.hotUpdatable("off")); assertFalse(EbpfMatcher.hotUpdatable(""))
    }

    @Test fun baseSignatureIgnoresOnlyTheAppListAndBypassCidrs() {
        val profile = ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.BYPASS,
            ProxyRuntimeProfile.AppScope.BLACKLIST, ProxyRuntimeProfile.DnsHijack.TPROXY, true, true, true, false, false, false)
        val values = mapOf<String, Any?>("proxyAppPackages" to setOf("a.b"), "proxyBypassCidrs" to setOf("10.0.0.0/8"), "proxyKillSwitch" to false)
        fun base(v: Map<String, Any?>) = ProxyRuntimeSettings.signature(profile, EbpfMatcher.withoutLists(v))
        val lists = values + mapOf("proxyAppPackages" to setOf("a.b", "c.d"), "proxyBypassCidrs" to emptySet<String>())
        assertEquals(base(values), base(lists))
        assertNotEquals(ProxyRuntimeSettings.signature(profile, values), ProxyRuntimeSettings.signature(profile, lists))
        assertNotEquals(base(values), base(values + ("proxyBypassInterfaces" to setOf("wlan0"))))
        assertNotEquals(base(values), base(values + ("proxyKillSwitch" to true)))
        assertNotEquals(base(values), base(values + ("proxyDirectGids" to setOf("20000"))))
        assertEquals(values, values.toMap()) // input map is not mutated
        assertTrue(values.containsKey("proxyAppPackages"))
    }

    @Test fun everyReasonTokenHasChineseText() {
        val tokens = listOf("selinux-denied", "bpffs-missing", "bpf-syscall-unavailable", "socket-uid-helper-unavailable", "lpm-unsupported",
            "xt-bpf-unsupported", "xt6-bpf-unsupported", "payload-missing", "uid-list-invalid", "cidr-list-invalid", "object-invalid",
            "pins-missing", "unpin-failed", "usage", "error", "mode-tun", "mode-ebpf", "prog-load-failed", "prog-load-permission",
            "map-create-failed", "map-create-permission", "map-update-failed", "pin-failed", "pin-permission", "bpf-syscall-failed")
        for (token in tokens) {
            val text = EbpfMatcher.reasonText(token)
            assertFalse(token, text.startsWith("未知原因"))
            assertTrue(token, text.any { it.code in 0x4e00..0x9fff })
        }
        assertTrue(EbpfMatcher.reasonText("prog-load-permission").endsWith("（权限不足）"))
        assertEquals("未知原因（weird-token）", EbpfMatcher.reasonText("weird-token"))
        assertEquals("未返回原因", EbpfMatcher.reasonText(null))
    }

    @Test fun switchSubtitleReportsTheRunningSession() {
        val about = EbpfMatcher.describe(false, true, "active", "")
        assertTrue(about.contains("默认关闭"))
        assertEquals(about, EbpfMatcher.describe(true, false, "active", ""))
        assertEquals(about, EbpfMatcher.describe(true, true, "", ""))
        assertTrue(EbpfMatcher.describe(true, true, "active", "").startsWith("已生效"))
        assertEquals("未生效，已回退到 iptables 规则：SELinux 拒绝了 bpf 调用", EbpfMatcher.describe(true, true, "fallback", "selinux-denied"))
        assertTrue(EbpfMatcher.describe(true, true, "active", "pins-missing").contains("映射丢失"))
        assertTrue(EbpfMatcher.describe(true, true, "off", "").contains("重启代理后生效"))
    }

    @Test fun advancedSettingsSwitchDefaultsOffAndDeploymentIsGatedByIt() {
        val page = File("src/main/java/io/github/xgl34222220/hetu/ProxyAdvancedSettingsActivity.kt").readText()
        assertTrue(page.contains("prefs.getBoolean(EbpfMatcher.PREF_ENABLED, false)"))
        assertTrue(page.contains("\"eBPF 规则匹配\""))
        assertTrue(page.contains("EbpfMatcher.describe("))
        val manager = File("src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java").readText()
        assertTrue(manager.contains("boolean ebpf=prefs.getBoolean(EbpfMatcher.PREF_ENABLED,false);"))
        assertTrue(manager.contains("cmd.append(EbpfMatcher.policyCommand(policyDir,ebpf));"))
        assertTrue(manager.contains("if(ebpf)appendEbpfPayloads(cmd,stage,deploySuffix);"))
        val controller = File("src/main/java/io/github/xgl34222220/hetu/ProxyComposeController.kt").readText()
        assertTrue(controller.contains(".putString(EbpfMatcher.PREF_STATE, status.optString(\"ebpf\", \"\"))"))
        assertTrue(controller.contains("root.ebpfHotUpdate(profile)"))
        // The hot path runs before the pre-start hook and the replacing start.
        assertTrue(controller.indexOf("root.ebpfHotUpdate(profile)") < controller.indexOf("root.replaceRunningManually(profile, onProgress)"))
    }
}
