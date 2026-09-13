package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Temporary dedicated Root TPROXY test console while the mode is integrated into the main proxy page. */
public final class RootTproxyActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ProxyStore store;
    private RootTproxyManager root;
    private TextView status, detail, message;
    private Button start, stop, refresh, importYaml;
    private boolean busy, destroyed;

    @Override public void onCreate(Bundle state) {
        setTheme(ProxyUi.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        store = new ProxyStore(this);
        root = new RootTproxyManager(this);
        build();
        refresh();
    }

    private void build() {
        ProxyUi u = new ProxyUi(this);
        LinearLayout shell = u.col();
        u.window(shell);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = u.col();
        body.setPadding(u.dp(20), u.dp(18), u.dp(20), u.dp(30));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = u.text("Root TPROXY 测试", 26, u.text, true);
        body.addView(title);
        u.gap(body, 6);
        body.addView(u.text("Mihomo 以 Root 进程运行 · 不占 Android VPN 槽位", 13, u.muted, false));
        u.gap(body, 18);

        LinearLayout hero = u.card(body);
        status = u.text("正在读取状态", 22, u.text, true);
        hero.addView(status);
        u.gap(hero, 8);
        detail = u.text("", 13, u.muted, false);
        detail.setMaxLines(8);
        hero.addView(detail);

        LinearLayout config = u.card(body);
        config.addView(u.text("配置", 18, u.text, true));
        u.gap(config, 8);
        config.addView(u.text("原 YAML 不修改。会保留 listeners / TPROXY / eBPF / routing-mark 等 Root 配置。", 13, u.muted, false));
        u.gap(config, 14);
        importYaml = button("导入 YAML", false, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 701));
        config.addView(importYaml, new LinearLayout.LayoutParams(-1, u.dp(52)));

        LinearLayout actions = u.card(body);
        actions.addView(u.text("运行", 18, u.text, true));
        u.gap(actions, 12);
        start = button("启动 Root TPROXY", true, this::startTproxy);
        actions.addView(start, new LinearLayout.LayoutParams(-1, u.dp(54)));
        u.gap(actions, 10);
        stop = button("停止并清理规则", false, this::stopTproxy);
        actions.addView(stop, new LinearLayout.LayoutParams(-1, u.dp(52)));
        u.gap(actions, 10);
        refresh = button("刷新状态", false, this::refresh);
        actions.addView(refresh, new LinearLayout.LayoutParams(-1, u.dp(52)));

        LinearLayout warning = u.card(body);
        warning.addView(u.text("测试边界", 16, u.text, true));
        u.gap(warning, 8);
        warning.addView(u.text("启动前会检查 Root、iptables TPROXY 和 IPv6。任一步失败都会回滚辟尘自己的链与策略路由。局域网地址默认旁路，Root UID 0 不重复透明代理以避免 Mihomo 自身回环。", 12, u.muted, false));

        message = u.text("", 12, u.accent, false);
        message.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        message.setVisibility(View.GONE);
        body.addView(message, new LinearLayout.LayoutParams(-1, -2));
        setContentView(shell);
    }

    private Button button(String text, boolean primary, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> action.run());
        if (primary) b.setTextColor(Color.WHITE);
        return b;
    }

    private void startTproxy() {
        if (busy) return;
        task(() -> {
            if (!store.exists()) throw new IOException("先导入包含 TPROXY listener 的 Mihomo YAML");
            String yaml = store.yaml();
            int port = root.detectPort(yaml);
            JSONObject preflight = root.preflight(yaml);
            if (!preflight.optBoolean("ok")) throw new IOException(preflight.optString("message", "TPROXY 预检失败"));
            ui.post(() -> confirmStart(port, yaml));
            return null;
        });
    }

    private void confirmStart(int port, String yaml) {
        if (destroyed) return;
        new AlertDialog.Builder(this)
                .setTitle("启动 Root TPROXY？")
                .setMessage("检测到 TPROXY 监听端口 " + port + "。\n\n不会占用 Android VPN；会临时安装辟尘自己的策略路由和 mangle 链。停止时只清理辟尘自己的规则。")
                .setNegativeButton("取消", null)
                .setPositiveButton("启动", (d, w) -> task(() -> {
                    JSONObject result = root.start(yaml);
                    return result.optString("message", "Root TPROXY 已启动");
                }))
                .show();
    }

    private void stopTproxy() {
        task(() -> root.stop().optString("message", "Root TPROXY 已停止"));
    }

    private void refresh() {
        task(() -> {
            JSONObject state = root.status();
            ui.post(() -> render(state));
            return null;
        });
    }

    private void render(JSONObject state) {
        if (destroyed || status == null) return;
        boolean running = state.optBoolean("running", false);
        status.setText(running ? "TPROXY 运行中" : "TPROXY 未运行");
        StringBuilder d = new StringBuilder();
        if (store.exists()) {
            try { d.append("配置已就绪 · TPROXY 端口 ").append(root.detectPort(store.yaml())); }
            catch (Exception e) { d.append("配置存在，但未识别到 TPROXY 端口"); }
        } else d.append("尚未导入配置");
        if (running) d.append("\nPID ").append(state.optInt("pid"))
                .append(" · IPv4规则 ").append(state.optBoolean("ipv4Rules") ? "已加载" : "缺失")
                .append(" · IPv6规则 ").append(state.optBoolean("ipv6Rules") ? "已加载" : "未加载/未启用");
        detail.setText(d.toString());
        start.setEnabled(!running && !busy);
        stop.setEnabled(running && !busy);
    }

    private interface Work { String run() throws Exception; }

    private void task(Work work) {
        if (busy || destroyed) return;
        busy = true;
        setButtons(false);
        worker.execute(() -> {
            String result = null;
            Exception error = null;
            try { result = work.run(); }
            catch (Exception e) { error = e; }
            final String text = result;
            final Exception failure = error;
            ui.post(() -> {
                busy = false;
                if (destroyed) return;
                setButtons(true);
                if (failure != null) showMessage(safe(failure));
                else if (!TextUtils.isEmpty(text)) showMessage(text);
                if (failure == null && text != null) refresh();
            });
        });
    }

    private void setButtons(boolean enabled) {
        if (start != null) start.setEnabled(enabled);
        if (stop != null) stop.setEnabled(enabled);
        if (refresh != null) refresh.setEnabled(enabled);
        if (importYaml != null) importYaml.setEnabled(enabled);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private String safe(Exception e) {
        String text = e.getMessage();
        if (TextUtils.isEmpty(text)) return "TPROXY 操作失败，请刷新状态";
        if (text.length() > 500) return text.substring(0, 500) + "…";
        return text;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 701 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            task(() -> {
                String yaml = ProxyStore.read(getContentResolver().openInputStream(data.getData()));
                root.detectPort(yaml);
                String revision = store.revision();
                store.saveIfUnchanged(yaml, "", revision);
                return "TPROXY 配置已导入";
            });
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
