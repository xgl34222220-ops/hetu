package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Basic proxy configuration page. Root TPROXY is the first fully wired Root mode. */
public final class RootTproxyActivity extends Activity {
    private static final String PREF_CORE = "proxyBaseCore";
    private static final String PREF_MODE = "proxyBaseMode";
    private static final String PREF_IPV6 = "proxyBaseIpv6";
    private static final String PREF_OVERWRITE = "proxyBaseAutoOverwrite";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ProxyStore store;
    private RootTproxyManager root;
    private SharedPreferences prefs;
    private ProxyUi u;
    private TextView coreValue, modeValue, ipv6Value, overwriteValue, configValue, stateValue, message;
    private boolean busy, destroyed;

    @Override public void onCreate(Bundle state) {
        setTheme(ProxyUi.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        prefs = getSharedPreferences("bichen", MODE_PRIVATE);
        store = new ProxyStore(this);
        root = new RootTproxyManager(this);
        build();
        refresh();
    }

    private void build() {
        u = new ProxyUi(this);
        LinearLayout shell = u.col();
        u.window(shell);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = u.col();
        body.setPadding(u.dp(28), u.dp(20), u.dp(28), u.dp(34));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout top = u.row();
        TextView back = u.text("‹", 42, u.text, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(u.dp(46), u.dp(54)));
        body.addView(top);

        TextView title = u.text("基础代理配置", 31, u.text, true);
        title.setPadding(0, u.dp(8), 0, u.dp(20));
        body.addView(title);

        LinearLayout settings = u.card(body);
        coreValue = settingRow(settings, "核心选择", currentCoreLabel(), this::chooseCore);
        separator(settings);
        modeValue = settingRow(settings, "运行模式", currentModeLabel(), this::chooseMode);
        separator(settings);
        ipv6Value = settingRow(settings, "IPv6", currentIpv6Label(), this::chooseIpv6);
        separator(settings);
        overwriteValue = settingRow(settings, "自动覆写", prefs.getBoolean(PREF_OVERWRITE, true) ? "开启" : "关闭", this::chooseOverwrite);

        LinearLayout launch = u.card(body);
        settingRow(launch, "查看启动配置", "", this::showLaunchConfig);

        LinearLayout configs = u.card(body);
        TextView configTitle = u.text("配置选择", 18, u.text, true);
        configs.addView(configTitle);
        TextView plus = u.text("＋", 30, u.text, false);
        plus.setGravity(Gravity.RIGHT);
        plus.setOnClickListener(v -> importYaml());
        configs.addView(plus, new LinearLayout.LayoutParams(-1, u.dp(44)));
        configValue = u.text(store.exists() ? "config.yaml    ✓" : "尚未选择配置", 17, u.text, false);
        configValue.setPadding(0, u.dp(12), 0, u.dp(14));
        configValue.setOnClickListener(v -> importYaml());
        configs.addView(configValue);

        LinearLayout runtime = u.card(body);
        runtime.addView(u.text("运行状态", 17, u.text, true));
        u.gap(runtime, 8);
        stateValue = u.text("正在读取", 14, u.muted, false);
        runtime.addView(stateValue);
        u.gap(runtime, 14);
        TextView start = u.button("启动", true, this::startSelectedMode);
        runtime.addView(start, new LinearLayout.LayoutParams(-1, u.dp(52)));
        u.gap(runtime, 10);
        TextView stop = u.button("停止并清理辟尘规则", false, this::stopSelectedMode);
        runtime.addView(stop, new LinearLayout.LayoutParams(-1, u.dp(50)));

        message = u.text("", 12, u.accent, false);
        message.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        message.setVisibility(View.GONE);
        body.addView(message);
        setContentView(shell);
    }

    private TextView settingRow(LinearLayout parent, String label, String value, Runnable action) {
        LinearLayout row = u.row();
        row.setMinimumHeight(u.dp(72));
        row.setPadding(0, u.dp(10), 0, u.dp(10));
        TextView left = u.text(label, 18, u.text, true);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView right = u.text(value, 16, u.text, false);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        right.setCompoundDrawablePadding(u.dp(8));
        row.addView(right, new LinearLayout.LayoutParams(-2, -1));
        TextView chevron = u.text("⌄", 22, u.text, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(u.dp(34), -1));
        row.setOnClickListener(v -> action.run());
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return right;
    }

    private void separator(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(u.dark ? 0x223d5548 : 0x12000000);
        parent.addView(line, new LinearLayout.LayoutParams(-1, 1));
    }

    private String core() { return prefs.getString(PREF_CORE, "mihomo"); }
    private String mode() { return prefs.getString(PREF_MODE, "tproxy"); }
    private String ipv6() { return prefs.getString(PREF_IPV6, "enable"); }

    private String currentCoreLabel() {
        switch (core()) {
            case "meta": return "Meta";
            case "singbox": return "Sing-Box";
            default: return "Mihomo";
        }
    }

    private String currentModeLabel() {
        switch (mode()) {
            case "tun": return "TUN";
            case "ebpf": return "eBPF";
            case "redirect": return "Redirect";
            case "mixed": return "Mixed";
            case "enhance": return "Enhance";
            default: return "TPROXY";
        }
    }

    private String currentIpv6Label() {
        switch (ipv6()) {
            case "bypass": return "IPv6 不进核心";
            case "disable": return "禁用系统 IPv6";
            default: return "启用 IPv6";
        }
    }

    private void chooseCore() {
        final String[] labels = {"Meta", "Mihomo", "Sing-Box"};
        final String[] values = {"meta", "mihomo", "singbox"};
        int checked = "meta".equals(core()) ? 0 : "singbox".equals(core()) ? 2 : 1;
        new AlertDialog.Builder(this).setTitle("核心选择")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (!"mihomo".equals(values[which])) {
                        showMessage(labels[which] + " 核心入口已经预留，但当前测试包尚未内置可执行核心；不会假装启动成功。");
                        d.dismiss();
                        return;
                    }
                    prefs.edit().putString(PREF_CORE, values[which]).apply();
                    coreValue.setText(labels[which]);
                    d.dismiss();
                }).show();
    }

    private void chooseMode() {
        final String[] labels = {"TUN", "TPROXY", "eBPF", "Redirect", "Mixed", "Enhance"};
        final String[] values = {"tun", "tproxy", "ebpf", "redirect", "mixed", "enhance"};
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i].equals(mode())) checked = i;
        new AlertDialog.Builder(this).setTitle("运行模式")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (!("tproxy".equals(values[which]) || "tun".equals(values[which]))) {
                        showMessage(labels[which] + " 模式已经预留在基础配置里，当前测试包尚未接通运行后端。");
                        d.dismiss();
                        return;
                    }
                    prefs.edit().putString(PREF_MODE, values[which]).apply();
                    modeValue.setText(labels[which]);
                    d.dismiss();
                }).show();
    }

    private void chooseIpv6() {
        final String[] labels = {"启用 IPv6", "IPv6 不进核心", "禁用系统 IPv6"};
        final String[] values = {"enable", "bypass", "disable"};
        int checked = "bypass".equals(ipv6()) ? 1 : "disable".equals(ipv6()) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("IPv6")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.edit().putString(PREF_IPV6, values[which]).apply();
                    ipv6Value.setText(labels[which]);
                    d.dismiss();
                }).show();
    }

    private void chooseOverwrite() {
        boolean now = prefs.getBoolean(PREF_OVERWRITE, true);
        prefs.edit().putBoolean(PREF_OVERWRITE, !now).apply();
        overwriteValue.setText(!now ? "开启" : "关闭");
    }

    private void showLaunchConfig() {
        String text = "核心：" + currentCoreLabel() + "\n运行模式：" + currentModeLabel()
                + "\nIPv6：" + currentIpv6Label() + "\n自动覆写："
                + (prefs.getBoolean(PREF_OVERWRITE, true) ? "开启" : "关闭");
        if (store.exists()) {
            try { text += "\nTPROXY 端口：" + root.detectPort(store.yaml()); }
            catch (Exception ignored) { text += "\nTPROXY 端口：配置中未识别"; }
        }
        new AlertDialog.Builder(this).setTitle("启动配置").setMessage(text).setPositiveButton("确定", null).show();
    }

    private void importYaml() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE), 701);
    }

    private void startSelectedMode() {
        if (busy) return;
        if (!"mihomo".equals(core())) {
            showMessage(currentCoreLabel() + " 当前测试包还没有内置运行核心，请先选 Mihomo。");
            return;
        }
        if ("tun".equals(mode())) {
            startActivity(new Intent(this, ProxyActivity.class));
            return;
        }
        if (!"tproxy".equals(mode())) {
            showMessage(currentModeLabel() + " 当前测试包还没有接通运行后端。");
            return;
        }
        task(() -> {
            if (!store.exists()) throw new IOException("先导入包含 TPROXY listener 的 Mihomo YAML");
            String yaml = store.yaml();
            int port = root.detectPort(yaml);
            JSONObject preflight = root.preflight(yaml, ipv6());
            if (!preflight.optBoolean("ok")) throw new IOException(preflight.optString("message", "TPROXY 预检失败"));
            JSONObject result = root.start(yaml, ipv6());
            return result.optString("message", "Root TPROXY 已启动") + " · 端口 " + port;
        });
    }

    private void stopSelectedMode() {
        if ("tun".equals(mode())) {
            stopService(new Intent(this, MihomoVpnService.class).setAction("STOP"));
            showMessage("TUN 停止请求已发送");
            return;
        }
        task(() -> root.stop().optString("message", "Root TPROXY 已停止"));
    }

    private void refresh() {
        if (destroyed) return;
        if (coreValue != null) coreValue.setText(currentCoreLabel());
        if (modeValue != null) modeValue.setText(currentModeLabel());
        if (ipv6Value != null) ipv6Value.setText(currentIpv6Label());
        if (overwriteValue != null) overwriteValue.setText(prefs.getBoolean(PREF_OVERWRITE, true) ? "开启" : "关闭");
        if (configValue != null) configValue.setText(store.exists() ? "config.yaml    ✓" : "尚未选择配置");
        if ("tproxy".equals(mode())) {
            task(() -> {
                JSONObject state = root.status();
                ui.post(() -> {
                    if (stateValue != null) stateValue.setText(state.optBoolean("running", false)
                            ? "Mihomo · TPROXY 运行中 · PID " + state.optInt("pid")
                            : "Mihomo · TPROXY 未运行");
                });
                return null;
            });
        } else if (stateValue != null) stateValue.setText(currentCoreLabel() + " · " + currentModeLabel());
    }

    private interface Work { String run() throws Exception; }
    private void task(Work work) {
        if (busy || destroyed) return;
        busy = true;
        worker.execute(() -> {
            String result = null; Exception error = null;
            try { result = work.run(); } catch (Exception e) { error = e; }
            final String text = result; final Exception failure = error;
            ui.post(() -> {
                busy = false;
                if (destroyed) return;
                if (failure != null) showMessage(safe(failure));
                else if (!TextUtils.isEmpty(text)) showMessage(text);
            });
        });
    }

    private void showMessage(String text) {
        if (message == null) return;
        message.setText(text);
        message.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }
    private String safe(Exception e) {
        String text = e.getMessage();
        if (TextUtils.isEmpty(text)) return "代理操作失败，请刷新状态";
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 701 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            task(() -> {
                String yaml = ProxyStore.read(getContentResolver().openInputStream(data.getData()));
                String revision = store.revision();
                store.saveIfUnchanged(yaml, "", revision);
                ui.post(() -> { if (configValue != null) configValue.setText("config.yaml    ✓"); });
                return "配置已导入";
            });
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
