package io.github.xgl34222220.hetu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Runs the production shell probe against a real process, including an unlinked executable. */
public final class ProxyCoreProbeTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static ProxyContinuity.ProcessState probe(Path pidFile, Path core, String setup) throws Exception {
        Process command = new ProcessBuilder("/bin/sh", "-c", setup
                + ProxyContinuity.coreProbeCommand(pidFile.toString(), core.toString()))
                .redirectErrorStream(true).start();
        if (!command.waitFor(5, TimeUnit.SECONDS)) {
            command.destroyForcibly();
            throw new AssertionError("process probe did not finish");
        }
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(command.exitValue() == 0, "probe finishes successfully: " + output);
        return ProxyContinuity.processState(true, output);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("hetu-core-probe-");
        // Shell metacharacters in both paths must remain literal paths.
        Path core = directory.resolve("core '; printf INJECTED; #");
        Path pidFile = directory.resolve("pid ' $(printf INJECTED)");
        Process child = null;
        try {
            Files.copy(Paths.get("/bin/sleep"), core);
            if (!core.toFile().setExecutable(true, true)) throw new AssertionError("cannot make test core executable");
            child = new ProcessBuilder(core.toString(), "60").start();
            Files.writeString(pidFile, Long.toString(child.pid()));
            check(probe(pidFile, core, "") == ProxyContinuity.ProcessState.ALIVE, "exact executable is alive and shell paths are quoted");
            check(probe(pidFile, directory.resolve("other-core"), "") == ProxyContinuity.ProcessState.DEAD, "a confirmed different executable is not Hetu");
            check(probe(pidFile, core, "readlink() { return 1; }; ") == ProxyContinuity.ProcessState.UNKNOWN, "readlink failure does not kill a live process");
            check(probe(pidFile, core, "readlink() { return 0; }; ") == ProxyContinuity.ProcessState.UNKNOWN, "empty executable response is unknown");
            check(probe(pidFile, core, "kill() { return 1; }; ") == ProxyContinuity.ProcessState.UNKNOWN, "permission failure with an existing process is unknown");
            check(probe(pidFile, core, "cat() { return 1; }; ") == ProxyContinuity.ProcessState.UNKNOWN, "unreadable existing pid file is unknown");

            Files.delete(core);
            check(child.isAlive(), "unlinking the executable leaves the process running");
            check(probe(pidFile, core, "") == ProxyContinuity.ProcessState.ALIVE, "replaced or deleted executable remains alive");

            child.destroy();
            check(child.waitFor(5, TimeUnit.SECONDS), "test process exits");
            check(probe(pidFile, core, "") == ProxyContinuity.ProcessState.DEAD, "an exited process is dead");
            check(probe(pidFile, core, "readlink() { return 1; }; ") == ProxyContinuity.ProcessState.DEAD, "confirmed exit stays dead even when readlink is unavailable");

            Files.writeString(pidFile, "not-a-pid");
            check(probe(pidFile, core, "") == ProxyContinuity.ProcessState.UNKNOWN, "damaged pid contents do not prove core death");
            Files.delete(pidFile);
            check(probe(pidFile, core, "") == ProxyContinuity.ProcessState.DEAD, "missing session pid allows a cold boot start");
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("ProxyCoreProbeTest passed: " + checks);
    }
}
