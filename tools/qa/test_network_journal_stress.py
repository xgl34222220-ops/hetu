#!/usr/bin/env python3
"""Compile real journal against test-only AOSP JSON and original Android API stubs.

No Root, HTTP, device or app data. Sources and fixture history exist only in fresh
temporary directories; --before replays a retained production source byte-for-byte.
"""
import argparse, hashlib, json, shutil, subprocess, tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
JSON=ROOT/'tools/qa/fixtures/android-json'
CONTEXT='package android.content; public class Context { public static final int MODE_PRIVATE=0; public java.io.File getNoBackupFilesDir(){return new java.io.File("unused");} public SharedPreferences getSharedPreferences(String n,int m){return null;} }'
PREFS='package android.content; public interface SharedPreferences { String getString(String key,String fallback); long getLong(String key,long fallback); }'
CLOCK='package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }'
HOST=r'''package io.github.xgl34222220.hetu;
import java.io.*;import java.nio.file.*;import java.util.*;import org.json.*;
final class JournalStressHost {
 static ProxyNetworkJournal journal(File dir,int chunk,long limit)throws Exception{
  try{return ProxyNetworkJournal.class.getDeclaredConstructor(File.class,int.class,long.class).newInstance(dir,chunk,limit);}
  catch(NoSuchMethodException legacy){return new ProxyNetworkJournal(dir,chunk);}
 }
 static long bytes(File dir){return Arrays.stream(dir.listFiles()).filter(File::isFile).mapToLong(File::length).sum();}
 static ProxyNetworkJournal.Event event() {return ProxyNetworkJournal.capture(ProxyNetworkJournal.Stage.EGRESS_RESULT,7,ProxyNetworkJournal.Outcome.UNVERIFIED,503,null,null);}
 public static void main(String[] args)throws Exception{
  File dir=Files.createDirectory(Path.of(args[0],"growth")).toFile();ProxyNetworkJournal j=journal(dir,2048,8192);
  int dropped=0;for(int i=0;i<3000;i++){try{j.append(event());}catch(IOException full){dropped++;}}
  long used=bytes(dir);String view=j.recent();
  File unsafe=Files.createDirectory(Path.of(args[0],"unsafe")).toFile();ProxyNetworkJournal u=journal(unsafe,256*1024,1024*1024);
  Throwable error=new IOException("ignored-secret-message");
  error.setStackTrace(new StackTraceElement[]{new StackTraceElement("https://user:TOPSECRET@private.invalid/token","password=TOPSECRET","ignored.java",42)});
  u.append(ProxyNetworkJournal.capture(ProxyNetworkJournal.Stage.EGRESS_RESULT,1,ProxyNetworkJournal.Outcome.FAILED,503,error,null));
  JSONObject forged=new JSONObject(event().line).put("configuration","https://user:TOPSECRET@private.invalid/token").put("authorization","Bearer TOPSECRET");
  u.append(new ProxyNetworkJournal.Event(forged.getString("id"),forged.toString()));
  boolean leak=u.recent().contains("TOPSECRET")||u.recent().contains("private.invalid");
  ProxyNetworkJournal.Event huge=ProxyNetworkJournal.captureHealth(1,ProxyNetworkJournal.Outcome.DEGRADED,"4-filter-HETU_"+"A".repeat(200000),null);
  File history=Files.createDirectory(Path.of(args[0],"history")).toFile();ProxyNetworkJournal h=journal(history,256*1024,1024*1024);
  for(int i=0;i<100;i++)h.append(event());String recent=h.recent();
  JSONObject result=new JSONObject().put("attempts",3000).put("storageLimitBytes",8192).put("storedBytes",used).put("dropped",dropped)
    .put("fullStatusVisible",view.contains("storagePaused=true")).put("sensitiveStoredMetadataCopied",leak)
    .put("oversizedHealthEventBytes",huge.line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
    .put("viewTruncationVisible",recent.contains("viewTruncated=true"));
  System.out.println(result);
 }
}'''


def run(source):
    with tempfile.TemporaryDirectory(prefix='hetu-journal-host-') as tmp:
        work=Path(tmp); inputs=[]
        for name,content in [('Context.java',CONTEXT),('SharedPreferences.java',PREFS),('SystemClock.java',CLOCK),('JournalStressHost.java',HOST),('ProxyNetworkJournal.java',source.read_text())]:
            p=work/name;p.write_text(content);inputs.append(p)
        inputs.extend(JSON.glob('*.java'))
        compiler=['javac'] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
        c=subprocess.run([*compiler,'-d',str(work),*map(str,inputs)],capture_output=True,text=True)
        if c.returncode:raise RuntimeError(c.stderr)
        r=subprocess.run(['java','-cp',str(work),'io.github.xgl34222220.hetu.JournalStressHost',str(work)],capture_output=True,text=True,check=True)
        result=json.loads(r.stdout)
    result.update(sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),fixtureOnly=True,deviceOrNetworkAccess=False)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--before',type=Path);p.add_argument('--baseline-only',action='store_true');p.add_argument('--output',type=Path);a=p.parse_args()
    report={'after':run(SOURCE/'ProxyNetworkJournal.java')}
    if a.before:report['before']=run(a.before)
    if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
    if not a.baseline_only:
        after=report['after'];assert after['storedBytes']<=after['storageLimitBytes'] and after['dropped']>0
        assert after['fullStatusVisible'] and not after['sensitiveStoredMetadataCopied']
        assert after['oversizedHealthEventBytes']<=8192 and after['viewTruncationVisible']
        if a.before:
            before=report['before'];assert before['storedBytes']>before['storageLimitBytes']
            assert before['sensitiveStoredMetadataCopied'] and before['oversizedHealthEventBytes']>8192
