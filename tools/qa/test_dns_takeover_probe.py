#!/usr/bin/env python3
"""Compile and exercise the actual DNS diagnostic Java source without Android or DNS I/O."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/DnsTakeoverProbe.java'

HARNESS = r'''package io.github.xgl34222220.hetu;
import java.net.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*;
public final class DnsTakeover90Host {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static InetAddress ip(String text)throws Exception{return InetAddress.getByName(text);}
 static DnsTakeoverProbe.Result run(String... values){return DnsTakeoverProbe.run("198.18.0.0/16",1000,n->{InetAddress[] out=new InetAddress[values.length];for(int i=0;i<values.length;i++)out[i]=ip(values[i]);return out;});}
 public static void main(String[] args)throws Exception{
  String c="198.18.0.0/16";
  check("captured".equals(DnsTakeoverProbe.verdict("198.18.23.4",c)),"positive");
  for(String a:new String[]{null,"","192.0.2.7","2001:db8::1","not-an-address"})check("inconclusive".equals(DnsTakeoverProbe.verdict(a,c)),"negative "+a);
  for(String range:new String[]{"","bad","198.18.0.0/33","198.18.0.0/-1","300.18.0.0/16","198.18.0.0/x"})check("inconclusive".equals(DnsTakeoverProbe.verdict("198.18.1.2",range)),"bad range");
  check(!DnsTakeoverProbe.inCidr("198.18.1.999",c),"bad address");
  DnsTakeoverProbe.Result real=run("192.0.2.7");check("inconclusive".equals(real.verdict)&&!real.describe().contains("没有进入核心"),"filtered real answer");
  check("inconclusive".equals(run("2001:db8::1").verdict),"ipv6");check("inconclusive".equals(run().verdict),"empty");
  DnsTakeoverProbe.Result mixed=run("2001:db8::1","192.0.2.7","198.18.4.5");check("captured".equals(mixed.verdict)&&mixed.answer.equals("198.18.4.5"),"positive last");
  DnsTakeoverProbe.Result missing=DnsTakeoverProbe.run(c,1000,n->{throw new UnknownHostException("fixture");});check("inconclusive".equals(missing.verdict)&&!missing.describe().contains("被污染"),"NXDOMAIN");
  AtomicInteger skipped=new AtomicInteger();check("inconclusive".equals(DnsTakeoverProbe.run("",1000,n->{skipped.incrementAndGet();return null;}).verdict)&&skipped.get()==0,"no range");
  String first=run("198.18.1.1").name,second=run("198.18.1.1").name;check(first.endsWith(".hetu-dns-probe.invalid")&&!first.equals(second),"reserved nonce");
  Thread.currentThread().interrupt();try{check("inconclusive".equals(run("198.18.1.1").verdict)&&Thread.currentThread().isInterrupted(),"interrupted");}finally{Thread.interrupted();}
  CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(2);AtomicInteger calls=new AtomicInteger();
  DnsTakeoverProbe.Lookup stuck=n->{calls.incrementAndGet();while(true){try{release.await();break;}catch(InterruptedException ignored){}}done.countDown();return new InetAddress[]{ip("198.18.1.1")};};
  try{for(int i=0;i<22;i++)check("inconclusive".equals(DnsTakeoverProbe.run(c,500,stuck).verdict),"bounded deadline");check(calls.get()==2,"bounded workers");}finally{release.countDown();check(done.await(2,TimeUnit.SECONDS),"drained workers");java.lang.reflect.Field field=DnsTakeoverProbe.class.getDeclaredField("LOOKUPS");field.setAccessible(true);ThreadPoolExecutor executor=(ThreadPoolExecutor)field.get(null);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((executor.getActiveCount()!=0||!executor.getQueue().isEmpty())&&System.nanoTime()<deadline)Thread.yield();if(executor.getActiveCount()!=0||!executor.getQueue().isEmpty())throw new AssertionError("lookup pool not drained");}
  System.out.println("{\"checks\":"+checks+",\"failures\":0,\"resolverWorkersMax\":2,\"fixtureDnsOnly\":true}");
 }
}'''


BEFORE_HARNESS = r'''package io.github.xgl34222220.hetu;
public final class DnsTakeoverBeforeHost {
 public static void main(String[] args) {
  int checked=0,falseDirect=0;
  for(String answer:new String[]{null,"","192.0.2.7","2001:db8::1","not-an-address"}){
   checked++; if("direct".equals(DnsTakeoverProbe.verdict(answer,"198.18.0.0/16")))falseDirect++;
  }
  for(String cidr:new String[]{"bad","198.18.0.0/33","198.18.0.0/-1","300.18.0.0/16","198.18.0.0/x"}){
   checked++; if("direct".equals(DnsTakeoverProbe.verdict("198.18.23.4",cidr)))falseDirect++;
  }
  if(checked!=10||falseDirect!=10)throw new AssertionError("historical negative verdict behavior changed");
  System.out.println("{\"checks\":"+checked+",\"inconclusiveCasesMisclassifiedAsDirect\":"+falseDirect+",\"externalDnsQueries\":0}");
 }
}'''


def run(source=SOURCE, harness=HARNESS, class_name='DnsTakeover90Host'):
    with tempfile.TemporaryDirectory(prefix='hetu-dns90-host-') as tmp:
        work = Path(tmp)
        actual = work / 'DnsTakeoverProbe.java'
        actual.write_bytes(source.read_bytes())
        test = work / (class_name + '.java')
        test.write_text(harness)
        compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        subprocess.run([*compiler, '-d', str(work), str(actual), str(test)], check=True, capture_output=True, text=True)
        result = subprocess.run(['java', '-cp', str(work), 'io.github.xgl34222220.hetu.' + class_name], check=True, capture_output=True, text=True, timeout=30)
        report = json.loads(result.stdout)
    report.update(sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(), productionSourceCompiledVerbatim=True,
                  androidRootOrDeviceAccess=False)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--before', type=Path)
    args = parser.parse_args()
    report = run()
    if args.before:
        report = {'after': report, 'before': run(args.before, BEFORE_HARNESS, 'DnsTakeoverBeforeHost')}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report))
