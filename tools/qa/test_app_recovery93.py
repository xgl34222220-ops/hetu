#!/usr/bin/env python3
"""Reconstructed host regression, not Android execution.
Compiles exact manager entry methods and its existing-runtime start branch.
Preparation/deployment, Android preferences and privileged replies are isolated
shims. The omitted new-runtime path explicitly throws; no claim about it is made.
The archived thirteen Robolectric tests remain the full-class Android gate.
"""
import argparse, hashlib, json, pathlib, re, shutil, subprocess, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
MAIN='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
BASE='cffeff08ee38c7220922bdb36de81b621c935daf'

def block(src, start):
    opening=src.index('{',start); depth=1;i=opening+1;quote=None;comment=None
    while depth:
        c=src[i];n=src[i:i+2]
        if comment=='line':
            if c=='\n': comment=None
        elif comment=='block':
            if n=='*/': comment=None;i+=1
        elif quote:
            if c=='\\': i+=1
            elif c==quote: quote=None
        elif n=='//': comment='line';i+=1
        elif n=='/*': comment='block';i+=1
        elif c in '\"\'': quote=c
        elif c=='{': depth+=1
        elif c=='}': depth-=1
        i+=1
    return src[start:i]

def method(src, signature):
    start=src.index(signature)
    start=src.rfind('\n',0,start)+1
    return block(src,start)

SHIMS=r'''
class Context { Object getContentResolver(){return null;} }
class Settings { static class Global {static final String BOOT_COUNT="boot";static int getInt(Object o,String k,int d){return 93;}} }
class SystemClock { static long elapsedRealtime(){return System.nanoTime()/1000000L;} }
class JSONObject {final java.util.Map<String,Object> m=new java.util.concurrent.ConcurrentHashMap<>(); JSONObject put(String k,Object v){m.put(k,v);return this;} boolean optBoolean(String k){return optBoolean(k,false);} boolean optBoolean(String k,boolean d){Object v=m.get(k);return v==null?d:(Boolean)v;} String optString(String k){return optString(k,"");} String optString(String k,String d){Object v=m.get(k);return v==null?d:(String)v;} }
class Prefs {final java.util.Map<String,Object> m=new java.util.concurrent.ConcurrentHashMap<>(); boolean writes=true;boolean contains(String k){return m.containsKey(k);}boolean getBoolean(String k,boolean d){return (boolean)m.getOrDefault(k,d);} String getString(String k,String d){return (String)m.getOrDefault(k,d);}long getLong(String k,long d){return ((Number)m.getOrDefault(k,d)).longValue();}int getInt(String k,int d){return ((Number)m.getOrDefault(k,d)).intValue();} Editor edit(){return new Editor(this);} static class Editor {final Prefs p;final java.util.Map<String,Object> add=new java.util.HashMap<>();final java.util.Set<String> remove=new java.util.HashSet<>();Editor(Prefs p){this.p=p;} Editor putBoolean(String k,boolean v){add.put(k,v);return this;}Editor putString(String k,String v){add.put(k,v);return this;}Editor putInt(String k,int v){add.put(k,v);return this;}Editor putLong(String k,long v){add.put(k,v);return this;}Editor remove(String k){remove.add(k);return this;}void apply(){commit();}boolean commit(){for(String k:remove)p.m.remove(k);p.m.putAll(add);return p.writes;} } }
class RootBridge {static void requireWorkerThread(){} static class Result {final int code;final String output;Result(int c,String s){code=c;output=s;}}static Result rootShell(Context c,String s,long t){return new Result(0,"");}}
class ProxyAdblockSession {static final Object LOCK=new Object();}
class ProxyAdblockCoordinator {static void exit(Context c){} }
class ProxyRuntimeProfile {}
class ProxyRuntimeSettings {static final String APPLIED_RUNTIME_REVISION_KEY="revision";static final int RUNTIME_REVISION=154;}
class ProxyContinuity {enum ProcessState{ALIVE,DEAD,UNKNOWN};static boolean preserveRunning(ProcessState s,boolean old){return s==ProcessState.ALIVE||s==ProcessState.UNKNOWN&&old;}}
class RootStartupProbe {static class Result {ProxyContinuity.ProcessState process=ProxyContinuity.ProcessState.ALIVE;String installedCoreToken="";}static Result parse(boolean success,String output){Result r=new Result();r.process=ProxyContinuity.ProcessState.UNKNOWN;return r;}}
class RootAutostart {static volatile boolean cancelFails=false;static volatile java.util.concurrent.CountDownLatch cancelled;static boolean confirmedRunningThisBoot(Context c){return true;}static void cancelCurrentBoot(Context c)throws java.io.IOException{if(cancelled!=null)cancelled.countDown();if(cancelFails)throw new java.io.IOException("cancel failed");}}
'''

def projection(src):
    current='STOP_GENERATION' in src
    fields='''private static final ProxyControlEpoch CONTROL_LOCK=new ProxyControlEpoch();
final Context context=new Context(); final Prefs prefs=new Prefs();
volatile Runnable probeHook; volatile java.util.concurrent.CountDownLatch healthEntered,healthRelease;
volatile String integrity="healthy"; volatile boolean plane=true; volatile boolean cleanup=true;
int probes=0; int stopCalls=0;
interface Progress{void onStage(String text);} static void stage(Progress p,String s){if(p!=null)p.onStage(s);}
RootStartupProbe.Result probeStartupRuntime(){probes++;if(probeHook!=null)probeHook.run();return new RootStartupProbe.Result();}
JSONObject networkHealth()throws Exception{if(healthEntered!=null)healthEntered.countDown();if(healthRelease!=null&&!healthRelease.await(6,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("fixture timed out");return new JSONObject().put("networkIntegrity",integrity).put("dataPlaneHealthy",plane);}
JSONObject runJsonAllowMissing(String a,JSONObject missing){stopCalls++;return new JSONObject().put("ok",cleanup).put("running",!cleanup).put("message","fixture cleanup failed");}
void ensureContinuityService(boolean wanted){} String setAutoStart(boolean on){return "";}
'''
    if current:
        a=src.index('    private static final Object INTENT_LOCK=');b=src.index('    private static final ReentrantLock HEALTH_CHECKER_LOCK',a)
        fields+=src[a:b]
    selected=[]
    signatures=['private JSONObject startOwned(', 'JSONObject startManual(',
        'JSONObject startIfWanted(ProxyRuntimeProfile profile,java.util.function.BooleanSupplier',
        'JSONObject stop()', 'JSONObject stop(Progress progress)', 'boolean adoptBootRuntime()']
    for sig in signatures:selected.append(method(src,sig))
    selected.append(method(src,'private static final class StartupTrace'))
    if current:
        selected.extend(method(src,sig) for sig in ['private void rollbackUnpublishedStart(', 'JSONObject startIfAutomationAllowed(', 'private void beginMatchingRecoveryScope('])
    full=method(src,'private JSONObject startInternal(')
    prefix=full[:full.index('        int restartControllerPort=')]
    # The branch is literal production bytes. The omitted path is never exercised.
    suffix='''throw new UnsupportedOperationException("new runtime omitted in host projection");
        }finally{'''+('if(ownIntent)START_INTENT.remove();' if current else '')+'''CONTROL_LOCK.unlock();}}
'''
    selected.append(prefix+suffix)
    text='import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.locks.*;\n'+SHIMS+'\nclass RootProxyManager {\n'+fields+'\n'+'\n'.join(selected)+'\n}\n'
    # The Android class name is a boundary alias, not altered decision logic.
    text=text.replace('android.provider.Settings.Global','Settings.Global')
    return text

HARNESS=r'''
import java.io.*;import java.util.concurrent.*;
public class AppRecovery93Host {
static void yes(boolean v,String s){if(!v)throw new AssertionError(s);}
static RootProxyManager fresh(){RootProxyManager m=new RootProxyManager();m.prefs.edit().putBoolean("proxyRootWanted",true).putBoolean("proxyRootRuntimeRunning",true).putBoolean("proxyRootAutoStart",false).apply();return m;}
static void check(String name,Callable<Void> test){try{test.call();System.out.println(name+" PASS");}catch(Throwable x){System.out.println(name+" FAIL "+x.getClass().getSimpleName()+":"+x.getMessage());}}
public static void main(String[] args)throws Exception{
check("supplier-stop",()->{RootProxyManager m=fresh();try{m.startIfWanted(new ProxyRuntimeProfile(),()->{try{m.stop();return true;}catch(Exception x){throw new RuntimeException(x);}});}catch(IOException ok){}yes(!m.prefs.getBoolean("proxyRootWanted",true),"stop revived wanted");yes(m.probes==0,"revoked call reached probe");return null;});
check("late-network",()->{RootProxyManager m=fresh();m.prefs.edit().putBoolean("proxyRootRuntimeRunning",false).apply();java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);m.probeHook=()->current.set(false);boolean refused=false;try{m.startIfWanted(new ProxyRuntimeProfile(),current::get);}catch(IOException ok){refused=true;}yes(refused,"old network accepted");yes(!m.prefs.getBoolean("proxyRootRuntimeRunning",true),"published runtime on old network");return null;});
check("adoption-data-plane",()->{RootProxyManager m=fresh();m.prefs.edit().putBoolean("proxyRootAutoStart",true).apply();m.plane=false;yes(!m.adoptBootRuntime(),"PID-only adoption");yes(!m.prefs.m.containsKey("proxyRootBootRestoreSuccessAt"),"published incomplete success");return null;});
check("cleanup-failure",()->{RootProxyManager m=fresh();m.cleanup=false;boolean refused=false;try{m.stop();}catch(IOException ok){refused=true;}yes(refused,"false cleanup reported success");yes(m.prefs.getBoolean("proxyRootRuntimeRunning",false),"lost runtime record");yes(!m.prefs.getBoolean("proxyRootWanted",true),"wanted not revoked");return null;});
check("queued-manual",()->{RootProxyManager m=fresh();java.lang.reflect.Field f=RootProxyManager.class.getDeclaredField("CONTROL_LOCK");f.setAccessible(true);ProxyControlEpoch c=(ProxyControlEpoch)f.get(null);ExecutorService e=Executors.newFixedThreadPool(2);RootAutostart.cancelled=new CountDownLatch(1);c.lock();try{Future<Boolean> start=e.submit(()->{try{m.startManual(new ProxyRuntimeProfile(),null);return true;}catch(IOException refused){return false;}});Thread.sleep(60);Future<Boolean> stop=e.submit(()->{try{m.stop();return true;}catch(IOException refused){return false;}});boolean promptly=RootAutostart.cancelled.await(300,TimeUnit.MILLISECONDS);if(promptly){yes(!stop.get(3,TimeUnit.SECONDS),"lock held stop must remain unconfirmed");}c.unlock();yes(!start.get(4,TimeUnit.SECONDS),"queued manual resurrected intent");yes(promptly,"stop revocation waited for control lock");stop.get(4,TimeUnit.SECONDS);yes(!m.prefs.getBoolean("proxyRootWanted",true),"stop intent lost");}finally{try{c.unlock();}catch(IllegalMonitorStateException ignored){}e.shutdownNow();e.awaitTermination(5,TimeUnit.SECONDS);RootAutostart.cancelled=null;}return null;});
check("healthy-existing",()->{RootProxyManager m=fresh();JSONObject r=m.startIfWanted(new ProxyRuntimeProfile(),()->true);yes(r.optBoolean("alreadyRunning"),"existing healthy core replaced");yes(m.probes==1,"unexpected deployment");return null;});
CURRENT_TESTS
}
}
'''
EXTRA=r'''
check("durable-stop-failure",()->{RootProxyManager m=fresh();m.prefs.writes=false;RootAutostart.cancelled=new CountDownLatch(1);boolean refused=false;try{m.stop();}catch(IOException ok){refused=true;}yes(refused,"commit failure hidden");yes(RootAutostart.cancelled.getCount()==0,"native cancellation skipped");yes(m.prefs.getBoolean("proxyRootRuntimeRunning",false),"unverified runtime lost");RootAutostart.cancelled=null;return null;});
check("unpublished-start-cleanup",()->{RootProxyManager m=fresh();java.lang.reflect.Method r=RootProxyManager.class.getDeclaredMethod("rollbackUnpublishedStart");r.setAccessible(true);m.prefs.edit().putBoolean("proxyRootWanted",false).apply();r.invoke(m);yes(!m.prefs.getBoolean("proxyRootRuntimeRunning",true),"entity cleanup not reflected");yes(!m.prefs.getBoolean("proxyRootWanted",true),"cleanup revived intent");m.cleanup=false;try{r.invoke(m);throw new AssertionError("cleanup failure accepted");}catch(java.lang.reflect.InvocationTargetException expected){yes(expected.getCause() instanceof IOException,"wrong cleanup error");}yes(m.prefs.getBoolean("proxyRootRuntimeRunning",false),"failed cleanup incorrectly idle");yes("unknown".equals(m.prefs.getString("proxyNetworkIntegrity","")),"failed cleanup missing unknown state");return null;});
check("shared-deadline-phases",()->{try{RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime()+170000);yes(RootProxyManager.automaticRootTimeout(20000)<=5000,"reserve consumed");RootProxyManager.automaticStartCompleted();yes(RootProxyManager.automaticRootTimeout(20000)==20000,"startup reserve not released");boolean nested=false;try{RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime()+999999);}catch(IllegalStateException ok){nested=true;}yes(nested,"nested budget reset");}finally{RootProxyManager.endAutomaticRecoveryScope();}yes(RootProxyManager.automaticRootTimeout(20000)==20000,"scope leaked");return null;});
check("matching-exhausted-admission",()->{RootProxyManager m=fresh();m.prefs.edit().putBoolean("networkMatchEnabled",true).putInt("proxyRecoveryBootCount",93).putLong("proxyRecoveryDeadlineElapsed",SystemClock.elapsedRealtime()+300000).putInt("proxyRecoveryStarts",6).apply();boolean refused=false;try{m.startIfAutomationAllowed(new ProxyRuntimeProfile(),()->true);}catch(IOException ok){refused=true;}yes(refused,"network-match bypassed shared attempt limit");yes(m.probes==0,"exhausted match reached probe");yes(m.prefs.getInt("proxyRecoveryStarts",0)==6,"attempt ledger changed");return null;});
check("matching-persisted-reservation",()->{RootProxyManager m=fresh();java.lang.reflect.Field f=RootProxyManager.class.getDeclaredField("STOP_GENERATION");f.setAccessible(true);long generation=((java.util.concurrent.atomic.AtomicLong)f.get(null)).get();java.lang.reflect.Method r=RootProxyManager.class.getDeclaredMethod("beginMatchingRecoveryScope",long.class);r.setAccessible(true);for(int n=1;n<=6;n++){m.prefs.edit().remove("proxyRecoveryMatchingAttemptElapsed").apply();try{r.invoke(m,generation);yes(m.prefs.getInt("proxyRecoveryStarts",0)==n,"admission did not persist count");}finally{RootProxyManager.endAutomaticRecoveryScope();}}try{r.invoke(m,generation);throw new AssertionError("seventh admission accepted");}catch(java.lang.reflect.InvocationTargetException expected){yes(expected.getCause() instanceof IOException,"wrong admission error");}return null;});

'''

def run(src,epoch,label,java):
    current='STOP_GENERATION' in src
    with tempfile.TemporaryDirectory(prefix='hetu-app93-') as d:
        d=pathlib.Path(d);(d/'RootProxyManager.java').write_text(projection(src));(d/'ProxyControlEpoch.java').write_text(epoch.replace('package io.github.xgl34222220.hetu;',''))
        (d/'AppRecovery93Host.java').write_text(HARNESS.replace('CURRENT_TESTS',EXTRA if current else ''))
        c=subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(d),*[str(x) for x in d.glob('*.java')]],capture_output=True,text=True)
        if c.returncode:raise RuntimeError(c.stderr)
        p=subprocess.run([java,'-cp',str(d),'AppRecovery93Host'],capture_output=True,text=True,timeout=30)
        lines=p.stdout.strip().splitlines();print(label+'\n'+p.stdout,flush=True)
        return {'sourceSha256':hashlib.sha256(src.encode()).hexdigest(),'checks':lines,'exit':p.returncode,'stderr':p.stderr}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output');a=parser.parse_args()
    java=shutil.which('java') or '/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
    now=(ROOT/MAIN/'RootProxyManager.java').read_text();epoch=(ROOT/MAIN/'ProxyControlEpoch.java').read_text()
    before=subprocess.check_output(['git','show',BASE+':'+MAIN+'RootProxyManager.java'],cwd=ROOT,text=True)
    oldepoch=subprocess.check_output(['git','show',BASE+':'+MAIN+'ProxyControlEpoch.java'],cwd=ROOT,text=True)
    b=run(before,oldepoch,'baseline cff',java);c=run(now,epoch,'current reconstruction',java)
    report={'baseline':b,'current':c,'scope':'exact entry methods and literal existing-runtime branch; deployment path omitted; Android/root are shims; not Android, AOSP or phone execution'}
    if a.output:pathlib.Path(a.output).write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    want=['supplier-stop','late-network','adoption-data-plane','cleanup-failure','queued-manual']
    assert all(any(line.startswith(n+' FAIL ') for line in b['checks']) for n in want),b
    assert len(c['checks'])==11 and all(' PASS' in line for line in c['checks']),c
    assert c['exit']==0
if __name__=='__main__':main()
