package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.Yaml
import java.util.Collections

/**
 * JS override scripts: apply order, all-or-nothing fallback to the unmodified source, the
 * pipeline's own deadline, result validation and the private script store. The fake engine
 * stands in for the WebView engine (whose JS is exercised under Node by tools/qa/test_override_js93.py).
 */
class OverrideScriptPipeline102Test {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeEngine(val behaviour: (String, MutableMap<String, Any?>, String) -> Any?) : OverrideScriptEngine {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val inputs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var closed = 0
        override fun evaluate(name: String, source: String, configJson: String, profileJson: String, timeoutMs: Long, maxResultChars: Int): OverrideScriptEngine.Evaluation {
            calls += name
            inputs += configJson
            @Suppress("UNCHECKED_CAST")
            val config = CoreJson.parseObject(configJson) as MutableMap<String, Any?>
            val out = behaviour(source, config, profileJson)
            return OverrideScriptEngine.Evaluation(if (out is String) out else CoreJson.write(out), listOf("log:$name"))
        }
        override fun close() { closed++ }
    }

    private fun script(name: String, source: String = name) = OverrideScript(name, name, true, source)

    /** "prepend:X" puts rule X first; "throw:msg" fails like a JS exception. */
    private val ruleEngine get() = FakeEngine { source, config, _ ->
        when {
            source.startsWith("prepend:") -> {
                @Suppress("UNCHECKED_CAST")
                val rules = (config["rules"] as? List<Any?>).orEmpty()
                config["rules"] = listOf(source.removePrefix("prepend:")) + rules
                config
            }
            source.startsWith("throw:") -> throw OverrideScriptException("Error: " + source.removePrefix("throw:"))
            else -> config
        }
    }

    private val yamlSource = "mixed-port: 7890\nproxies:\n  - {name: a, type: ss, server: 1.2.3.4, port: 443, cipher: aes-128-gcm, password: p}\nrules:\n  - MATCH,DIRECT\n"

    @Suppress("UNCHECKED_CAST")
    private fun yamlRules(text: String) = (Yaml().load<Any>(text) as Map<String, Any?>)["rules"] as List<String>

    @Test fun enabledScriptsApplyInOrderAndEachSeesThePreviousResult() {
        val engine = ruleEngine
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, mapOf("name" to "sub.yaml"),
            listOf(script("first", "prepend:DOMAIN,a.example,DIRECT"), script("second", "prepend:DOMAIN,b.example,REJECT")), engine)
        assertTrue(outcome.applied); assertFalse(outcome.fallback)
        assertEquals(listOf("first", "second"), engine.calls)
        assertEquals(listOf("DOMAIN,b.example,REJECT", "DOMAIN,a.example,DIRECT", "MATCH,DIRECT"), yamlRules(outcome.text))
        assertTrue("second script receives the first one's output", engine.inputs[1].contains("a.example"))
        assertEquals(7890, (Yaml().load<Map<String, Any?>>(outcome.text))["mixed-port"])
        assertEquals("覆写脚本：已启用 2 个，已应用 2 个", outcome.notice)
        assertEquals(listOf("log:first"), outcome.steps[0].logs)
    }

    @Test fun anyFailureDiscardsEveryChangeAndKeepsTheUnmodifiedSource() {
        val engine = ruleEngine
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null,
            listOf(script("ok", "prepend:DOMAIN,a.example,DIRECT"), script("bad", "throw:boom"), script("never", "prepend:X")), engine)
        assertTrue(outcome.fallback); assertFalse(outcome.applied)
        assertEquals("unmodified source bytes", yamlSource, outcome.text)
        assertEquals("a failing script stops the chain", listOf("ok", "bad"), engine.calls)
        assertTrue(outcome.steps[0].ok); assertFalse(outcome.steps[1].ok)
        assertTrue(outcome.steps[1].detail, outcome.steps[1].detail.contains("boom"))
        assertTrue(outcome.notice, outcome.notice.startsWith("覆写脚本失败，已使用未修改的配置启动") && outcome.notice.contains("bad"))
    }

    @Test fun aStuckEngineIsAbandonedAtTheDeadlineClosedAndTheStartFallsBack() {
        val engine = FakeEngine { _, _, _ -> Thread.sleep(30_000); emptyMap<String, Any?>() }
        val started = System.nanoTime()
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null, listOf(script("loop")), engine,
            timeoutMs = 200, graceMs = 300)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("deadline must bound the start, took $elapsedMs ms", elapsedMs < 5_000)
        assertTrue(outcome.fallback)
        assertEquals(yamlSource, outcome.text)
        assertTrue(outcome.steps.single().detail, outcome.steps.single().detail.contains("超时"))
        assertTrue("the engine (WebView) is torn down", engine.closed >= 1)
    }

    @Test fun resultMustBeAMappingOfBoundedSize() {
        for (bad in listOf("[1,2]", "null", "42", "\"text\"")) {
            val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null, listOf(script("s")), FakeEngine { _, _, _ -> bad })
            assertTrue(bad, outcome.fallback)
            assertEquals(yamlSource, outcome.text)
            assertTrue(outcome.steps.single().detail, outcome.steps.single().detail.contains("配置对象"))
        }
        val huge = "{\"x\":\"" + "a".repeat(OverrideScriptLimits.MAX_CONFIG_CHARS) + "\"}"
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null, listOf(script("s")), FakeEngine { _, _, _ -> huge })
        assertTrue(outcome.fallback)
        assertTrue(outcome.steps.single().detail, outcome.steps.single().detail.contains("过大"))
        val invalid = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null, listOf(script("s")), FakeEngine { _, _, _ -> "{oops" })
        assertTrue(invalid.fallback)
    }

    @Test fun noProfileStartsFromAnEmptyConfigAndPassesNullProfile() {
        var seenProfile = ""
        val engine = FakeEngine { _, config, profile ->
            seenProfile = profile
            config["proxies"] = listOf(mapOf("name" to "gen", "type" to "socks5", "server" to "127.0.0.1", "port" to 1080))
            config["rules"] = listOf("MATCH,gen")
            config
        }
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, "", null, listOf(script("gen")), engine)
        assertEquals("{}", engine.inputs.single().trim())
        assertEquals("null", seenProfile)
        assertTrue(outcome.applied)
        assertEquals(listOf("MATCH,gen"), yamlRules(outcome.text))
    }

    @Test fun profileInformationReachesTheScript() {
        var seen = ""
        OverrideScriptPipeline.apply(OverrideFormat.JSON, "{\"outbounds\":[]}", linkedMapOf("name" to "box.json", "core" to "sing-box", "format" to "json"),
            listOf(script("p")), FakeEngine { _, c, p -> seen = p; c })
        val profile = CoreJson.parseObject(seen)
        assertEquals("box.json", profile["name"]); assertEquals("sing-box", profile["core"]); assertEquals("json", profile["format"])
    }

    @Test fun singBoxJsoncIsScriptedAsJson() {
        val source = "// sing-box\n{\n  \"outbounds\": [{\"type\": \"direct\", \"tag\": \"direct\"}], # note\n  \"route\": {\"rules\": []}\n}\n"
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.JSON, source, null, listOf(script("r")), FakeEngine { _, c, _ ->
            @Suppress("UNCHECKED_CAST") val route = c["route"] as MutableMap<String, Any?>
            route["rules"] = listOf(mapOf("domain_suffix" to listOf("example.com"), "outbound" to "direct")); c
        })
        assertTrue(outcome.applied)
        val result = CoreJson.parseObject(outcome.text)
        @Suppress("UNCHECKED_CAST") val rules = (result["route"] as Map<String, Any?>)["rules"] as List<Map<String, Any?>>
        assertEquals("direct", rules.single()["outbound"])
    }

    @Test fun withoutEnabledScriptsTheSourceIsUntouchedAndNoEngineRuns() {
        val engine = ruleEngine
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null, emptyList(), engine)
        assertEquals(yamlSource, outcome.text)
        assertFalse(outcome.applied); assertFalse(outcome.fallback)
        assertEquals("", outcome.notice)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun invalidSourceYamlFallsBackWithoutRunningScripts() {
        val engine = ruleEngine
        val broken = "rules: [unterminated\n"
        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, broken, null, listOf(script("s")), engine)
        assertTrue(outcome.fallback); assertEquals(broken, outcome.text); assertTrue(engine.calls.isEmpty())
    }

    @Test fun storeKeepsOrderEnabledStateAndLastRunInPrivateFiles() {
        val store = OverrideScriptStore(temporary.newFolder("override-scripts"))
        val a = store.add("A", "function main(c){return c}")
        val b = store.add("B", "function main(c){c.b=1;return c}")
        val c = store.add("C", "function main(c){return c}", enabled = false)
        assertEquals(listOf("A", "B", "C"), store.list().map { it.name })
        store.move(c.id, -2)
        assertEquals(listOf("C", "A", "B"), store.list().map { it.name })
        store.move(c.id, -1)
        assertEquals("clamped at the top", listOf("C", "A", "B"), store.list().map { it.name })
        assertEquals("only enabled scripts, in order, with sources", listOf("A", "B"), store.enabled().map { it.name })
        assertTrue(store.enabled()[1].source.contains("c.b=1"))
        store.setEnabled(c.id, true); store.setEnabled(a.id, false)
        assertEquals(listOf("C", "B"), store.enabled().map { it.name })
        store.update(b.id, "B2", "function main(c){return c}")
        assertEquals("B2", store.list().first { it.id == b.id }.name)
        store.delete(a.id)
        assertEquals(listOf("C", "B2"), store.list().map { it.name })
        assertTrue(store.hasEnabled())
        val reopened = OverrideScriptStore(store.dir)
        assertEquals(listOf(c.id, b.id), reopened.list().map { it.id })

        val outcome = OverrideScriptPipeline.apply(OverrideFormat.YAML, yamlSource, null,
            listOf(script("x", "prepend:A"), script("y", "throw:bad")), ruleEngine)
        reopened.recordRun(outcome, at = 1234L)
        val last = reopened.lastRun()
        assertNotNull(last)
        last!!
        assertEquals(1234L, last.at); assertTrue(last.fallback)
        assertEquals(listOf(true, false), last.steps.map { it.ok })
        assertEquals(outcome.notice, last.notice)
    }

    @Test fun storeRejectsOversizedScriptsAndTooManyScripts() {
        val store = OverrideScriptStore(temporary.newFolder("limits"))
        try { store.add("big", "x".repeat(OverrideScriptLimits.MAX_SCRIPT_CHARS + 1)); throw AssertionError("oversized script accepted") }
        catch (_: IllegalArgumentException) {}
        repeat(OverrideScriptLimits.MAX_SCRIPTS) { store.add("s$it", "function main(c){return c}") }
        try { store.add("one-more", "function main(c){return c}"); throw AssertionError("script limit not enforced") }
        catch (_: IllegalArgumentException) {}
        assertEquals(OverrideScriptLimits.MAX_SCRIPTS, store.list().size)
    }

    @Test fun shippedExamplesDefineMain() {
        assertEquals(5, OverrideScriptExamples.ALL.size)
        OverrideScriptExamples.ALL.forEach { assertTrue(it.title, it.source.contains("function main(config, profile)")) }
    }
}
