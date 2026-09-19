package io.github.xgl34222220.hetu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host regression tests for the same Java parser used by downloads and DNS. */
class RuleStoreParserTest {
    static int checks;
    static void expect(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    static Set<String> parse(String source,boolean empty) throws Exception {
        return RuleStore.parseRules(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),empty);
    }
    static void reject(String source) throws Exception {
        try {parse(source,false);throw new AssertionError("Accepted invalid subscription: "+source);}
        catch(IOException expected) {checks++;}
    }
    public static void main(String[] args) throws Exception {
        String[][] valid={
            {" EXAMPLE.COM. ","example.com"},
            {"https://ads.Example.com/private/path?token=secret#fragment","ads.example.com"},
            {"http://Example.com:8080/a","example.com"},
            {"https://例子.测试/a","xn--fsqu00a.xn--0zwm56d"},
            {"例子。测试","xn--fsqu00a.xn--0zwm56d"},
            {"a-b.c-d.example","a-b.c-d.example"},
            {"https://example.com./","example.com"}
        };
        for(String[] pair:valid)expect(pair[1].equals(RuleStore.normalize(pair[0])),"Normalization: "+pair[0]);
        String[] invalid={null,""," ","localhost","localhost.localdomain","127.0.0.1","1.2.3.4.","::1","[2001:db8::1]",
            "http://127.0.0.1/a","http://[2001:db8::1]/","https://user:pass@example.com/","ftp://example.com/file",
            "//example.com/a","example.com/a","*.example.com","||example.com^","a..example.com","-a.example.com",
            "a-.example.com","a_b.example.com","https://example.com:0","https://example.com:65536","https://example.com:abc",
            "https://example.com:","http:///path","evil.example\nother.example","example.com?x=1","example.com#x","example.com.."};
        for(String s:invalid)expect(RuleStore.normalize(s)==null,"Rejected domain: "+s);
        expect(RuleStore.normalize("a".repeat(63)+".example")!=null,"DNS label length 63 remains valid");
        expect(RuleStore.normalize("a".repeat(64)+".example")==null,"DNS labels over 63 are rejected");
        expect(RuleStore.normalize("a.123")==null,"Numeric top-level label cannot masquerade as hostname");
        expect("foo.example".equals(RuleStore.normalize("ＦＯＯ.example")),"Unicode compatibility characters retain IDN conversion");
        Set<String> parsed=parse("\uFEFF# source\n127.0.0.1 localhost ad.example.com another.example.com\n::1 ip6-localhost\n0.0.0.0 AD.EXAMPLE.COM # duplicate\ntracking.example.com.\n! comment\n",false);
        expect(parsed.size()==3,"Hosts aliases and deduplication");
        expect(parsed.contains("ad.example.com"),"Expected exact match");
        expect(!parsed.contains("child.ad.example.com"),"No automatic subdomain expansion");
        expect(!parsed.contains("example.com"),"No parent-domain expansion");
        expect(!parsed.contains("localhost"),"Local hosts never blocked");
        expect(parse("# comments\n",true).isEmpty(),"Empty effective snapshots support pause/allow-all");
        reject("# comments\n"); reject("0.0.0.0\n");reject("ads.example.com\n<html>error</html>\n");
        reject("1.2.3.4 ads.example.com\n");reject("https://ads.example.com/path\n");
        reject("ads.example.com another.example.com\n");
        expect(parse("||ads.example.com^\n@@||login.example.com^\n",false)
                .equals(Set.of("ads.example.com","@@login.example.com")),"Unconditional suffix blocks and exceptions survive");
        String[] modifiers={"dnstype=AAAA","denyallow=login.example.com","client=192.0.2.1",
                "dnsrewrite=NOERROR;A;192.0.2.1","dnsrewrite","important","third-party","script","unknown"};
        for(String modifier:modifiers) {
            expect(parse("||example.com^$"+modifier+"\n",true).isEmpty(),"Conditional rule is never widened: "+modifier);
            expect(parse("@@||example.com^$"+modifier+"\n",true).isEmpty(),"Conditional exception is never widened: "+modifier);
        }
        expect(parse("||ads.example.com^\n||ads.example.com^$badfilter\n",true).isEmpty(),"badfilter cancels previous block");
        expect(parse("||ads.example.com^$badfilter\n||ads.example.com^\n",true).isEmpty(),"badfilter cancels following block");
        expect(parse("@@||ads.example.com^\n@@||ads.example.com^$badfilter\n",true).isEmpty(),"badfilter cancels exception");
        expect(parse("||ads.example.com^$badfilter\n",true).isEmpty(),"Standalone badfilter never adds a block");
        expect(parse("||ads.example.com^\n||ads.example.com^$badfilter\nads.example.com\n",true)
                .equals(Set.of("ads.example.com")),"badfilter does not remove independent plain-domain rule");
        expect(parse("0.0.0.0 ads.example.com\n||ads.example.com^$badfilter\n",true)
                .equals(Set.of("ads.example.com")),"badfilter does not remove independent hosts entry");
        expect(parse("||ads.example.com^\n@@||ads.example.com^$badfilter\n",true)
                .equals(Set.of("ads.example.com")),"Cancelling an exception does not cancel a block");
        expect(parse("||ads.example.com^\n||ads.example.com^$dnstype=AAAA,badfilter\n",true)
                .equals(Set.of("ads.example.com")),"Conditional badfilter does not cancel unconditional rule");
        expect(parse("||example.com^\n||example.com$badfilter\n",true)
                .equals(Set.of("example.com")),"badfilter identifies original text including anchor");
        for(String narrowed:List.of("||example.com^/ads", "||example.com/ads^", "||*.example.com^",
                "||example.com", "|https://example.com^", "||example.com^$")) {
            expect(parse(narrowed+"\n",true).isEmpty(),"Narrower pattern cannot become whole-domain rule: "+narrowed);
        }
        expect(parse("@@internal.example.com\n",true).equals(Set.of("@@internal.example.com")),"Persisted exception format remains readable");
        reject("@@internal.example.com\n");
        try {RuleStore.parseRules(new ByteArrayInputStream(new byte[]{(byte)0xc3,(byte)0x28}),false);throw new AssertionError("Invalid UTF-8 accepted");}
        catch(IOException expected){checks++;}
        byte[] oversized=new byte[32*1024*1024+1];Arrays.fill(oversized,(byte)'#');
        try {RuleStore.parseRules(new ByteArrayInputStream(oversized),false);throw new AssertionError("Oversized source accepted");}
        catch(IOException expected){expect(expected.getMessage().contains("32 MiB"),"Source limit error matches real 32 MiB quota");}
        // A normal merged generation remains readable using the same parser.
        String longLabel=String.join("",Collections.nCopies(55,"a"));
        StringBuilder combined=new StringBuilder(10*1024*1024);
        for(int i=0;i<50000;i++)combined.append(i).append('.').append(longLabel).append('.').append(longLabel).append('.').append(longLabel).append(".example.com\n");
        expect(combined.length()>8*1024*1024,"Merged regression input remains substantial");
        expect(parse(combined.toString(),true).size()==50000,"Merged snapshots retain every domain");
        try {RuleStore.checkDownloadDeadline(System.nanoTime()-1);throw new AssertionError("Expired batch budget accepted");}
        catch(IOException expected){expect(expected.getMessage().contains("超时"),"Expired download is reported as a timeout");}
        Thread.currentThread().interrupt();
        try {parse("ads.example.com\n",false);throw new AssertionError("Cancelled parse accepted");}
        catch(InterruptedIOException expected){checks++;}
        finally {Thread.interrupted();}
        expect(!MessagingFilterPolicy.subscriptionExceptions(Set.of("child.long.weixin.qq.com"))
                .contains("long.weixin.qq.com"),"Automatic parent exception cannot bypass an explicit child block");
        expect(MessagingFilterPolicy.subscriptionExceptions(Set.of("ad.weixin.qq.com"))
                .contains("long.weixin.qq.com"),"An unrelated manual block does not remove message transport exceptions");
        expect(RuleStore.shouldRefreshBundledAdguard(false,0,0L,100L),"Legacy default source is repaired offline");
        expect(!RuleStore.shouldRefreshBundledAdguard(false,2,0L,100L),"Current format is not rebuilt on every reload");
        expect(!RuleStore.shouldRefreshBundledAdguard(false,3,0L,100L),"Future format is not downgraded");
        expect(!RuleStore.shouldRefreshBundledAdguard(false,0,101L,100L),"Newer downloaded sources are retained");
        expect(!RuleStore.shouldRefreshBundledAdguard(true,0,0L,100L),"External module snapshot is not replaced");
        int total=0;
        for(String path:args)try(InputStream in=new FileInputStream(path)) {
            long started=System.nanoTime();
            Set<String> builtins=RuleStore.parseRules(in,false);expect(!builtins.isEmpty(),"Builtin source readable: "+path);
            if(path.endsWith("adguard.txt")) {
                expect(builtins.size()>100000,"Offline default source is present and substantial");
                for(String cancelled:List.of("pl.ua","tn.porngo.xxx","linksprf.com","fixtures.onet.pl"))
                    expect(!builtins.contains(cancelled),"Real upstream badfilter no longer creates a block: "+cancelled);
            }
            total+=builtins.size();System.out.println(path+": "+builtins.size()+" unique rules, parsed in "+((System.nanoTime()-started)/1000000)+" ms");
        }
        System.out.println("PASS: "+checks+" checks; "+total+" bundled source entries");
    }
}
