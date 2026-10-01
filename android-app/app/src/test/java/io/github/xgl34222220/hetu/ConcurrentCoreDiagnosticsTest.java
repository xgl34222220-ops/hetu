package io.github.xgl34222220.hetu;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ConcurrentCoreDiagnosticsTest {
    private static final String BOOT = "00000000-0000-4000-8000-000000000001";
    private static final String HEADER = "inventory\t1\nboot\t" + BOOT + "\n";
    private static final String OWN = "process\t101\t100\t0\t1\tmihomo\tcore\thetu\tmagiskd\n";
    private static final String OTHER = "process\t202\t200\t10123\t1\tmihomo\tmihomo\tapp-private\tinit\n";
    private static String identity() throws Exception {
        return new JSONObject().put("running", true).put("pid", 101).put("processStartTicks", "100")
                .put("bootId", BOOT).toString();
    }
    private static String report(String rows, String identity) {
        return ConcurrentCoreDiagnostics.render(HEADER + rows + "complete\t4\t0\t0\n", 0,
                identity, uid -> new String[] {"com.example.proxy"});
    }
    @Test public void twoProcessesAreReportedWithoutClaimingTheExactConflictOrKillingEither() throws Exception {
        String value = report(OWN + OTHER + "tun\ttun_test\n", identity());
        assertTrue(value.contains("2 个代理核心候选进程同时存在"));
        assertTrue(value.contains("河图已绑定启动进程"));
        assertTrue(value.contains("com.example.proxy（系统UID映射）"));
        assertTrue(value.contains("不是对具体冲突机制的判定"));
        assertTrue(value.contains("不停止进程、不改网络"));
        assertTrue(value.contains("不证明网站可达"));
    }
    @Test public void changedPidStartOrBootCannotClaimOwnership() throws Exception {
        for (String stale : new String[] {identity().replace("100", "101"), identity().replace(BOOT,
                "00000000-0000-4000-8000-000000000002"), identity().replace("101", "303"), "{}"}) {
            String value = report(OWN + OTHER, stale);
            assertFalse(value.contains("河图已绑定启动进程"));
            assertTrue(value.contains("河图启动身份未确认"));
        }
    }
    @Test public void incompleteDeniedAndEmptySnapshotsRemainUnknown() throws Exception {
        for (String value : new String[] {
                ConcurrentCoreDiagnostics.render(HEADER + OWN, 0, identity(), uid -> null),
                ConcurrentCoreDiagnostics.render(HEADER + OWN, 126, identity(), uid -> null),
                ConcurrentCoreDiagnostics.render("SYNTHETIC_PRIVATE_TOKEN", 0, identity(), uid -> null),
                report("", identity())}) {
            assertFalse(value.contains("SYNTHETIC_PRIVATE_TOKEN"));
            assertFalse(value.contains("河图已绑定启动进程"));
            assertFalse(value.contains("检测到 2"));
            assertTrue(value.contains("未知") || value.contains("未能证实"));
            assertTrue(value.contains("未检出不代表没有其他代理"));
        }
    }
    @Test public void packageOwnershipAndMalformedFieldsStayConservative() throws Exception {
        String shared = ConcurrentCoreDiagnostics.render(HEADER + OTHER + "complete\t1\t0\t0\n", 0, "{}",
                uid -> new String[] {"com.example.one", "com.example.two", "https://secret.invalid/token"});
        assertTrue(shared.contains("共享UID，不能唯一归属"));
        assertFalse(shared.contains("secret.invalid"));
        assertFalse(report(OWN + OWN, identity()).contains("2 个代理核心"));
        String bad = report(OTHER.replace("mihomo\tmihomo", "PRIVATE_TOKEN\tmihomo"), identity());
        assertFalse(bad.contains("PRIVATE_TOKEN"));
        assertTrue(report(OWN, identity()).contains("系统/Root UID，不能唯一对应应用"));
    }
    @Test public void targetLogWindowSurvivesUnrelatedFloodAndKeepsItsBound() throws Exception {
        Path log = Files.createTempFile("hetu log '$literal", ".txt");
        try {
            StringBuilder text = new StringBuilder("old github.io beyond-window\n");
            for (int i = 0; i < 16000; i++) text.append("unrelated synthetic log\n");
            for (int i = 0; i < 30; i++) text.append("github.io synthetic target ").append(i).append('\n');
            for (int i = 0; i < 1000; i++) text.append("unrelated ad rejection\n");
            Files.writeString(log, text);
            Process process = new ProcessBuilder("sh", "-c", ConcurrentCoreDiagnostics.focusedLogCommand(log.toString())).start();
            try {
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
                String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue());
                assertEquals(24, result.lines().count());
                assertTrue(result.contains("target 6\n") && result.contains("target 29\n"));
                assertFalse(result.contains("unrelated") || result.contains("beyond-window"));
            } finally { process.destroyForcibly(); }
        } finally { Files.deleteIfExists(log); }
    }
    @Test public void unreadableTargetLogIsNotReportedAsAnEmptySuccessfulRead() throws Exception {
        Path directory = Files.createTempDirectory("hetu-missing-log");
        try {
            Process process = new ProcessBuilder("sh", "-c", ConcurrentCoreDiagnostics.focusedLogCommand(directory.resolve("missing").toString())).start();
            try {
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
                assertEquals(3, process.exitValue());
                assertEquals(0, process.getInputStream().readAllBytes().length);
            } finally { process.destroyForcibly(); }
        } finally { Files.deleteIfExists(directory); }
    }
}
