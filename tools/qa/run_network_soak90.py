#!/usr/bin/env python3
"""Wall-clock socket soak of actual controller/reducer/coalescer Java in a host fixture.

No Android, Root rules, radio handover or real subscription is exercised. The local
HTTP controller and explicit proxy deliberately return failures and delayed results.
Samples bind the verbatim compiled production sources and are retained individually.
"""
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'


def method(source, name):
    match = re.search(r'(?m)^    private int ' + name + r'\(', source)
    if not match:
        raise ValueError('Missing actual egress method')
    start = source.index('{', match.start())
    level = 0
    for token in re.finditer(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[{}]', source[start:]):
        if token.group() == '{':
            level += 1
        elif token.group() == '}':
            level -= 1
            if not level:
                return source[match.start():start + token.end()]
    raise ValueError('Incomplete production egress method')


STUBS = {
    'android/content/SharedPreferences.java': '''package android.content; import java.util.*;
public interface SharedPreferences { Map<String,?> getAll(); boolean getBoolean(String k,boolean d); String getString(String k,String d); }''',
    'android/content/Context.java': '''package android.content; import java.util.*;
public class Context { public final Map<String,Object> values=Collections.synchronizedMap(new HashMap<>());
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences(){
  public Map<String,?> getAll(){synchronized(values){return new HashMap<>(values);}}
  public boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);}
  public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
 };}}
''',
    'android/net/Uri.java': '''package android.net; public final class Uri { public static String encode(String text){return java.net.URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}}''',
    'android/os/SystemClock.java': '''package android.os; public final class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}public static void sleep(long n){try{Thread.sleep(n);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}''',
    'io/github/xgl34222220/hetu/MihomoStartupConfig.java': '''package io.github.xgl34222220.hetu; final class MihomoStartupConfig {static final int CONTROLLER_PORT=9090;}''',
}

HARNESS = r'''package io.github.xgl34222220.hetu;
import android.content.*; import java.lang.reflect.*; import java.util.*; import java.util.concurrent.*; import java.nio.file.*; import java.time.*; import org.json.*;
public final class NetworkSoak90Host {
 EGRESS_METHOD
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 static final class ManualExecutor implements java.util.concurrent.Executor {final ArrayDeque<Runnable> queue=new ArrayDeque<>();public void execute(Runnable task){queue.add(task);}void drain(){while(!queue.isEmpty())queue.remove().run();}}
 static void ready(NetworkEpoch<String> epochs,String network,String dns){epochs.available(network);epochs.capabilities(network,true,"physical");epochs.links(network,dns);}
 static JSONObject request(MihomoControllerClient client,String path,int timeout)throws Exception {
  Method m=MihomoControllerClient.class.getDeclaredMethod("request",String.class,String.class,JSONObject.class,int.class);m.setAccessible(true);
  try{return (JSONObject)m.invoke(client,"GET",path,null,timeout);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
 }
 public static void main(String[] args)throws Exception{
  int controller=Integer.parseInt(args[0]),proxy=Integer.parseInt(args[1]);long duration=Long.parseLong(args[2]),interval=Long.parseLong(args[3]);
  Path samples=Path.of(args[4]);Context context=new Context();context.values.put("proxyControllerPort",controller);context.values.put("proxyControllerSecret","fixture-secret");
  MihomoControllerClient client=new MihomoControllerClient(context);NetworkSoak90Host egress=new NetworkSoak90Host();NetworkEpoch<String> epochs=new NetworkEpoch<>();ProxyNetworkState physical=new ProxyNetworkState();
  ready(epochs,"wifi","dns-a");int cycles=0,authFailures=0,timeouts=0,staleRejected=0,offlineCycles=0,accepted=0,egressOk=0,queuedMax=0;long started=System.nanoTime(),deadline=started+duration*1000000L;
  ExecutorService lateWorker=Executors.newSingleThreadExecutor();
  try{do{
   long cycleStart=System.nanoTime();int mode=cycles%5;String scenario="steady";
   if(mode==1){scenario="401-then-auth-restored";context.values.put("proxyControllerSecret","incorrect-fixture");try{client.version();throw new AssertionError("401 accepted");}catch(MihomoControllerClient.ControllerHttpException expected){require(expected.statusCode==401,"401 identity");authFailures++;}context.values.put("proxyControllerSecret","fixture-secret");}
   if(mode==2){scenario="timeout-then-next-request";try{request(client,"/slow",100);throw new AssertionError("slow accepted");}catch(java.net.SocketTimeoutException expected){timeouts++;}}
   if(mode==3){scenario="late-result-after-handover";NetworkEpoch.Snapshot<String> old=epochs.snapshot();Future<JSONObject> pending=lateWorker.submit(()->request(client,"/slow",2000));Thread.sleep(75);ready(epochs,"cell-"+cycles,"dns-cell");JSONObject reply=pending.get(3,TimeUnit.SECONDS);require(reply.optString("version").equals("fixture"),"real delayed socket result");require(!epochs.isCurrent(old),"stale result accepted");staleRejected++;}
   if(mode==4){scenario="blocked-offline-reconnect";String current=epochs.snapshot().network;epochs.blocked(current,true);require(epochs.snapshot().network==null,"blocked eligible");epochs.lost(current);require(epochs.snapshot().network==null,"offline eligible");ready(epochs,"wifi-"+cycles,"dns-reconnected");offlineCycles++;}
   NetworkEpoch.Snapshot<String> observed=epochs.snapshot();require(client.version().optString("version").equals("fixture"),"healthy controller response");require(epochs.isCurrent(observed),"healthy identity changed");accepted++;
   require(egress.fetchEgressCode("http://controlled-egress.invalid/generate_204",proxy)==204,"explicit proxy probe");egressOk++;
   ManualExecutor executor=new ManualExecutor();int[] recovered={0};ProxyTaskCoalescer coalescer=new ProxyTaskCoalescer(executor,()->recovered[0]++);for(int i=0;i<64;i++)coalescer.request();queuedMax=Math.max(queuedMax,executor.queue.size());require(executor.queue.size()==1,"duplicate recovery queue");executor.drain();require(recovered[0]==1,"coalesced recovery missing");coalescer.close();
   physical.available("live-"+cycles);physical.capabilities("live-"+cycles,true,true,false,false);require(physical.state()==ProxyNetworkState.State.UNVERIFIED,"validation invented");physical.blocked("live-"+cycles,true);require(physical.underlying()==null,"blocked underlying");physical.blocked("live-"+cycles,false);
   cycles++;JSONObject sample=new JSONObject().put("sequence",cycles).put("at",Instant.now().toString()).put("elapsedMs",(System.nanoTime()-started)/1000000).put("cycleMs",(System.nanoTime()-cycleStart)/1000000).put("scenario",scenario).put("epoch",epochs.snapshot().epoch).put("controllerHealthy",true).put("egress204",true).put("modelRecoveryQueue",executor.queue.size()).put("ok",true);Files.writeString(samples,sample.toString()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
   long wait=interval-(System.nanoTime()-cycleStart)/1000000;if(wait>0&&System.nanoTime()<deadline)Thread.sleep(wait);
  }while(System.nanoTime()<deadline);
  long elapsed=(System.nanoTime()-started)/1000000;require(elapsed>=duration,"duration too short");System.out.println(new JSONObject().put("cycles",cycles).put("wallTimeMs",elapsed).put("healthyControllerResponses",accepted).put("proxy204Responses",egressOk).put("expected401Failures",authFailures).put("expectedTimeouts",timeouts).put("lateResultsRejectedByActualEpoch",staleRejected).put("blockedOfflineReconnectModelCycles",offlineCycles).put("maximumRecoveryTasksQueued",queuedMax).put("failures",0).toString());
  }finally{lateWorker.shutdownNow();}
 }
}'''


def run(duration, interval, output):
    output.mkdir(parents=True, exist_ok=True)
    samples = output / 'samples.jsonl'
    if samples.exists():
        raise ValueError('Evidence directory already contains samples; choose a new path')
    events = output / 'socket-events.jsonl'
    event_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            start = time.monotonic()
            if self.server.fixture_role == 'proxy':
                code = 204 if self.path == 'http://controlled-egress.invalid/generate_204' else 502
                payload = b''
            elif self.headers.get('Authorization') != 'Bearer fixture-secret':
                code, payload = 401, b'{}'
            else:
                if self.path == '/slow':
                    time.sleep(.4)
                code, payload = 200, b'{"version":"fixture"}'
            try:
                self.send_response(code)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with event_lock:
                    with events.open('a') as stream:
                        stream.write(json.dumps({'at': datetime.now(timezone.utc).isoformat(), 'role': self.server.fixture_role,
                                                 'path': self.path, 'code': code, 'wallTimeMs': round((time.monotonic() - start) * 1000)}) + '\n')

    servers = []
    started_at = datetime.now(timezone.utc).isoformat()
    try:
        for role in ('controller', 'proxy'):
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.fixture_role = role
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        with tempfile.TemporaryDirectory(prefix='hetu-network90-host-') as tmp:
            work = Path(tmp)
            sources = {}
            for name in ('MihomoControllerClient', 'NetworkEpoch', 'ProxyTaskCoalescer', 'ProxyNetworkState'):
                actual = SOURCE / (name + '.java')
                data = actual.read_bytes()
                (work / (name + '.java')).write_bytes(data)
                sources[str(actual.relative_to(ROOT))] = hashlib.sha256(data).hexdigest()
            service = SOURCE / 'ProxyNetworkMatchService.java'
            egress_method = method(service.read_text(), 'fetchEgressCode')
            sources[str(service.relative_to(ROOT))] = hashlib.sha256(service.read_bytes()).hexdigest()
            sources['fetchEgressCodeMethodSha256'] = hashlib.sha256(egress_method.encode()).hexdigest()
            for name, text in STUBS.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            for actual in (ROOT / 'tools/qa/fixtures/android-json').glob('*.java'):
                (work / actual.name).write_bytes(actual.read_bytes())
            (work / 'NetworkSoak90Host.java').write_text(HARNESS.replace('EGRESS_METHOD', egress_method))
            compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
            subprocess.run([*compiler, '-d', str(work), *map(str, work.rglob('*.java'))], check=True, capture_output=True, text=True)
            start = time.monotonic()
            result = subprocess.run(['java', '-cp', str(work), 'io.github.xgl34222220.hetu.NetworkSoak90Host',
                                     str(servers[0].server_port), str(servers[1].server_port), str(round(duration * 1000)),
                                     str(round(interval * 1000)), str(samples.resolve())], capture_output=True, text=True,
                                    timeout=duration + 60)
            wall_seconds = time.monotonic() - start
            (output / 'java-stdout.txt').write_text(result.stdout)
            (output / 'java-stderr.txt').write_text(result.stderr)
            report = {'schema': 1, 'startedAt': started_at, 'completedAt': datetime.now(timezone.utc).isoformat(),
                      'requestedDurationSeconds': duration, 'processWallTimeSeconds': wall_seconds, 'intervalSeconds': interval,
                      'sourcesSha256': sources, 'javaExit': result.returncode,
                      'actualScope': ['Verbatim production MihomoControllerClient socket/auth/timeout path',
                                      'Verbatim production fetchEgressCode HTTP explicit-proxy method',
                                      'Verbatim production NetworkEpoch and ProxyNetworkState reducers',
                                      'Verbatim production ProxyTaskCoalescer repeated-operation queue'],
                      'boundary': 'Host fixture with Android preference/context shims and local HTTP controller/proxy. Not Android service, Root firewall, Mihomo core, radio handover, public internet, Google/GMS, subscription or device evidence.',
                      'samplesSha256': hashlib.sha256(samples.read_bytes()).hexdigest() if samples.exists() else None,
                      'socketEventsSha256': hashlib.sha256(events.read_bytes()).hexdigest() if events.exists() else None}
            if result.returncode == 0:
                report['result'] = json.loads(result.stdout)
            else:
                report['failure'] = result.stderr[-6000:]
            (output / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
            print(json.dumps(report, ensure_ascii=False))
            if result.returncode:
                raise RuntimeError('Actual Java soak failed; raw evidence preserved at ' + str(output))
            if wall_seconds < duration:
                raise AssertionError('Wall time shorter than requested duration')
            return report
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--duration', type=float, default=900)
    parser.add_argument('--interval', type=float, default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.duration <= 0 or args.interval < 0:
        parser.error('duration must be positive; interval must be nonnegative')
    run(args.duration, args.interval, args.output)
