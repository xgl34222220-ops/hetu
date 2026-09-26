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

/** Lightweight JSON/shell lexical presentation; validation is a separate explicit action. */
public final class HetuCodeLanguage extends EmptyLanguage {
    private final boolean shell;
    private final Analyzer analyzer = new Analyzer();
    private static final Pattern ATOM = Pattern.compile("\\b(true|false|null|if|then|else|elif|fi|for|while|do|done|case|esac|function|export|return|exit|[0-9]+)\\b|\\$\\{?\\w+\\}?");
    public HetuCodeLanguage(String extension) { shell = extension.equalsIgnoreCase("sh") || extension.equalsIgnoreCase("bash"); }
    @Override public boolean useTab() { return false; }
    @Override public AnalyzeManager getAnalyzeManager() { return analyzer; }
    @Override public void destroy() { analyzer.destroy(); }
    private final class Analyzer extends SimpleAnalyzeManager<Void> {
        @Override protected Styles analyze(StringBuilder source, Delegate<Void> delegate) {
            String[] lines = source.toString().split("\n", -1);
            MappedSpans.Builder spans = new MappedSpans.Builder();
            for (int row=0; row<lines.length; row++) {
                if (delegate.isCancelled()) return null;
                String line=lines[row]; int[] colors=new int[line.length()]; Arrays.fill(colors,EditorColorScheme.TEXT_NORMAL);
                Matcher atom=ATOM.matcher(line); while(atom.find()) Arrays.fill(colors,atom.start(),atom.end(),HetuYamlLanguage.YAML_LITERAL);
                for(int i=0;i<line.length();i++) {
                    char c=line.charAt(i);
                    if(shell && c=='#' && (i==0||Character.isWhitespace(line.charAt(i-1)))) { Arrays.fill(colors,i,colors.length,HetuYamlLanguage.YAML_COMMENT);break; }
                    if(c=='"'||(shell&&c=='\'')) {
                        int start=i;
                        while(++i<line.length()) { if(line.charAt(i)=='\\'&&c=='"'){i++;continue;} if(line.charAt(i)==c)break; }
                        int end=Math.min(i+1,line.length()), next=end;
                        while(next<line.length()&&Character.isWhitespace(line.charAt(next)))next++;
                        int color=!shell&&next<line.length()&&line.charAt(next)==':'?HetuYamlLanguage.YAML_KEY:HetuYamlLanguage.YAML_VALUE;
                        Arrays.fill(colors,start,end,color);
                    }
                }
                spans.addIfNeeded(row,0,colors.length==0?EditorColorScheme.TEXT_NORMAL:colors[0]);
                for(int col=1;col<colors.length;col++)if(colors[col]!=colors[col-1])spans.addIfNeeded(row,col,colors[col]);
            }
            spans.determine(lines.length-1);return new Styles(spans.build());
        }
    }
}
