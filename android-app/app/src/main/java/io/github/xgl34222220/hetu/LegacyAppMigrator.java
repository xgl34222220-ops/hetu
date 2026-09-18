package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.util.*;

/**
 * One-shot best-effort migration from the old Bichen package sandbox.
 * Runs on a worker thread before the first Hetu runtime/config operation.
 */
final class LegacyAppMigrator {
    private static final Object LOCK = new Object();
    private static volatile boolean attempted;

    static void migrateIfNeeded(Context context) {
        if (attempted) return;
        synchronized (LOCK) {
            if (attempted) return;
            Context app = context.getApplicationContext();
            SharedPreferences prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE);
            if (prefs.getBoolean("hetuLegacyAppDataMigrated", false)) {
                attempted = true;
                return;
            }
            try {
                RootBridge.requireWorkerThread();
                int uid = Process.myUid();
                int userId = Math.max(0, uid / 100000);
                File stage = new File(app.getCacheDir(), "bichen-app-migration");
                deleteTree(stage);
                if (!stage.mkdirs() && !stage.isDirectory()) throw new IOException("无法创建旧数据迁移目录");

                String oldBase = "/data/user/" + userId + "/io.github.xgl34222220.bichen";
                String command = "set +e; SRC=" + RootBridge.quote(oldBase)
                        + "; DST=" + RootBridge.quote(stage.getAbsolutePath()) + "; "
                        + "if [ ! -d \"$SRC\" ]; then echo present=0; exit 0; fi; "
                        + "echo present=1; mkdir -p \"$DST\"; "
                        + "if [ -d \"$SRC/files/proxy/configs\" ]; then cp -R \"$SRC/files/proxy/configs\" \"$DST/configs\" 2>/dev/null || true; fi; "
                        + "if [ -f \"$SRC/shared_prefs/bichen.xml\" ]; then cp \"$SRC/shared_prefs/bichen.xml\" \"$DST/bichen.xml\" 2>/dev/null || true; fi; "
                        + "chown -R " + uid + ":" + uid + " \"$DST\" 2>/dev/null || true; "
                        + "chmod -R u+rwX,go-rwx \"$DST\" 2>/dev/null || true";
                RootBridge.Result result = RootBridge.rootShell(app, command, 8000L);
                if (!result.ok() || result.output == null || !result.output.contains("present=1")) {
                    prefs.edit().putBoolean("hetuLegacyAppDataChecked", true).apply();
                    attempted = true;
                    return;
                }

                Map<String, Object> oldPrefs = readPrefs(new File(stage, "bichen.xml"));
                ProxyConfigLibrary library = new ProxyConfigLibrary(app);
                int imported = 0;
                File configsRoot = new File(stage, "configs");
                for (ProxyRuntimeProfile.Core core : ProxyRuntimeProfile.Core.values()) {
                    File dir = new File(configsRoot, core.id);
                    File[] files = dir.listFiles(File::isFile);
                    if (files == null) continue;
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    String wanted = stringValue(oldPrefs.get("proxySelectedConfig." + core.id));
                    String selectedImported = null;
                    for (File file : files) {
                        String lower = file.getName().toLowerCase(Locale.ROOT);
                        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) continue;
                        try (FileInputStream in = new FileInputStream(file)) {
                            ProxyConfigLibrary.Entry entry = library.importConfig(core, file.getName(), in);
                            imported++;
                            if (file.getName().equals(wanted)) selectedImported = entry.name;
                            else if (selectedImported == null) selectedImported = entry.name;
                        } catch (Exception ignored) {}
                    }
                    if (selectedImported != null) {
                        try { library.select(core, selectedImported); } catch (Exception ignored) {}
                    }
                }

                SharedPreferences.Editor edit = prefs.edit();
                for (Map.Entry<String, Object> item : oldPrefs.entrySet()) {
                    String key = item.getKey();
                    if (!portableKey(key)) continue;
                    Object value = item.getValue();
                    if (value instanceof String) edit.putString(key, (String) value);
                    else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                    else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                    else if (value instanceof Long) edit.putLong(key, (Long) value);
                    else if (value instanceof Float) edit.putFloat(key, (Float) value);
                    else if (value instanceof Set) {
                        LinkedHashSet<String> values = new LinkedHashSet<>();
                        for (Object raw : (Set<?>) value) if (raw != null) values.add(String.valueOf(raw));
                        edit.putStringSet(key, values);
                    }
                }
                edit.remove("proxyRootWanted")
                        .remove("proxyRootRuntimeRunning")
                        .remove("proxyRootSessionOwner")
                        .remove("proxyControllerPort")
                        .remove("proxyAdblockSessionHits")
                        .remove("proxyAdblockLogOffset")
                        .putBoolean("hetuLegacyAppDataMigrated", true)
                        .putInt("hetuLegacyImportedConfigCount", imported)
                        .putLong("hetuLegacyAppDataMigratedAt", System.currentTimeMillis())
                        .remove("hetuLegacyMigrationError")
                        .apply();
                deleteTree(stage);
            } catch (Exception e) {
                prefs.edit().putString("hetuLegacyMigrationError",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).apply();
            } finally {
                attempted = true;
            }
        }
    }

    private static boolean portableKey(String key) {
        if (key == null || key.isEmpty() || key.startsWith("proxySelectedConfig.")) return false;
        String low = key.toLowerCase(Locale.ROOT);
        if (low.contains("runtime") || low.contains("session") || low.contains("error")
                || low.contains("uilast") || low.contains("logoffset") || low.contains("counter")) return false;
        return key.startsWith("proxy") || key.startsWith("networkMatch") || key.startsWith("latency")
                || key.startsWith("cname") || key.startsWith("dns") || key.startsWith("rule")
                || key.startsWith("dailyUpdate") || key.startsWith("requestLogs")
                || key.startsWith("theme") || key.equals("showPanelTab");
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static Map<String, Object> readPrefs(File file) throws Exception {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!file.isFile()) return out;
        try (FileInputStream in = new FileInputStream(file)) {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            int event;
            while ((event = x.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                String tag = x.getName();
                String name = x.getAttributeValue(null, "name");
                if (name == null) continue;
                String value = x.getAttributeValue(null, "value");
                if ("string".equals(tag)) out.put(name, x.nextText());
                else if ("boolean".equals(tag)) out.put(name, Boolean.parseBoolean(value));
                else if ("int".equals(tag)) try { out.put(name, Integer.parseInt(value)); } catch (Exception ignored) {}
                else if ("long".equals(tag)) try { out.put(name, Long.parseLong(value)); } catch (Exception ignored) {}
                else if ("float".equals(tag)) try { out.put(name, Float.parseFloat(value)); } catch (Exception ignored) {}
                else if ("set".equals(tag)) {
                    LinkedHashSet<String> set = new LinkedHashSet<>();
                    int depth = x.getDepth();
                    while (x.next() != XmlPullParser.END_DOCUMENT) {
                        if (x.getEventType() == XmlPullParser.START_TAG && "string".equals(x.getName())) {
                            set.add(x.nextText());
                        } else if (x.getEventType() == XmlPullParser.END_TAG
                                && x.getDepth() == depth && "set".equals(x.getName())) {
                            break;
                        }
                    }
                    out.put(name, set);
                }
            }
        }
        return out;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private LegacyAppMigrator() {}
}
