package com.offworkpet.receiver;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class AlertProtocolTest {
    static JSONObject parse(String value) {
        try { return new JSONObject(value); }
        catch (org.json.JSONException e) { throw new AssertionError(e); }
    }
    static class Recorder implements AlertProtocol.Sink {
        final List<JSONObject> sends = new ArrayList<>();
        final List<AlertProtocol.Alert> alerts = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        public void send(String wire) { sends.add(parse(wire)); }
        public void status(String value) { }
        public void alert(AlertProtocol.Alert alert) { alerts.add(alert); }
        public void fatal(String error) { errors.add(error); }
    }
    private final Recorder sink = new Recorder();
    private final AlertProtocol protocol = new AlertProtocol("ABCD2345", "手机", sink);
    private static JSONObject group(String code) {
        return AlertProtocol.json("id", "group1", "code", code, "name", "本地测试", "members", new org.json.JSONArray(), "owner_self", false);
    }
    private void ready() {
        protocol.connected(0);
        protocol.receive(AlertProtocol.json("type", "state", "server_time", 1000).toString(), 0);
        protocol.receive(AlertProtocol.json("type", "rally_state", "rally", group("ABCD2345")).toString(), 0);
        protocol.receive(AlertProtocol.json("type", "result", "ok", true, "request_id", sink.sends.get(sink.sends.size() - 1).optString("request_id"), "data", AlertProtocol.json()).toString(), 0);
    }
    static JSONObject signal(String id, String level) {
        return AlertProtocol.json("type", "rally_alert", "rally_id", "group1", "alert_id", id,
                "level", level, "server_time", 1000, "expires_at", 1006, "from", "电脑");
    }
    @Test public void rejectsInvalidInviteInsteadOfPairingOrCreating() throws Exception {
        assertFalse(AlertProtocol.validCode("123456"));
        assertFalse(AlertProtocol.validCode("ABCDEFG0"));
        assertEquals("ABCD2345", AlertProtocol.normalizeCode(" abcd2345 "));
        try { new AlertProtocol("123456", "手机", sink); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void joinsOnlyAfterProfileAndInitialMembershipArrive() throws Exception {
        protocol.connected(0);
        assertEquals("profile", sink.sends.get(0).optString("type"));
        protocol.receive(AlertProtocol.json("type", "rally_state", "rally", JSONObject.NULL).toString(), 0);
        assertEquals(1, sink.sends.size());
        protocol.receive(AlertProtocol.json("type", "result", "ok", true, "request_id", sink.sends.get(0).optString("request_id")).toString(), 0);
        assertEquals("rally_join", sink.sends.get(1).optString("type"));
        assertEquals("ABCD2345", sink.sends.get(1).optString("code"));
    }
    @Test public void doesNotLeaveAnExistingMatchingGroupOnRestart() throws Exception {
        ready(); assertEquals(1, sink.sends.size());
    }
    @Test public void acceptsDefaultAndCustomLabelsWithoutSendingAnything() throws Exception {
        ready();
        protocol.receive(signal("one", "safe").toString(), 1);
        protocol.receive(signal("two", "danger").put("label", "准备出发").put("color", "#AABBCC").toString(), 2);
        assertEquals(2, sink.alerts.size());
        assertEquals("安全", sink.alerts.get(0).label);
        assertEquals("准备出发", sink.alerts.get(1).label);
        assertEquals(1, sink.sends.size()); // Only the receive profile, never alert/chat/ack.
    }
    @Test public void dropsExpiredFutureAndForeignGroupAlerts() throws Exception {
        ready();
        protocol.receive(signal("expired", "danger").toString(), 6000);
        protocol.receive(signal("future", "danger").put("expires_at", 2000).toString(), 0);
        protocol.receive(signal("foreign", "danger").put("rally_id", "other").toString(), 0);
        assertTrue(sink.alerts.isEmpty());
    }
    @Test public void repeatedSignalsAreDeliveredEvenAcrossReconnects() throws Exception {
        ready(); protocol.receive(signal("same", "safe").toString(), 1);
        ready(); protocol.receive(signal("same", "safe").toString(), 2);
        assertEquals(2, sink.alerts.size());
    }
    @Test public void twentyRapidIdenticalSignalsAreAllDelivered() throws Exception {
        ready();
        for (int i = 0; i < 20; i++) protocol.receive(signal("same", "warning").toString(), i * 100);
        assertEquals(20, sink.alerts.size());
    }
    @Test public void oldDangerCannotReplaceNewSafetySignal() throws Exception {
        ready();
        protocol.receive(signal("safe", "safe").put("server_time", 1001).toString(), 1100);
        protocol.receive(signal("old-danger", "danger").toString(), 1200);
        assertEquals(1, sink.alerts.size()); assertEquals("safe", sink.alerts.get(0).level);
    }
    @Test public void rejectsMalformedPayloadAndUnsyncedClock() throws Exception {
        protocol.connected(0);
        protocol.receive(signal("early", "danger").toString(), 0);
        assertTrue(sink.alerts.isEmpty());
        ready();
        for (String wire : new String[]{"not-json", "[]", signal("badcolor", "safe").put("color", "red").toString(),
                signal("badname", "danger").put("label", "x\ny").put("color", "#ABCDEF").toString(),
                signal("badlevel", "unknown").toString(), signal("badttl", "safe").put("expires_at", "1006").toString()}) protocol.receive(wire, 0);
        assertTrue(sink.alerts.isEmpty());
    }
    @Test public void requestFailureAndTimeoutStopReception() throws Exception {
        protocol.connected(0); protocol.tick(10_000);
        assertEquals(1, sink.errors.size());
    }
    @Test public void switchingGroupLeavesOldMembershipBeforeJoining() throws Exception {
        protocol.connected(0);
        protocol.receive(AlertProtocol.json("type", "rally_state", "rally", group("BCDE2345")).toString(), 0);
        protocol.receive(AlertProtocol.json("type", "result", "ok", true, "request_id", sink.sends.get(0).optString("request_id")).toString(), 0);
        assertEquals("rally_leave", sink.sends.get(1).optString("type"));
        protocol.receive(AlertProtocol.json("type", "result", "ok", true, "request_id", sink.sends.get(1).optString("request_id"),
                "data", AlertProtocol.json("rally", JSONObject.NULL)).toString(), 0);
        assertEquals("rally_join", sink.sends.get(2).optString("type"));
    }
}
