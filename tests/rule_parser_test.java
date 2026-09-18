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
        Set<String> parsed=parse("\uFEFF# source\n127.0.0.1 localhost ad.example.com another.example.com\n::1 ip6-localhost\n0.0.0.0 AD.EXAMPLE.COM # duplicate\ntracking.example.com.\n! comment\n",false);
        expect(parsed.size()==3,"Hosts aliases and deduplication");
        expect(parsed.contains("ad.example.com"),"Expected exact match");
        expect(!parsed.contains("child.ad.example.com"),"No automatic subdomain expansion");
        expect(!parsed.contains("example.com"),"No parent-domain expansion");
        expect(!parsed.contains("localhost"),"Local hosts never blocked");
        expect(parse("# comments\n",true).isEmpty(),"Empty effective snapshots support pause/allow-all");
        reject("# comments\n"); reject("0.0.0.0\n");reject("ads.example.com\n<html>error</html>\n");
        reject("||ads.example.com^\n");reject("1.2.3.4 ads.example.com\n");reject("https://ads.example.com/path\n");
        reject("ads.example.com another.example.com\n");reject("0.0.0.0 ads.example.com *.bad.example.com\n");
        try {RuleStore.parseRules(new ByteArrayInputStream(new byte[]{(byte)0xc3,(byte)0x28}),false);throw new AssertionError("Invalid UTF-8 accepted");}
        catch(IOException expected){checks++;}
        byte[] oversized=new byte[8*1024*1024+1];Arrays.fill(oversized,(byte)'#');
        try {RuleStore.parseRules(new ByteArrayInputStream(oversized),false);throw new AssertionError("Oversized source accepted");}
        catch(IOException expected){checks++;}
        // Real merged snapshots can exceed one subscription's 8 MiB quota.
        // Exercise the combined 32 MiB path with valid rules, not just whitespace.
        String longLabel=String.join("",Collections.nCopies(55,"a"));
        StringBuilder combined=new StringBuilder(10*1024*1024);
        for(int i=0;i<50000;i++)combined.append(i).append('.').append(longLabel).append('.').append(longLabel).append('.').append(longLabel).append(".example.com\n");
        expect(combined.length()>8*1024*1024,"Merged regression input exceeds single-source quota");
        expect(parse(combined.toString(),true).size()==50000,"Valid merged snapshot above 8 MiB remains readable");
        reject(combined.toString());
        byte[] oversizedMerged=new byte[32*1024*1024+1];Arrays.fill(oversizedMerged,(byte)'#');
        try {RuleStore.parseRules(new ByteArrayInputStream(oversizedMerged),true);throw new AssertionError("Oversized merged snapshot accepted");}
        catch(IOException expected){checks++;}
        try {RuleStore.checkDownloadDeadline(System.nanoTime()-1);throw new AssertionError("Expired batch budget accepted");}
        catch(IOException expected){expect(expected.getMessage().contains("180"),"Timeout identifies whole-batch budget");}
        Thread.currentThread().interrupt();
        try {parse("ads.example.com\n",false);throw new AssertionError("Cancelled parse accepted");}
        catch(InterruptedIOException expected){checks++;}
        finally {Thread.interrupted();}
        int total=0;
        for(String path:args)try(InputStream in=new FileInputStream(path)) {
            Set<String> builtins=RuleStore.parseRules(in,false);expect(!builtins.isEmpty(),"Builtin source readable: "+path);
            total+=builtins.size();System.out.println(path+": "+builtins.size()+" unique exact domains");
        }
        System.out.println("PASS: "+checks+" checks; "+total+" bundled source entries");
    }
}
