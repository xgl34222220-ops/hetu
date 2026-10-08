#!/usr/bin/env python3
"""Original host fault fixture runs actual service queue methods, no Android/Root/network.

Only Android dependencies at this boundary are replaced; the production reducer,
coalescer, scheduling/publication methods and scheduler policy are compiled verbatim.
"""
import argparse, hashlib, json, re, shutil, subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'


def method(source, name):
    match = re.search(r'(?m)^    (?:private |static |synchronized |final |public )*[\w<>.?]+ '+name+r'\(', source)
    if not match:
        return ''
    start = source.index('{', match.start())
    level = 0
    token = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[{}]')
    for piece in token.finditer(source, start):
        if piece.group() == '{': level += 1
        elif piece.group() == '}':
            level -= 1
            if not level: return source[match.start():piece.end()]
    raise ValueError('incomplete production method '+name)


def run(service_path):
    text = service_path.read_text()
    extra = '\n'.join(method(text, n) for n in ['queueNetworkRecovery', 'recoverQueuedNetwork', 'createMetrics'])
    fixed = bool(method(text, 'queueNetworkRecovery'))
    timer = re.search(r'private final (?:ScheduledExecutorService|ScheduledThreadPoolExecutor) metrics=(.*);', text).group(1)
    declarations = '''
    private NetworkEpoch.Snapshot<Network> pendingRecoveryRoute;
    private String pendingRecoveryReason;
    private final ProxyTaskCoalescer networkRecoveries=new ProxyTaskCoalescer(worker,this::recoverQueuedNetwork);
''' if fixed else ''
    code = '''package io.github.xgl34222220.hetu;
import java.util.*; import java.util.concurrent.*;
final class RecoveryStressHost {
 static final class Network {final int id; Network(int id){this.id=id;}}
 static final class Prefs {
  final Map<String,Object> values=new HashMap<>();
  boolean contains(String k){return values.containsKey(k);}
  boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);}
  Editor edit(){return new Editor();}
  final class Editor {
   Editor putLong(String k,long v){values.put(k,v);return this;}
   Editor putString(String k,String v){values.put(k,v);return this;}
   Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
   void apply(){}
  }
 }
 static final class ProxyNetworkJournal {
  enum Stage {NETWORK_CHANGE} enum Outcome {UNKNOWN}
  static Outcome outcome(String ignored){return Outcome.UNKNOWN;}
 }
 static final class ManualWorker extends AbstractExecutorService {
  final ArrayDeque<Runnable> queue=new ArrayDeque<>(); boolean stopped;
  public void execute(Runnable task){if(stopped)throw new RejectedExecutionException();queue.add(task);}
  public void shutdown(){stopped=true;} public List<Runnable> shutdownNow(){stopped=true;return List.of();}
  public boolean isShutdown(){return stopped;} public boolean isTerminated(){return stopped;}
  public boolean awaitTermination(long n,TimeUnit u){return stopped;}
  void drain(){while(!queue.isEmpty())queue.remove().run();}
 }
 static final class ManualTimer extends ScheduledThreadPoolExecutor {
  final ArrayDeque<Future> tasks=new ArrayDeque<>();
  ManualTimer(){super(1);}
  public ScheduledFuture<?> schedule(Runnable task,long delay,TimeUnit unit){Future f=new Future(task);tasks.add(f);return f;}
  void fire(){while(!tasks.isEmpty()){Future f=tasks.remove();if(!f.cancelled)f.task.run();}}
  static final class Future implements ScheduledFuture<Object> {
   final Runnable task;boolean cancelled;Future(Runnable task){this.task=task;}
   public boolean cancel(boolean ignored){cancelled=true;return true;} public boolean isCancelled(){return cancelled;}
   public boolean isDone(){return cancelled;} public Object get(){return null;} public Object get(long n,TimeUnit u){return null;}
   public long getDelay(TimeUnit u){return 0;} public int compareTo(Delayed d){return 0;}
  }
 }
 private final ManualWorker worker=new ManualWorker();
 private final ManualTimer metrics=new ManualTimer();
 private final Prefs prefs=new Prefs();
 private final NetworkEpoch<Network> networkEvents=new NetworkEpoch<>();
 private final ProxyNetworkState networkState=new ProxyNetworkState();
 private ScheduledFuture<?> networkRefreshTask;
 private boolean destroyed;private long lastPolicyProbeAt;
 private int attempts,executed;private long executedEpoch;
 private Runnable duringRecovery;
 private void recordEvent(ProxyNetworkJournal.Stage s,NetworkEpoch.Snapshot<Network> n,ProxyNetworkJournal.Outcome o,int c,Object t,Object p){}
 private void scheduleBootRestore(long n){} private void evaluate(){}
 private void recoverNetwork(NetworkEpoch.Snapshot<Network> n,String reason){
  attempts++;
  if(destroyed||!networkEvents.isCurrent(n)||!prefs.getBoolean("proxyRootWanted",false))return;
  executed++;executedEpoch=n.epoch;
  if(duringRecovery!=null){Runnable r=duringRecovery;duringRecovery=null;r.run();}
 }
''' + declarations + method(text, 'networkChanged') + '\n' + extra + '''
 private void ready(Network n,String dns){
  networkChanged("available",()->{networkEvents.available(n);networkState.available(n);});
  networkChanged("capabilities",()->{networkEvents.capabilities(n,true,"physical");networkState.capabilities(n,true,true,false,false);});
  networkChanged("links",()->networkEvents.links(n,dns));
 }
 public static void main(String[] args)throws Exception{
  RecoveryStressHost h=new RecoveryStressHost();h.prefs.values.put("proxyRootWanted",true);
  int max=0;
  for(int i=0;i<512;i++){
   Network n=new Network(i);h.ready(n,"DNS"+i);
   h.networkChanged("blocked",()->{h.networkEvents.blocked(n,true);h.networkState.blocked(n,true);});
   h.networkChanged("blocked",()->{h.networkEvents.blocked(n,false);h.networkState.blocked(n,false);});
   h.metrics.fire();max=Math.max(max,h.worker.queue.size());
  }
  h.worker.drain();if(h.executed!=1||h.executedEpoch!=h.networkEvents.snapshot().epoch)throw new AssertionError("latest recovery lost");
  int drainedAttempts=h.attempts;
  h.ready(new Network(999),"changed");h.metrics.fire();h.prefs.values.put("proxyRootWanted",false);h.worker.drain();
  if(h.executed!=1)throw new AssertionError("cancelled wanted recovered");
  h.prefs.values.put("proxyRootWanted",true);h.ready(new Network(1000),"DNS");h.metrics.fire();
  h.duringRecovery=()->{h.ready(new Network(1001),"last");h.metrics.fire();};h.worker.drain();
  if(h.executed!=3)throw new AssertionError("event during running attempt lost");
  ScheduledThreadPoolExecutor real=(ScheduledThreadPoolExecutor)METRICS;
  for(int i=0;i<4096;i++)real.schedule(()->{},1,TimeUnit.DAYS).cancel(false);
  int retained=real.getQueue().size();real.shutdownNow();h.metrics.shutdownNow();
  System.out.println("{\\"cycles\\":512,\\"maxQueuedRecoveries\\":"+max+",\\"drainedAttempts\\":"+drainedAttempts+",\\"latestExecuted\\":1,\\"cancelledWantedExecuted\\":0,\\"eventDuringAttemptPreserved\\":true,\\"cancelledTimersRetained\\":"+retained+"}");
 }
}'''.replace('METRICS', timer)
    with tempfile.TemporaryDirectory(prefix='hetu-recovery-host-') as temp:
        work = Path(temp)
        for name in ['NetworkEpoch', 'ProxyNetworkState', 'ProxyTaskCoalescer']:
            (work/(name+'.java')).write_text((SOURCE/(name+'.java')).read_text())
        (work/'RecoveryStressHost.java').write_text(code)
        compiler=['javac'] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
        compiled=subprocess.run([*compiler,'-d',str(work),*map(str,work.glob('*.java'))],capture_output=True,text=True)
        if compiled.returncode: raise RuntimeError(compiled.stderr)
        result=subprocess.run(['java','-cp',str(work),'io.github.xgl34222220.hetu.RecoveryStressHost'],check=True,capture_output=True,text=True)
        report=json.loads(result.stdout)
    report.update(sourceSha256=hashlib.sha256(text.encode()).hexdigest(),fixtureOnly=True,deviceOrNetworkAccess=False)
    return report


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--before',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
    result={'after':run(SOURCE/'ProxyNetworkMatchService.java')}
    if a.before: result['before']=run(a.before)
    if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
    assert result['after']['maxQueuedRecoveries']<=1
    assert result['after']['cancelledTimersRetained']==0
    if a.before:
        assert result['before']['maxQueuedRecoveries']==512
        assert result['before']['cancelledTimersRetained']==4096
