package com.offworkpet.receiver;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class RelayClientTest {
    @Test public void errorDetailsKeepErrnoButRedactLongCredentials() {
        String details = RelayClient.failureDetails(new java.net.SocketException("recvfrom failed: EPERM token=abcdefghijklmnopqrstuvwxyz1234567890"));
        assertTrue(details.contains("EPERM"));
        assertTrue(details.contains("SocketException"));
        assertFalse(details.contains("abcdefghijklmnopqrstuvwxyz1234567890"));
    }
    @Test public void reconnectGoesStraightToFreshWebSocketWithoutAnotherHealthRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch reopened = new CountDownLatch(2);
            AtomicInteger opens = new AtomicInteger();
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"features\":[\"rally/v1\"]}"));
            WebSocketListener responder = new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    opens.incrementAndGet();
                    ws.send("{\"type\":\"state\",\"server_time\":1000}");
                    ws.send(AlertProtocol.json("type", "rally_state", "rally", AlertProtocol.json("id", "group1", "code", "ABCD2345", "name", "test")).toString());
                }
                @Override public void onMessage(WebSocket ws, String wire) {
                    JSONObject msg = AlertProtocolTest.parse(wire);
                    if (!msg.optString("type").equals("profile")) return;
                    ws.send(AlertProtocol.json("type", "result", "ok", true, "request_id", msg.optString("request_id")).toString());
                    if (opens.get() == 1) ws.close(1001, "temporary network reset");
                }
            };
            server.enqueue(new MockResponse().withWebSocketUpgrade(responder));
            server.enqueue(new MockResponse().withWebSocketUpgrade(responder));
            server.start();
            try (RelayClient client = new RelayClient(server.url("/").toString().replaceAll("/$", ""), "ABCD2345", "test", new RelayClient.Identity() {
                public String load() { return "existing-token"; }
                public void save(String value) { fail("Identity must be reused"); }
            }, () -> System.nanoTime() / 1_000_000, new RelayClient.Listener() {
                public void status(String value) { if (value.startsWith("正在接收")) reopened.countDown(); }
                public void alert(AlertProtocol.Alert value) { }
                public void fatal(String value) { fail(value); }
            })) {
                client.start(); assertTrue(reopened.await(8, TimeUnit.SECONDS));
                assertEquals("/health", server.takeRequest(1, TimeUnit.SECONDS).getPath());
                assertEquals("/v2/connect", server.takeRequest(1, TimeUnit.SECONDS).getPath());
                assertEquals("/v2/connect", server.takeRequest(1, TimeUnit.SECONDS).getPath());
                assertEquals(3, server.getRequestCount());
            }
        }
    }
    @Test public void registersEncryptableIdentityAuthenticatesAndReceivesWithoutTransmittingAlerts() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicReference<String> token = new AtomicReference<>("");
            AtomicInteger saves = new AtomicInteger(), outgoing = new AtomicInteger();
            CountDownLatch alert = new CountDownLatch(1);
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"features\":[\"rally/v1\"]}"));
            server.enqueue(new MockResponse().setBody("{\"token\":\"test-secret\"}"));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    ws.send("{\"type\":\"state\",\"server_time\":1000}");
                    ws.send(AlertProtocol.json("type", "rally_state", "rally", AlertProtocol.json("id", "group1", "code", "ABCD2345", "name", "测试")).toString());
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    JSONObject msg = AlertProtocolTest.parse(text); outgoing.incrementAndGet();
                    assertEquals("profile", msg.optString("type"));
                    ws.send(AlertProtocol.json("type", "result", "ok", true, "request_id", msg.optString("request_id")).toString());
                    ws.send(AlertProtocolTest.signal("hello", "danger").toString());
                }
            }));
            server.start();
            RelayClient.Identity identity = new RelayClient.Identity() {
                public String load() { return token.get(); }
                public void save(String value) { token.set(value); saves.incrementAndGet(); }
            };
            String endpoint = server.url("/").toString().replaceAll("/$", "");
            try (RelayClient client = new RelayClient(endpoint, "ABCD2345", "手机", identity,
                    () -> System.nanoTime() / 1_000_000, new RelayClient.Listener() {
                public void status(String text) { }
                public void alert(AlertProtocol.Alert value) { assertEquals("danger", value.level); alert.countDown(); }
                public void fatal(String text) { fail(text); }
            })) {
                client.start(); assertTrue("No alert arrived", alert.await(10, TimeUnit.SECONDS));
                RecordedRequest health = server.takeRequest(2, TimeUnit.SECONDS);
                RecordedRequest registration = server.takeRequest(2, TimeUnit.SECONDS);
                RecordedRequest ws = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("/health", health.getPath()); assertEquals("/v2/devices", registration.getPath());
                assertEquals("Bearer test-secret", ws.getHeader("Authorization"));
                assertEquals(1, saves.get()); assertEquals(1, outgoing.get());
            }
        }
    }
    @Test public void storedIdentityDoesNotRegisterAgainAndAuthFailureDoesNotLoop() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch failure = new CountDownLatch(1);
            server.enqueue(new MockResponse().setBody("{\"ok\":true,\"features\":[\"rally/v1\"]}"));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) { ws.close(4401, "invalid"); }
            }));
            server.start();
            try (RelayClient client = new RelayClient(server.url("/").toString().replaceAll("/$", ""), "ABCD2345", "手机", new RelayClient.Identity() {
                public String load() { return "saved-token"; }
                public void save(String value) { fail("Must not register another device"); }
            }, () -> System.nanoTime() / 1_000_000, new RelayClient.Listener() {
                public void status(String text) { }
                public void alert(AlertProtocol.Alert alert) { fail("Unexpected alert"); }
                public void fatal(String text) { failure.countDown(); }
            })) {
                client.start(); assertTrue(failure.await(10, TimeUnit.SECONDS));
                assertEquals(2, server.getRequestCount());
            }
        }
    }
}
