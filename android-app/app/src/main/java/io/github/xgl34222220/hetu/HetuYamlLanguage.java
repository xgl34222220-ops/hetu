package io.github.xgl34222220.hetu;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only lexical presentation. No formatter, auto-completion or text rewriting. */
public final class HetuYamlLanguage extends EmptyLanguage {
    public static final int YAML_KEY = 256, YAML_VALUE = 257, YAML_COMMENT = 258, YAML_LITERAL = 259;
    private final Analyzer analyzer = new Analyzer();
    @Override public AnalyzeManager getAnalyzeManager() { return analyzer; }
    @Override public void destroy() { analyzer.destroy(); }

    public static EditorColorScheme colors(EditorColorScheme base, boolean dark) {
        base.setColor(YAML_KEY, dark ? 0xff9cc5ff : 0xff2157a5);
        base.setColor(YAML_VALUE, dark ? 0xffa9dbc0 : 0xff246443);
        base.setColor(YAML_COMMENT, dark ? 0xffa4adbc : 0xff626b76);
        base.setColor(YAML_LITERAL, dark ? 0xffd8b2ff : 0xff75449b);
        return base;
    }

    private static final Pattern ATOM = Pattern.compile("(?i)(?<![\\w./])(?:true|false|null|yes|no|~|[-+]?\\d+(?:\\.\\d+)?)(?![\\w./])");
    private static final Pattern KEY = Pattern.compile("(?:^|[,{]\\s*)(?:-\\s+)?[\\s]*([^\\s,:{}\\[\\]#][^,:{}\\[\\]#]*?)(?=:\\s|:$)");

    /** Color IDs per character; does not normalize or mutate the input, even invalid YAML. */
    static int[] colorLine(String line, boolean blockScalar) {
        int[] out = new int[line.length()];
        Arrays.fill(out, blockScalar ? YAML_VALUE : EditorColorScheme.TEXT_NORMAL);
        if (blockScalar) return out;
        Matcher atom = ATOM.matcher(line);
        while (atom.find()) Arrays.fill(out, atom.start(), atom.end(), YAML_LITERAL);
        Matcher key = KEY.matcher(line);
        while (key.find()) {
            int end = key.end(1);
            while (end > key.start(1) && Character.isWhitespace(line.charAt(end - 1))) end--;
            Arrays.fill(out, key.start(1), end, YAML_KEY);
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                Arrays.fill(out, i, out.length, YAML_COMMENT); break;
            }
            if (c == '\'' || c == '"') {
                int start = i;
                while (++i < line.length()) {
                    if (line.charAt(i) == '\\' && c == '"') { i++; continue; }
                    if (line.charAt(i) == c) {
                        if (c == '\'' && i + 1 < line.length() && line.charAt(i + 1) == '\'') { i++; continue; }
                        break;
                    }
                }
                Arrays.fill(out, start, Math.min(i + 1, out.length), YAML_VALUE);
            }
        }
        return out;
    }

    private static final class Analyzer extends SimpleAnalyzeManager<Void> {
        @Override protected Styles analyze(StringBuilder source, Delegate<Void> delegate) {
            MappedSpans.Builder spans = new MappedSpans.Builder();
            String[] lines = source.toString().split("\\n", -1);
            int scalarIndent = -1;
            for (int row = 0; row < lines.length; row++) {
                if (delegate.isCancelled()) return null;
                String line = lines[row];
                int indent = 0;
                while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
                boolean scalar = scalarIndent >= 0 && (line.trim().isEmpty() || indent > scalarIndent);
                if (!scalar) scalarIndent = -1;
                int[] colors = colorLine(line, scalar);
                spans.addIfNeeded(row, 0, colors.length == 0 ? EditorColorScheme.TEXT_NORMAL : colors[0]);
                for (int col = 1; col < colors.length; col++) {
                    if (colors[col] != colors[col - 1]) spans.addIfNeeded(row, col, colors[col]);
                }
                if (!scalar && line.matches(".*:\\s*[|>][+-]?[1-9]?\\s*(?:#.*)?")) scalarIndent = indent;
            }
            spans.determine(lines.length - 1);
            return new Styles(spans.build());
        }
    }
}
