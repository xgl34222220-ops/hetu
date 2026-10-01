#!/usr/bin/env python3
"""Test the exact androidTest foreground wait function without a device or UI dependencies."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kotlin-lib', type=Path, required=True)
args = parser.parse_args()
source = (ROOT / 'android-app/app/src/androidTest/java/io/github/xgl34222220/hetu/HetuWindowUiTest.kt').read_text()
start = source.index('internal fun awaitWindowForeground(')
end = source.index('\n}\n', start) + 3
function = source[start:end]
test = r'''
fun main() {
    var assertions = 0
    fun verify(states: List<Pair<Boolean, String?>>, expected: List<Pair<Boolean, String?>>, timeout: Long, elapsed: Long) {
        var now = 0L
        var reads = 0
        val result = awaitWindowForeground(
            read = { states[minOf(reads++, states.lastIndex)] },
            clock = { now },
            pause = { check(it in 1..50); now += it },
            timeoutMillis = timeout,
        )
        check(result == expected) { "Expected $expected, got $result" }; assertions++
        check(now == elapsed) { "Expected elapsed $elapsed, got $now" }; assertions++
        check(reads == expected.size) { "Unexpected extra reads: $reads" }; assertions++
    }
    val ours = true to "io.github.xgl34222220.hetu"
    val unknown = true to null
    val other = true to "com.android.systemui"
    verify(listOf(ours), listOf(ours), 5000, 0)
    verify(listOf(unknown, unknown, ours), listOf(unknown, unknown, ours), 5000, 100)
    verify(listOf(other, ours), listOf(other), 5000, 0)
    verify(listOf(false to null, ours), listOf(false to null), 5000, 0)
    verify(listOf(false to "io.github.xgl34222220.hetu", ours), listOf(false to "io.github.xgl34222220.hetu"), 5000, 0)
    verify(listOf(unknown, other, ours), listOf(unknown, other), 5000, 50)
    verify(listOf(unknown), listOf(unknown, unknown, unknown), 75, 75)
    verify(listOf(unknown), listOf(unknown), 0, 0)
    verify(listOf(unknown), List(101) { unknown }, 5000, 5000)
    var readCalled = false
    try {
        awaitWindowForeground({ readCalled = true; ours }, { 0 }, {}, -1)
        error("Negative timeout accepted")
    } catch (expected: IllegalArgumentException) { check(!readCalled); assertions++ }
    println("WindowForegroundHostTest passed: $assertions checks; exact instrumentation helper, no device")
}
'''
with tempfile.TemporaryDirectory(prefix='hetu-window-foreground-') as folder:
    folder = Path(folder)
    source_path = folder / 'WindowForegroundHostTest.kt'
    source_path.write_text(function + '\n' + test)
    compiler = list(args.kotlin_lib.glob('kotlin-compiler-embeddable-*.jar'))
    if len(compiler) != 1:
        raise SystemExit('Expected one existing Kotlin compiler in --kotlin-lib')
    classpath = ':'.join(str(p) for p in args.kotlin_lib.glob('*.jar'))
    stdlib = ':'.join(str(p) for p in args.kotlin_lib.glob('kotlin-stdlib-*.jar'))
    java = shutil.which('java')
    if not java:
        raise SystemExit('java is required')
    subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', stdlib, '-d', str(folder / 'classes'), str(source_path)], check=True)
    subprocess.run([java, '-cp', str(folder / 'classes') + ':' + stdlib, 'WindowForegroundHostTestKt'], check=True)
