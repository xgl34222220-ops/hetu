#!/usr/bin/env python3
"""One-time bounded test.100 lifecycle migration. No UI/config/rule changes."""
from pathlib import Path
import json,hashlib
R=Path(__file__).resolve().parents[1]
J=R/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
assert 'versionName = "0.4.0-test.99"' in (R/'android-app/app/build.gradle.kts').read_text()
expected={
 'android-app/app/src/main/assets/hetu-root.sh':'f87c718f0ebb89fff1fda0e3e62c10b25302705ddc2bc69d8250b9a7c78e3341',
 'android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java':'4ba0e25c62129f31baa75c6913bac97e14daf9254cccfadb9804819fc0c9af83',
 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyNetworkMatchService.java':'77a2f8ec870a841fee1decfc5e3df58b61b1ad3acba934723842db328e1f7ad5'}
for p,h in expected.items():assert hashlib.sha256((R/p).read_bytes()).hexdigest()==h,p
changed=[]
def edit(path,fn):
 p=R/path; before=p.read_text();after=fn(before);assert before!=after,path;p.write_text(after);changed.append(path)
def rep(s,a,b):
 assert s.count(a)==1,(a[:90],s.count(a));return s.replace(a,b)
(J/'ProxyControlEpoch.java').write_text('''package io.github.xgl34222220.hetu;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
/** Nonblocking publication of observations, invalidated by every control transaction. */
final class ProxyControlEpoch {
    private final ReentrantLock gate = new ReentrantLock(true);
    private final AtomicLong generation = new AtomicLong();
    void lock() { gate.lock(); generation.incrementAndGet(); }
    boolean tryLock() {
        if (!gate.tryLock()) return false;
        generation.incrementAndGet(); return true;
    }
    void unlock() { gate.unlock(); }
    long observe() {
        if (gate.isLocked()) return -1;
        long ticket = generation.get();
        return gate.isLocked() ? -1 : ticket;
    }
    boolean publish(long ticket, Runnable action) {
        if (ticket < 0 || !gate.tryLock()) return false;
        try {
            if (generation.get() != ticket) return false;
            action.run(); return true;
        } finally { gate.unlock(); }
    }
}
''')
def manager(s):
 s=rep(s,'private static final ReentrantLock CONTROL_LOCK=new ReentrantLock(true);','private static final ProxyControlEpoch CONTROL_LOCK=new ProxyControlEpoch();\n    static long observationTicket(){return CONTROL_LOCK.observe();}\n    static boolean publishObservation(long ticket,Runnable publish){return CONTROL_LOCK.publish(ticket,publish);}')
 a=s.index('    JSONObject startIfWanted(');b=s.index('\n    JSONObject replaceRunningAfterUpgrade',a)
 seg=rep(s[a:b],'        CONTROL_LOCK.lock();','        if(!CONTROL_LOCK.tryLock())return new JSONObject().put("ok",true).put("cancelled",true).put("reason","control-busy");')
 s=s[:a]+seg+s[b:]
 s=rep(s,'proxyLastNetworkSessionResetReason",','proxyLastNetworkSessionResetReason","proxyLastNetworkObservationAt","proxyLastNetworkObservationReason",')
 return s
edit(str((J/'RootProxyManager.java').relative_to(R)),manager)
def service(s):
 s=rep(s,'private long lastPolicyProbeAt=-90000L;','private volatile long lastPolicyProbeAt=-90000L;')
 a=s.index('    private void handleDefaultNetwork(Network n){');b=s.index('\n    private void scheduleWorker',a)
 s=s[:a]+'''    private void handleDefaultNetwork(Network n){
        final long generation=networkHandover.available(n);
        if(generation==0L||!prefs.getBoolean("proxyRootWanted",false))return;
        scheduleWorker(()->{
            if(destroyed||!networkHandover.isCurrent(n,generation)||!prefs.getBoolean("proxyRootWanted",false))return;
            Network active=cm.getActiveNetwork();
            if(active==null||!active.equals(n))return;
            long ticket=RootProxyManager.observationTicket();
            // A new BEST network does not prove that the old one disconnected.
            // Do not DELETE every connection merely because its start precedes a callback.
            RootProxyManager.publishObservation(ticket,()->prefs.edit()
                    .putLong("proxyLastNetworkObservationAt",System.currentTimeMillis())
                    .putString("proxyLastNetworkObservationReason","default-changed-connections-preserved")
                    .putBoolean("proxyRootEgressPending",true).apply());
            lastPolicyProbeAt=-90000L;
        },2500L);
    }
''' + s[b:]
 s=rep(s,'    private ProxyContinuity.ProcessState probeCoreState(){\n        try{','    private ProxyContinuity.ProcessState probeCoreState(){\n        final long ticket=RootProxyManager.observationTicket();\n        if(ticket<0L)return ProxyContinuity.ProcessState.UNKNOWN;\n        try{')
 s=rep(s,'                prefs.edit().putBoolean("proxyRootRuntimeRunning",state==ProxyContinuity.ProcessState.ALIVE).apply();','                if(!RootProxyManager.publishObservation(ticket,()->prefs.edit()\n                        .putBoolean("proxyRootRuntimeRunning",state==ProxyContinuity.ProcessState.ALIVE).apply()))\n                    return ProxyContinuity.ProcessState.UNKNOWN;')
 s=rep(s,'    private void maintainProxyRuntime(){\n        try{\n            if(!prefs.getBoolean("proxyRootWanted",false))return;','    private void maintainProxyRuntime(){\n        try{\n            if(destroyed||!prefs.getBoolean("proxyRootWanted",false)||RootProxyManager.observationTicket()<0L)return;')
 a=s.index('    private void checkLiveNetworkIntegrity(){');b=s.index('\n    private void probeEgressIfPending()',a)
 seg=rep(s[a:b],'        try{','        final long ticket=RootProxyManager.observationTicket();\n        if(ticket<0L)return;\n        try{')
 seg=rep(seg,'            editor.apply();','            RootProxyManager.publishObservation(ticket,editor::apply);')
 seg=rep(seg,'            prefs.edit().putString("proxyNetworkIntegrity","unknown").apply();','            RootProxyManager.publishObservation(ticket,()->prefs.edit().putString("proxyNetworkIntegrity","unknown").apply());')
 s=s[:a]+seg+s[b:]
 a=s.index('    private void probeEgressIfPending(){');b=s.index('\n    private void updateAdblockMetrics',a)
 seg=rep(s[a:b],'        Network network=','        final long ticket=RootProxyManager.observationTicket();\n        if(ticket<0L)return;\n        Network network=')
 seg=rep(seg,'        edit.apply();','        RootProxyManager.publishObservation(ticket,edit::apply);')
 return s[:a]+seg+s[b:]
edit(str((J/'ProxyNetworkMatchService.java').relative_to(R)),service)
def shell(s):
 s=rep(s,'acquire_lock(){\n  mkdir','acquire_lock(){\n  [ "$LOCK_HELD" != 1 ] || return 0\n  mkdir')
 a=s.index('cleanup_snapshot_begin(){')
 s=s[:a]+'''cleanup_snapshot_read(){
  CS_RAW=$("$1" -t "$2" -S 2>&1); CS_RC=$?
  if [ "$CS_RC" = 0 ]; then printf '%s\\n' "$CS_RAW"; return 0; fi
  case "$CS_RAW" in
    *"can't initialize"*"Table does not exist"*) return 0;;
    *) return 1;;
  esac
}
''' + s[a:]
 for f in (4,6):
  for t in ('mangle','nat','filter'):
   s=rep(s,f'$(xt{f} -t {t} -S 2>/dev/null) || CLEAN',f'$(cleanup_snapshot_read xt{f} {t}) || CLEAN')
 s=rep(s,'  root; mode "$M" || fail "运行模式无效";','  root; acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"; mode "$M" || fail "运行模式无效";')
 s=rep(s,'  rm -f "$START_ERROR"; start_stage "preflight"','  acquire_lock || fail "另一个代理网络事务正在执行，请稍后重试"\n  rm -f "$START_ERROR"; start_stage "preflight"')
 a=s.index('tcp_listen(){');b=s.index('\nready(){',a)
 s=s[:a]+'''# One ss invocation per readiness/port-check sample. /proc is a bounded fallback.
LISTEN_SNAPSHOT_VALID=0
LISTEN_PORTS=''
listen_snapshot(){
  LISTEN_SNAPSHOT_VALID=0; LISTEN_PORTS=''
  if has ss; then
    LS_RAW=$(ss -lnut 2>/dev/null); LS_RC=$?
    if [ "$LS_RC" = 0 ]; then
      LISTEN_PORTS=$(printf '%s\\n' "$LS_RAW" | awk '
        ($1=="tcp" || $1=="udp" || $1=="tcp6" || $1=="udp6") && NF>=5 {
          proto=substr($1,1,3); n=split($5,a,":"); p=a[n];
          if(p ~ /^[0-9]+$/) printf " %s:%s ",proto,p
        }')
      LISTEN_SNAPSHOT_VALID=1; return 0
    fi
  fi
  [ -r /proc/net/tcp ] && [ -r /proc/net/udp ] || return 1
  LISTEN_PORTS=$(awk '
    function dec(h, i,n,c) {n=0; for(i=1;i<=length(h);i++){c=index("0123456789ABCDEF",toupper(substr(h,i,1)))-1; if(c<0)return -1; n=n*16+c} return n}
    FNR>1 && (FILENAME ~ /udp/ || $4=="0A") {
      n=split($2,a,":");p=dec(a[n]);if(p>=0)printf " %s:%s ",(FILENAME ~ /udp/?"udp":"tcp"),p
    }' /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6 2>/dev/null) || return 1
  LISTEN_SNAPSHOT_VALID=1
}
tcp_listen(){ [ "$LISTEN_SNAPSHOT_VALID" = 1 ] || listen_snapshot || return 1; case "$LISTEN_PORTS" in *" tcp:$1 "*) return 0;; *) return 1;; esac; }
udp_listen(){ [ "$LISTEN_SNAPSHOT_VALID" = 1 ] || listen_snapshot || return 1; case "$LISTEN_PORTS" in *" udp:$1 "*) return 0;; *) return 1;; esac; }
''' + s[b:]
 s=rep(s,'\nready(){\n  PID=','\nready(){\n  listen_snapshot || return 1\n  PID=')
 s=rep(s,'check_start_ports(){\n  M=','check_start_ports(){\n  listen_snapshot || fail "无法读取端口状态，未盲目启动核心"\n  M=')
 a=s.index('wait_ready(){');b=s.index('\nwrite_session(){',a)
 s=s[:a]+'''monotonic_seconds(){
  read -r MONO_RAW MONO_UNUSED < /proc/uptime || return 1
  MONO_SECONDS=${MONO_RAW%%.*}
  case "$MONO_SECONDS" in ''|*[!0-9]*) return 1;; esac
}
wait_ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"
  monotonic_seconds || return 3
  READY_DEADLINE=$((MONO_SECONDS+90))
  # 900 sleeps did NOT mean 90s: every old iteration performed several process
  # launches and socket queries. The outer timeout could kill startup before rollback.
  while :; do
    ready "$PID" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP" && return 0
    core_maybe_alive "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 2
    monotonic_seconds || return 3
    [ "$MONO_SECONDS" -lt "$READY_DEADLINE" ] || return 3
    sleep 0.1
  done
}
''' + s[b:]
 return s
edit('android-app/app/src/main/assets/hetu-root.sh',shell)
f=R/'tests/ui-runtime-baseline.json';m=json.loads(f.read_text())
for p in changed:
 if p in m:m[p]=hashlib.sha256((R/p).read_bytes()).hexdigest()
f.write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
f=R/'android-app/app/build.gradle.kts';s=f.read_text();s=rep(s,'versionCode = 499','versionCode = 500');s=rep(s,'versionName = "0.4.0-test.99"','versionName = "0.4.0-test.100"');f.write_text(s)
print('test.100 lifecycle migration complete')
