package io.github.xgl34222220.hetu;

import java.io.IOException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** The shell protocol ends in one JSON response; diagnostic stderr may precede it. */
final class RootCommandReply {
    private RootCommandReply() {}

    static JSONObject read(int exitCode, String output) throws IOException {
        String raw = output == null ? "" : output.trim();
        JSONObject reply = null;
        try { reply = object(raw); }
        catch (Exception ignored) {
            int line = raw.lastIndexOf('\n');
            if (line >= 0) try { reply = object(raw.substring(line + 1).trim()); }
            catch (Exception invalid) { /* Only the terminal complete response is authoritative. */ }
        }
        if (exitCode != 0) {
            String message = reply == null ? summary(raw) : reply.optString("message", "");
            throw new IOException("Root 命令失败（退出码 " + exitCode + "）" + (message.isEmpty() ? "" : "：" + message));
        }
        if (reply == null) throw new IOException("Root 控制器没有返回完整状态" + (raw.isEmpty() ? "" : "：" + summary(raw)));
        if (!reply.optBoolean("ok", false)) throw new IOException(reply.optString("message", "Root 操作未完成"));
        return reply;
    }

    private static JSONObject object(String text) throws Exception {
        JSONTokener input = new JSONTokener(text);
        Object value = input.nextValue();
        if (!(value instanceof JSONObject) || input.nextClean() != 0 || !(((JSONObject) value).opt("ok") instanceof Boolean))
            throw new IOException("Invalid control response");
        return (JSONObject) value;
    }

    private static String summary(String text) {
        String first = text.replace('\r', ' ').replace('\n', ' ').trim();
        return first.length() > 180 ? first.substring(0, 180) + "…" : first;
    }
}
