package io.github.xgl34222220.bichen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** DNS-only VPN: only 10.111.0.2/32 enters the tunnel, never ordinary app traffic. */
public final class DnsVpnService extends VpnService {
    public static final String ACTION_START = "io.github.xgl34222220.bichen.VPN_START";
    public static final String ACTION_STOP = "io.github.xgl34222220.bichen.VPN_STOP";
    public static final String ACTION_RESTART = "io.github.xgl34222220.bichen.VPN_RESTART";
    public static final String ACTION_RELOAD = "io.github.xgl34222220.bichen.VPN_RELOAD";
    public static volatile boolean running;
    private static final Object MODE_LOCK = new Object();
    private static volatile DnsVpnService latestInstance;
    private static final String CHANNEL = "dns_protection";
    private static final int NOTIFICATION_ID = 101;
    private SharedPreferences prefs;
    private RuleStore rules;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statistics = Executors.newSingleThreadScheduledExecutor();
    private final Set<Closeable> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Closeable, Boolean>());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger commandSequence = new AtomicInteger();
    private final AtomicLong queries = new AtomicLong(), blocked = new AtomicLong(), errors = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong(), fallbackCount = new AtomicLong(), latencyMs = new AtomicLong();
    private final DnsCache cache = new DnsCache();
    private final Object outputLock = new Object(), logsLock = new Object();
    private volatile ParcelFileDescriptor tunnel;
    private volatile FileOutputStream output;
    private volatile ThreadPoolExecutor workers;
    private volatile InetAddress activeUpstream;
    private volatile String activeTransport = "udp", activeDohProvider = "cloudflare";
    private volatile DnsUpstream encryptedUpstream;
    private volatile int latestStartId;
    private volatile boolean requestedStop, destroyed, networkRunning;

    @Override public void onCreate() {
        super.onCreate();
        latestInstance = this; running = false;
        prefs = getSharedPreferences("bichen", MODE_PRIVATE);
        rules = new RuleStore(this);
        queries.set(prefs.getLong("queries", 0)); blocked.set(prefs.getLong("blocked", 0)); errors.set(prefs.getLong("errors", 0));
        cacheHits.set(prefs.getLong("cacheHits", 0)); fallbackCount.set(prefs.getLong("dnsFallbackCount", 0));
        latencyMs.set(prefs.getLong("dnsLatencyMs", 0));
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "DNS 去广告", NotificationManager.IMPORTANCE_LOW));
        statistics.scheduleWithFixedDelay(this::flushStatistics, 10, 10, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? (prefs.getBoolean("vpnWanted", false) ? ACTION_START : ACTION_STOP) : intent.getAction();
        int command = ACTION_RELOAD.equals(action) ? commandSequence.get() : commandSequence.incrementAndGet();
        showForeground("正在准备 DNS 防护…");
        if (ACTION_STOP.equals(action)) {
            requestedStop = true;
            prefs.edit().putBoolean("vpnWanted", false).commit();
            closeNetwork(); // Stop DNS immediately; a pending root request must not delay it.
            submit(() -> { if (commandSequence.get() == command) stopAndRestore(null); });
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            submit(() -> {
                try { rules.reload(); cache.clear(); } catch (Exception e) { setError("规则刷新失败：" + message(e)); }
                if (running) showForeground("DNS 防护已开启 · 普通网络流量直接连接");
                else stopAndRestore(null);
            });
            return START_STICKY;
        }
        prefs.edit().putBoolean("vpnWanted", true).commit();
        boolean restart = ACTION_RESTART.equals(action);
        submit(() -> {
            synchronized (MODE_LOCK) {
                if (destroyed || latestInstance != this || commandSequence.get() != command) return;
                requestedStop = false;
                prefs.edit().putBoolean("vpnWanted", true).commit();
                if (networkRunning && !restart) { showForeground("DNS 防护已开启 · 普通网络流量直接连接"); return; }
                if (restart) closeNetwork(); // Keep hosts paused while rebuilding application exclusions.
                try { startVpn(); }
                catch (Exception e) {
                    if (commandSequence.get() == command) stopAndRestore("DNS 防护启动失败：" + message(e));
                    else closeNetwork();
                }
            }
        });
        return START_STICKY;
    }

    private void submit(Runnable action) {
        try { lifecycle.execute(action); } catch (RejectedExecutionException ignored) { }
    }

    private void startVpn() throws Exception {
        if (VpnService.prepare(this) != null) throw new IOException("请先在 App 内授权 VPN");
        String transport = prefs.getString("dnsTransport", "udp"), provider = prefs.getString("dohProvider", "cloudflare");
        if (!"udp".equals(transport) && !"doh".equals(transport)) throw new IOException("DNS 传输设置无效");
        InetAddress configuredUpstream = "udp".equals(transport) ? numericAddress(prefs.getString("upstream", "223.5.5.5")) : null;
        if ("doh".equals(transport)) DnsUpstream.endpoint(provider);
        rules.reload();
        pauseHosts();
        if (requestedStop || destroyed) return;
        Builder builder = new Builder().setSession("辟尘 DNS 防护").setMtu(32767)
                .addAddress("10.111.0.1", 32).addDnsServer("10.111.0.2").addRoute("10.111.0.2", 32)
                .allowFamily(OsConstants.AF_INET6).setBlocking(true);
        builder.addDisallowedApplication(getPackageName());
        Set<String> excluded = new HashSet<>(prefs.getStringSet("bypassApps", Collections.emptySet()));
        int excludedCount = 0;
        Set<String> activeExcluded = new HashSet<>();
        for (String packageName : excluded) {
            if (getPackageName().equals(packageName)) continue;
            try { builder.addDisallowedApplication(packageName); excludedCount++; activeExcluded.add(packageName); }
            catch (PackageManager.NameNotFoundException ignored) { /* Uninstalled app: preserve preference for reinstall. */ }
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) builder.setConfigureIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        ParcelFileDescriptor established = builder.establish();
        if (established == null) throw new IOException("VPN 授权已被撤销或另一个 VPN 正在切换");
        if (requestedStop || destroyed) { established.close(); return; }
        synchronized (outputLock) {
            tunnel = established; output = new FileOutputStream(established.getFileDescriptor());
        }
        workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        activeUpstream = configuredUpstream;
        activeTransport = transport; activeDohProvider = provider;
        encryptedUpstream = "doh".equals(transport) ? new DnsUpstream() : null;
        cache.clear();
        int token = generation.incrementAndGet();
        networkRunning = true; running = true;
        prefs.edit().remove("vpnError").remove("dnsLastError").putInt("activeBypassCount", excludedCount)
                .putStringSet("activeBypassApps", activeExcluded).putString("activeDnsTransport", transport)
                .putString("activeDohProvider", provider).apply();
        showForeground("DNS 防护已开启 · 普通网络流量直接连接");
        Thread reader = new Thread(() -> readPackets(established, token), "bichen-dns-tun");
        reader.setDaemon(true); reader.start();
    }

    /** Record restoration intent before changing persistent module state, including failed starts. */
    private void pauseHosts() throws Exception {
        JSONObject status = RootBridge.status(this);
        if (!status.optBoolean("ok", false)) throw new IOException("无法确认模块状态：" + status.optString("message", "请检查 Root 授权"));
        if (status.optBoolean("pendingReboot", false)) throw new IOException("模块升级待重启，请先重启手机后再开启 DNS 防护");
        if (!status.optBoolean("installed", true)) return;
        showForeground("正在同步模块规则与白名单…");
        rules.syncFromModule();
        boolean disabled = status.optBoolean("moduleDisabled", false) || status.optBoolean("moduleRemovalPending", false);
        if (disabled && !status.optBoolean("mounted", false)) return;
        if (status.optBoolean("enabled", false) || status.optBoolean("mounted", false)) {
            if (!disabled && status.optBoolean("enabled", false) && !prefs.edit().putBoolean("vpnRestoreHosts", true).commit())
                throw new IOException("无法保存模式恢复信息");
            showForeground("正在暂停 hosts 并核对应用放行条件…");
            RootBridge.Result paused = RootBridge.run(this, "pause");
            if (paused.code != 0) throw new IOException("暂停 hosts 失败：" + paused.output);
        }
        JSONObject checked = RootBridge.status(this);
        if (!checked.optBoolean("ok", false) || checked.optBoolean("enabled", false) || checked.optBoolean("mounted", false))
            throw new IOException("未确认 hosts 已停止，暂不能保证按应用放行");
    }

    private void readPackets(ParcelFileDescriptor descriptor, int token) {
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[32767];
            while (active(token)) {
                int size = input.read(buffer);
                if (size < 0) throw new IOException("VPN 接口已关闭");
                if (size == 0) continue;
                DnsPacket.Query query = DnsPacket.parse(buffer, size);
                if (query == null) {
                    byte[] reset = DnsPacket.tcpReset(buffer, size);
                    if (reset != null) { errors.incrementAndGet(); writePacket(reset, token); }
                    continue;
                }
                queries.incrementAndGet();
                if (query.queryClass == 1 && rules.isBlocked(query.domain)) {
                    blocked.incrementAndGet();
                    record(query.domain, "blocked");
                    writePacket(DnsPacket.responsePacket(query, DnsPacket.error(query, 3)), token);
                    continue;
                }
                byte[] cached = cache.get(query, token + ":" + rules.currentRevision());
                if (cached != null) {
                    // A user can edit rules while the reader is between these operations.
                    if (replyBlockedIfNeeded(query, token)) continue;
                    cacheHits.incrementAndGet(); record(query.domain, "cached");
                    writePacket(DnsPacket.responsePacket(query, cached), token);
                    continue;
                }
                ThreadPoolExecutor pool = workers;
                try {
                    if (pool == null) throw new RejectedExecutionException();
                    pool.execute(() -> forward(query, token));
                } catch (RejectedExecutionException e) {
                    if (active(token)) replyFailure(query, token, "busy");
                }
            }
        } catch (Exception e) {
            if (active(token)) submit(() -> {
                if (active(token)) stopAndRestore("DNS 接口中断：" + message(e));
            });
        }
    }

    private boolean active(int token) { return networkRunning && !requestedStop && !destroyed && generation.get() == token; }

    private void forward(DnsPacket.Query query, int token) {
        if (!active(token)) return;
        if (replyBlockedIfNeeded(query, token)) return;
        String revision = rules.currentRevision();
        long started = System.nanoTime();
        try {
            byte[] answer;
            boolean fallback = false;
            if ("doh".equals(activeTransport)) {
                DnsUpstream https = encryptedUpstream;
                if (https == null) throw new IOException("加密 DNS 上游尚未初始化");
                String first = activeDohProvider;
                try { answer = requireUsable(https.exchange(query, first)); }
                catch (IOException firstFailure) {
                    if (!active(token)) return;
                    fallback = true;
                    String alternate = "cloudflare".equals(first) ? "google" : "cloudflare";
                    try { answer = requireUsable(https.exchange(query, alternate)); }
                    catch (IOException secondFailure) { throw new IOException("两路加密 DNS 均失败，未切换明文：" + message(firstFailure) + "；" + message(secondFailure)); }
                }
            } else {
                InetAddress first = activeUpstream;
                if (first == null) throw new IOException("DNS 上游尚未初始化");
                try { answer = requireUsable(exchange(query, first, token)); }
                catch (IOException firstFailure) {
                    if (!active(token)) return;
                    fallback = true;
                    InetAddress alternate = numericAddress("1.1.1.1");
                    if (first.equals(alternate)) alternate = numericAddress("223.5.5.5");
                    try { answer = requireUsable(exchange(query, alternate, token)); }
                    catch (IOException secondFailure) { throw new IOException("两路普通 DNS 均失败：" + message(firstFailure) + "；" + message(secondFailure)); }
                }
            }
            if (!active(token)) return;
            if (replyBlockedIfNeeded(query, token)) return;
            if (fallback) fallbackCount.incrementAndGet();
            long elapsed = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            // Exponential moving average (1/8 weight); cached/block decisions are excluded.
            latencyMs.updateAndGet(previous -> previous == 0 ? elapsed : (previous * 7 + elapsed) / 8);
            if (revision.equals(rules.currentRevision())) cache.put(query, answer, token + ":" + revision);
            prefs.edit().remove("dnsLastError").apply();
            record(query.domain, fallback ? "fallback" : "allowed");
            writePacket(DnsPacket.responsePacket(query, answer), token);
        } catch (Exception e) {
            if (active(token)) {
                prefs.edit().putString("dnsLastError", message(e)).apply();
                replyFailure(query, token, "failed");
            }
        }
    }

    private static byte[] requireUsable(byte[] answer) throws IOException {
        int rcode = answer[3] & 15;
        if (rcode != 0 && rcode != 3) throw new IOException("DNS 上游返回错误 " + rcode);
        return answer;
    }

    private boolean replyBlockedIfNeeded(DnsPacket.Query query, int token) {
        if (query.queryClass != 1 || !rules.isBlocked(query.domain)) return false;
        if (active(token)) {
            blocked.incrementAndGet(); record(query.domain, "blocked");
            writePacket(DnsPacket.responsePacket(query, DnsPacket.error(query, 3)), token);
        }
        return true;
    }

    public static InetAddress numericAddress(String value) throws IOException {
        if (value == null || value.length() == 0 || value.length() > 64 || !value.matches("[0-9a-fA-F:.]+"))
            throw new IOException("上游 DNS 必须是数字 IP 地址");
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IOException("IPv4 地址格式错误");
            for (String part : parts) {
                try { if (part.length() == 0 || part.length() > 3 || Integer.parseInt(part) > 255) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new IOException("IPv4 地址格式错误"); }
            }
        }
        return InetAddress.getByName(value);
    }

    private byte[] exchange(DnsPacket.Query query, InetAddress upstream, int token) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            sockets.add(socket);
            try {
                if (!active(token) || !protect(socket)) throw new IOException("无法保护 DNS 上游连接");
                socket.connect(upstream, 53);
                socket.send(new DatagramPacket(query.dns, query.dns.length));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1800);
                byte[] received = new byte[DnsPacket.MAX_DNS + 1];
                while (active(token)) {
                    int left = (int)TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (left <= 0) throw new IOException("DNS 上游超时");
                    socket.setSoTimeout(Math.max(1, left));
                    DatagramPacket packet = new DatagramPacket(received, received.length);
                    socket.receive(packet);
                    byte[] answer = Arrays.copyOf(received, packet.getLength());
                    if (!DnsPacket.validResponse(query, answer)) continue;
                    if (DnsPacket.truncated(answer)) return exchangeTcp(query, upstream, token);
                    if (DnsPacket.ttlOffsets(answer) == null) continue;
                    return answer;
                }
                throw new IOException("DNS 防护已停止");
            } finally { sockets.remove(socket); }
        }
    }

    private byte[] exchangeTcp(DnsPacket.Query query, InetAddress upstream, int token) throws IOException {
        try (Socket socket = new Socket()) {
            sockets.add(socket);
            try {
                if (!active(token) || !protect(socket)) throw new IOException("无法保护 DNS TCP 连接");
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
                socket.connect(new InetSocketAddress(upstream, 53), 1500);
                socket.getOutputStream().write(new byte[]{(byte)(query.dns.length >>> 8), (byte)query.dns.length});
                socket.getOutputStream().write(query.dns);
                byte[] length = new byte[2]; readFully(socket, length, deadline);
                int size = ((length[0] & 255) << 8) | (length[1] & 255);
                if (size < 12 || size > DnsPacket.MAX_DNS) throw new IOException("DNS TCP 响应长度不支持");
                byte[] answer = new byte[size]; readFully(socket, answer, deadline);
                if (!DnsPacket.validResponse(query, answer) || DnsPacket.truncated(answer) || DnsPacket.ttlOffsets(answer) == null) throw new IOException("DNS TCP 响应无效");
                return answer;
            } finally { sockets.remove(socket); }
        }
    }

    private static void readFully(Socket socket, byte[] buffer, long deadline) throws IOException {
        int position = 0; InputStream input = socket.getInputStream();
        while (position < buffer.length) {
            int remaining = (int)TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new IOException("DNS TCP 上游超时");
            socket.setSoTimeout(Math.max(1, remaining));
            int count = input.read(buffer, position, buffer.length - position);
            if (count < 0) throw new IOException("DNS TCP 响应被截断");
            position += count;
        }
    }

    private void replyFailure(DnsPacket.Query query, int token, String reason) {
        errors.incrementAndGet(); record(query.domain, reason);
        writePacket(DnsPacket.responsePacket(query, DnsPacket.error(query, 2)), token);
    }

    private void writePacket(byte[] packet, int token) {
        synchronized (outputLock) {
            if (!active(token) || output == null) return;
            try { output.write(packet); }
            catch (IOException e) { if (active(token)) submit(() -> { if (active(token)) stopAndRestore("DNS 回复失败：" + message(e)); }); }
        }
    }

    private void record(String domain, String outcome) {
        if (!prefs.getBoolean("requestLogs", false)) return;
        synchronized (logsLock) {
            try {
                if (!prefs.getBoolean("requestLogs", false)) return;
                JSONArray previous = new JSONArray(prefs.getString("dnsLogs", "[]")), next = new JSONArray();
                for (int i = Math.max(0, previous.length() - 99); i < previous.length(); i++) next.put(previous.get(i));
                next.put(new JSONObject().put("time", System.currentTimeMillis()).put("domain", domain).put("result", outcome));
                prefs.edit().putString("dnsLogs", next.toString()).apply();
            } catch (Exception ignored) { }
        }
    }

    private synchronized void flushStatistics() {
        if (prefs != null) prefs.edit().putLong("queries", queries.get()).putLong("blocked", blocked.get()).putLong("errors", errors.get())
                .putLong("cacheHits", cacheHits.get()).putLong("dnsLatencyMs", latencyMs.get()).putLong("dnsFallbackCount", fallbackCount.get()).commit();
    }

    private void closeNetwork() {
        networkRunning = false; if (latestInstance == this) running = false; generation.incrementAndGet();
        synchronized (outputLock) {
            // ParcelFileDescriptor owns the shared descriptor. Do not close wrappers twice.
            output = null;
            ParcelFileDescriptor old = tunnel; tunnel = null;
            if (old != null) try { old.close(); } catch (IOException ignored) { }
        }
        for (Closeable socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear();
        DnsUpstream https = encryptedUpstream; encryptedUpstream = null;
        if (https != null) https.close();
        cache.clear();
        ThreadPoolExecutor pool = workers; workers = null;
        if (pool != null) pool.shutdownNow();
        flushStatistics();
    }

    private void stopAndRestore(String error) {
        synchronized (MODE_LOCK) {
            int stoppingCommand = commandSequence.get();
            int stoppingStartId = latestStartId;
            requestedStop = true;
            closeNetwork();
            if (latestInstance == this) {
                prefs.edit().putBoolean("vpnWanted", false).commit();
                String restorationError = restoreHosts();
                if (error != null) setError(error + (restorationError == null ? "" : "；" + restorationError));
                else if (restorationError != null) setError(restorationError);
                else prefs.edit().remove("vpnError").apply();
            }
            // A START delivered during a slow hosts restoration must remain queued and alive.
            // startId provides the system-side check even if delivery races this final comparison.
            if (commandSequence.get() == stoppingCommand && stopSelfResult(stoppingStartId))
                stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private String restoreHosts() {
        if (!prefs.getBoolean("vpnRestoreHosts", false)) return null;
        try {
            JSONObject before = RootBridge.status(this);
            if (!before.optBoolean("ok", false)) return "原 hosts 防护待恢复：" + before.optString("message", "无法读取模块状态");
            if (before.optBoolean("ok", false) && !before.optBoolean("installed", true)) {
                prefs.edit().putBoolean("vpnRestoreHosts", false).commit(); return null;
            }
            if (before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false))
                return "模块已在 Root 管理器中停用或待卸载，原 hosts 未恢复";
            if (before.optBoolean("enabled", false) && before.optBoolean("mounted", false)) {
                prefs.edit().putBoolean("vpnRestoreHosts", false).commit(); return null;
            }
            RootBridge.Result result = RootBridge.run(this, "enable");
            JSONObject status = RootBridge.status(this);
            if (result.code != 0 || !status.optBoolean("ok", false) || !status.optBoolean("enabled", false) || !status.optBoolean("mounted", false))
                return "原 hosts 防护恢复失败，请在首页检查并重新开启";
            prefs.edit().putBoolean("vpnRestoreHosts", false).commit();
            return null;
        } catch (Exception e) { return "原 hosts 防护待恢复：" + message(e); }
    }

    private void setError(String message) { prefs.edit().putString("vpnError", message).commit(); }
    private static String message(Exception e) { String text = e.getMessage(); return text == null ? e.getClass().getSimpleName() : text; }

    private void showForeground(String text) {
        if (destroyed) return;
        Intent stop = new Intent(this, DnsVpnService.class).setAction(ACTION_STOP);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("辟尘 · DNS 去广告").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止", PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, builder.build());
    }

    @Override public void onRevoke() {
        int command = commandSequence.incrementAndGet();
        requestedStop = true; prefs.edit().putBoolean("vpnWanted", false).commit(); closeNetwork();
        submit(() -> { if (commandSequence.get() == command) stopAndRestore("VPN 授权已撤销或被其他 VPN 替换"); });
    }

    @Override public void onDestroy() {
        commandSequence.incrementAndGet();
        destroyed = true; requestedStop = true; closeNetwork(); statistics.shutdownNow();
        // Also covers normal system teardown. Abrupt process death is recovered via the persisted intent.
        submit(() -> {
            synchronized (MODE_LOCK) {
                // A newer service instance owns the persisted restore intent now.
                if (latestInstance == this) { String error = restoreHosts(); if (error != null) setError(error); }
            }
        });
        lifecycle.shutdown(); super.onDestroy();
    }
}
