package io.github.xgl34222220.hetu

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * JavaScript lexical presentation for override scripts, on the color slots of [HetuYamlLanguage]
 * (keywords = key color, strings = value color, numbers/atoms = literal color, comments).
 * Read-only presentation: no formatter, completion or text rewriting.
 */
internal class HetuJsLanguage : EmptyLanguage() {
    private val analyzer = Analyzer()
    override fun useTab(): Boolean = false
    override fun getAnalyzeManager(): AnalyzeManager = analyzer
    override fun destroy() { analyzer.destroy() }

    private class Analyzer : SimpleAnalyzeManager<Void>() {
        override fun analyze(source: StringBuilder, delegate: SimpleAnalyzeManager<Void>.Delegate<Void>): Styles? {
            val spans = MappedSpans.Builder()
            val lines = source.toString().split("\n")
            val inComment = booleanArrayOf(false)
            for (row in lines.indices) {
                if (delegate.isCancelled) return null
                val colors = colorLine(lines[row], inComment)
                spans.addIfNeeded(row, 0, (if (colors.isEmpty()) EditorColorScheme.TEXT_NORMAL else colors[0]).toLong())
                for (col in 1 until colors.size) if (colors[col] != colors[col - 1]) spans.addIfNeeded(row, col, colors[col].toLong())
            }
            spans.determine(lines.size - 1)
            return Styles(spans.build())
        }
    }

    companion object {
        private val KEYWORD = Regex("\\b(?:function|return|const|let|var|if|else|for|while|do|of|in|new|typeof|instanceof|switch|case|break|continue|default|try|catch|finally|throw|async|await|class|extends|this|delete|void|yield)\\b")
        private val ATOM = Regex("\\b(?:true|false|null|undefined|NaN|Infinity)\\b|(?<![\\w$])(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)(?![\\w$])")

        /** Color IDs per character of one line; [inComment] carries an open block comment across lines. */
        fun colorLine(line: String, inComment: BooleanArray): IntArray {
            val out = IntArray(line.length) { EditorColorScheme.TEXT_NORMAL }
            ATOM.findAll(line).forEach { out.fill(HetuYamlLanguage.YAML_LITERAL, it.range.first, it.range.last + 1) }
            KEYWORD.findAll(line).forEach { out.fill(HetuYamlLanguage.YAML_KEY, it.range.first, it.range.last + 1) }
            var i = 0
            while (i < line.length) {
                if (inComment[0]) {
                    val end = line.indexOf("*/", i)
                    val stop = if (end < 0) line.length else end + 2
                    out.fill(HetuYamlLanguage.YAML_COMMENT, i, stop)
                    if (end >= 0) inComment[0] = false
                    i = stop
                    continue
                }
                val c = line[i]
                if (c == '/' && i + 1 < line.length && line[i + 1] == '/') {
                    out.fill(HetuYamlLanguage.YAML_COMMENT, i, out.size)
                    break
                }
                if (c == '/' && i + 1 < line.length && line[i + 1] == '*') {
                    inComment[0] = true
                    out.fill(HetuYamlLanguage.YAML_COMMENT, i, i + 2)
                    i += 2
                    continue
                }
                if (c == '"' || c == '\'' || c == '`') {
                    val start = i
                    while (++i < line.length) {
                        if (line[i] == '\\') { i++; continue }
                        if (line[i] == c) break
                    }
                    out.fill(HetuYamlLanguage.YAML_VALUE, start, minOf(i + 1, out.size))
                }
                i++
            }
            return out
        }
    }
}
