#!/usr/bin/env python3
"""Original JVM-location fixtures against the whole production journal; no devices."""
import argparse, json
from pathlib import Path
import test_network_journal_stress as harness

harness.HOST=r'''package io.github.xgl34222220.hetu;
import java.io.*;import java.nio.file.*;import java.util.*;import org.json.*;
final class JournalStressHost {
 public static void main(String[] args)throws Exception {
  ProxyNetworkJournal journal=new ProxyNetworkJournal(Files.createDirectory(Path.of(args[0],"symbols")).toFile(),4096);
  String[] methods={"toDynamicScheme-Iv8Zu3U","<init>","<clinit>"};
  JSONArray captured=new JSONArray(),copied=new JSONArray();
  for(String method:methods){
   Throwable error=new NoSuchMethodError("SYNTHETIC private message; no missing method is invoked");
   error.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.Worker",method,"private-config-file",41)});
   ProxyNetworkJournal.Event e=ProxyNetworkJournal.capture(ProxyNetworkJournal.Stage.HEALTH_RESULT,7,ProxyNetworkJournal.Outcome.UNKNOWN,0,error,null);
   captured.put(new JSONObject(e.line).getJSONArray("causes").getJSONObject(0).getJSONArray("frames").getString(0));journal.append(e);
  }
  for(String line:journal.recent().split("\n"))if(line.startsWith("{"))copied.put(new JSONObject(line).getJSONArray("causes").getJSONObject(0).getJSONArray("frames").getString(0));
  int rejected=0;
  for(String method:new String[]{"<init>https://TOPSECRET@private.invalid","invoke-token=TOPSECRET","invoke-TOPSECRET@private.invalid","get\nTOPSECRET","m".repeat(161)}){
   Throwable error=new IOException("TOPSECRET");error.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.Worker",method,"ignored",42)});
   ProxyNetworkJournal.Event e=ProxyNetworkJournal.capture(ProxyNetworkJournal.Stage.HEALTH_RESULT,7,ProxyNetworkJournal.Outcome.UNKNOWN,0,error,null);
   if(e.line.contains("redacted-frame")&&!e.line.contains("TOPSECRET"))rejected++;
  }
  System.out.println(new JSONObject().put("captured",captured).put("copied",copied).put("hostileSymbolsRejected",rejected)
   .put("privateMessageOrFileCopied",journal.recent().contains("SYNTHETIC")||journal.recent().contains("private-config-file")));
 }
}'''

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--before',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
 result={'after':harness.run(harness.SOURCE/'ProxyNetworkJournal.java')}
 if a.before:result['before']=harness.run(a.before)
 if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result))
 expected=['example.Worker.toDynamicScheme-Iv8Zu3U:41','example.Worker.<init>:41','example.Worker.<clinit>:41']
 assert result['after']['captured']==expected and result['after']['copied']==expected
 assert result['after']['hostileSymbolsRejected']==5 and not result['after']['privateMessageOrFileCopied']
 if a.before:assert result['before']['captured']==['redacted-frame']*3 and result['before']['copied']==['redacted-frame']*3
