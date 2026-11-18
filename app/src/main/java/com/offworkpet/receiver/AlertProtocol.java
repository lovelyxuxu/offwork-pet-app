package com.offworkpet.receiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Pure protocol engine. It cannot send alerts, chat, create groups or pair pets. */
public final class AlertProtocol {
    public interface Sink {
        void send(String wire);
        void status(String text);
        void alert(Alert alert);
        void fatal(String text);
        default void retry(String text) { fatal(text); }
    }
    public static final class Alert {
        public final String id, level, label, color, source;
        public final long remainingMillis;
        Alert(String id, String level, String label, String color, String source, long ttl) {
            this.id = id; this.level = level; this.label = label; this.color = color;
            this.source = source; remainingMillis = ttl;
        }
    }
    private final Sink sink;
    private final String desiredCode, nickname;
    private final Map<String, String> pending = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    // Deliver every valid alert, including repeated identifiers.

    private JSONObject rally;
    private boolean profiled, rallyKnown, joining, stopped;
    private double epoch, lastAlertTime;
    private long syncedAt;
    private boolean clockKnown;
    private String profileRequest;

    public AlertProtocol(String code, String name, Sink sink) {
        desiredCode = normalizeCode(code); nickname = name.trim(); this.sink = sink;
        if (!validCode(desiredCode)) throw new IllegalArgumentException("请输入 8 位集会码");
        if (!validName(nickname, 24)) throw new IllegalArgumentException("昵称最多 24 字，不能换行");
    }
    public static String normalizeCode(String text) { return text.trim().toUpperCase(Locale.ROOT); }
    public static boolean validCode(String text) { return text.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"); }
    static boolean validName(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit && text.codePoints().noneMatch(c -> c < 32 || c == 127);
    }
    static JSONObject json(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return value;
    }
    public void connected(long now) {
        pending.clear(); deadlines.clear(); profiled = false; rallyKnown = false; joining = false;
        stopped = false; clockKnown = false; rally = null;
        sink.status("正在同步集会…");
        profileRequest = request("profile", now, "paused", false, "visible", true,
                "capabilities", JSONObject.NULL, "visitSupport", false, "allowVisits", false, "client_type", "mobile");
    }
    private String request(String type, long now, Object... args) {
        String id = UUID.randomUUID().toString();
        JSONObject msg = json(args);
        try { msg.put("type", type); msg.put("request_id", id); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        pending.put(id, type); deadlines.put(id, now + 10_000); sink.send(msg.toString()); return id;
    }
    public void tick(long now) {
        for (Long deadline : deadlines.values()) {
            if (deadline <= now) { stopped = true; sink.retry("服务响应超时，正在重新连接"); return; }
        }
    }
    public void receive(String wire, long now) {
        if (stopped || wire.length() > 32768) return;
        try {
            JSONObject msg = new JSONObject(wire);
            String type = msg.optString("type");
            Object serverTime = msg.opt("server_time");
            if ((type.equals("state") || type.equals("heartbeat") || type.equals("rally_state")) && serverTime instanceof Number) {
                double time = ((Number) serverTime).doubleValue();
                if (Double.isFinite(time) && (!clockKnown || time > estimatedTime(now))) {
                    epoch = time; syncedAt = now; clockKnown = true;
                }
            }
            if (type.equals("rally_state") && msg.has("rally")) {
                syncRally(msg.optJSONObject("rally")); ensureMembership(now);
            } else if (type.equals("result")) {
                String id = msg.optString("request_id"), kind = pending.remove(id);
                deadlines.remove(id);
                if (kind == null) return;
                if (!msg.optBoolean("ok")) { fail(msg.optString("message", "加入失败，请检查集会码")); return; }
                JSONObject data = msg.optJSONObject("data");
                if (id.equals(profileRequest)) profiled = true;
                if (data != null && data.has("rally")) syncRally(data.optJSONObject("rally"));
                if (kind.equals("rally_leave")) joining = false;
                ensureMembership(now);
            } else if (type.equals("rally_alert")) receiveAlert(msg, now);
        } catch (JSONException | ClassCastException ignored) { /* Untrusted malformed frames do not notify. */ }
    }
    private double estimatedTime(long now) { return epoch + (now - syncedAt) / 1000.0; }
    private void syncRally(JSONObject value) {
        rallyKnown = true; rally = value;
        if (value == null) lastAlertTime = 0;
    }
    private void ensureMembership(long now) {
        if (!profiled || !rallyKnown || stopped) return;
        if (rally != null && desiredCode.equals(rally.optString("code"))) {
            joining = false;
            JSONArray members = rally.optJSONArray("members");
            sink.status("正在接收 · " + rally.optString("name", "集会") + " · " + (members == null ? 0 : members.length()) + " 人");
        } else if (!joining) {
            joining = true;
            if (rally != null) {
                if (rally.optBoolean("owner_self")) { fail("当前设备是集会创建者，不能自动切换集会"); return; }
                request("rally_leave", now);
            } else {
                sink.status("正在加入集会…");
                request("rally_join", now, "code", desiredCode, "name", nickname);
            }
        }
    }
    private void receiveAlert(JSONObject msg, long now) {
        if (!profiled || rally == null || !desiredCode.equals(rally.optString("code")) || !clockKnown) return;
        String group = msg.optString("rally_id"), id = msg.optString("alert_id"), level = msg.optString("level");
        if (!group.equals(rally.optString("id")) || !id.matches("[A-Za-z0-9_-]{1,64}")) return;
        String label, color;
        switch (level) {
            case "safe": label = "安全"; color = "#3FA47A"; break;
            case "warning": label = "警戒"; color = "#D39A32"; break;
            case "danger": label = "危险"; color = "#D85B58"; break;
            default: return;
        }
        if (!(msg.opt("expires_at") instanceof Number) || !(msg.opt("server_time") instanceof Number)) return;
        double ttl = msg.optDouble("expires_at") - estimatedTime(now), sentAt = msg.optDouble("server_time");
        if (!Double.isFinite(ttl) || ttl <= 0 || ttl > 7 || !Double.isFinite(sentAt) || sentAt < lastAlertTime) return;
        if (msg.has("label") || msg.has("color")) {
            if (!(msg.opt("label") instanceof String) || !(msg.opt("color") instanceof String)) return;
            label = msg.optString("label").trim(); color = msg.optString("color");
            if (label.isEmpty() || !validName(label, 24) || !color.matches("#[0-9a-fA-F]{6}")) return;
        }
        lastAlertTime = sentAt;
        String source = msg.optString("from");
        if (!validName(source, 24)) source = "搭子";
        sink.alert(new Alert(id, level, label, color, source, Math.max(1, (long) (ttl * 1000))));
    }
    private void fail(String text) { stopped = true; sink.fatal(text); }
}
