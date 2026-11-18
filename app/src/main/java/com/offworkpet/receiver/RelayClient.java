package com.offworkpet.receiver;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.function.LongSupplier;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** One authenticated connection; retries never replay alerts or registration requests. */
public final class RelayClient implements AutoCloseable {
    public interface Identity {
        String load() throws Exception;
        void save(String token) throws Exception;
    }
    public interface Listener {
        void status(String text);
        void alert(AlertProtocol.Alert alert);
        void fatal(String text);
        default void diagnostic(String event) { }
        default void traffic(long elapsed) { }
    }
    private final String endpoint;
    private final Identity identity;
    private final Listener listener;
    private final LongSupplier clock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final AlertProtocol protocol;
    private volatile boolean running;
    private volatile WebSocket socket;
    private volatile Call call;
    private int generation, retry = 1;
    private long lastReceived, lastMonitor;
    private String token;
    private boolean healthChecked;
    private ScheduledFuture<?> retryTask;

    public RelayClient(String endpoint, String code, String nickname, Identity identity,
                       LongSupplier clock, Listener listener) {
        if (!endpoint.matches("https?://[^/@?#\\s]+(?::[0-9]+)?"))
            throw new IllegalArgumentException("服务地址格式不正确");
        this.endpoint = endpoint; this.identity = identity; this.clock = clock; this.listener = listener;
        protocol = new AlertProtocol(code, nickname, new AlertProtocol.Sink() {
            public void send(String wire) { if (socket != null && running) socket.send(wire); }
            public void status(String text) { if (running) listener.status(text); }
            public void alert(AlertProtocol.Alert alert) { if (running) listener.alert(alert); }
            public void fatal(String text) { fail(text); }
            public void retry(String text) { listener.diagnostic("profile_timeout"); reconnect(generation); }
        });
    }
    public synchronized void start() {
        if (running || worker.isShutdown()) return;
        running = true; dispatch(this::connect);
    }
    private void dispatch(Runnable action) {
        if (!running) return;
        try { worker.execute(() -> { if (running) action.run(); }); }
        catch (RejectedExecutionException ignored) { }
    }
    private JSONObject fetch(Request request) throws IOException {
        Call current = http.newCall(request); call = current;
        try (Response response = current.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            try { return new JSONObject(response.body().string()); }
            catch (Exception e) { throw new IOException("服务响应格式不正确", e); }
        } finally { if (call == current) call = null; }
    }
    private void connect() {
        if (!running) return;
        int attempt = ++generation;
        listener.diagnostic("connect_attempt=" + attempt);
        listener.status("正在连接…");
        try {
            if (!healthChecked) {
                JSONObject health = fetch(new Request.Builder().url(endpoint + "/health").header("Connection", "close").build());
                JSONArray features = health.optJSONArray("features");
                boolean rally = false;
                if (features != null) for (int i = 0; i < features.length(); i++) rally |= "rally/v1".equals(features.optString(i));
                if (!health.optBoolean("ok") || !rally) { fail("服务不支持集会提醒"); return; }
                healthChecked = true;
            }
            if (token == null) {
                try { token = identity.load(); }
                catch (Exception e) { fail("无法读取设备身份，请清除应用数据后重试"); return; }
            }
            if (token.isEmpty()) {
                JSONObject device = fetch(new Request.Builder().url(endpoint + "/v2/devices")
                        .header("X-Offwork-Client-Type", "mobile")
                        .post(RequestBody.create("{}", MediaType.get("application/json"))).build());
                token = device.optString("token");
                if (token.isEmpty()) { fail("服务未返回设备身份"); return; }
                try { identity.save(token); }
                catch (Exception e) { fail("无法安全保存设备身份，请检查手机存储"); return; }
            }
            if (!running) return;
            http.connectionPool().evictAll();
            String ws = endpoint.replaceFirst("^http", "ws") + "/v2/connect";
            socket = http.newWebSocket(new Request.Builder().url(ws).header("Authorization", "Bearer " + token).build(),
                    new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    dispatch(() -> {
                        if (generation != attempt) return;
                        socket = webSocket; lastReceived = clock.getAsLong(); lastMonitor = lastReceived; retry = 1;
                        listener.diagnostic("socket_open");
                        protocol.connected(lastReceived); monitor(attempt);
                    });
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    dispatch(() -> {
                        if (generation != attempt) return;
                        lastReceived = clock.getAsLong(); listener.traffic(lastReceived); protocol.receive(text, lastReceived);
                    });
                }
                @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(code, null);
                    dispatch(() -> {
                        if (generation != attempt) return;
                        listener.diagnostic("socket_close_code=" + code + " last_data_age_ms=" + (clock.getAsLong() - lastReceived));
                        if (code == 4401) fail("设备身份已失效，请清除应用数据后重新加入");
                        else if (code == 4001) fail("接收连接已被另一连接替换，请重新开始接收");
                        else reconnect(attempt);
                    });
                }
                @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                    dispatch(() -> { if (generation == attempt) listener.diagnostic("socket_failure=" + failureDetails(error) + " last_data_age_ms=" + (clock.getAsLong() - lastReceived) + " last_tick_age_ms=" + (clock.getAsLong() - lastMonitor)); reconnect(attempt); });
                }
            });
        } catch (IOException | IllegalArgumentException e) { listener.diagnostic("connect_failure=" + failureDetails(e)); reconnect(attempt); }
    }
    private void monitor(int attempt) {
        if (!running || attempt != generation) return;
        worker.schedule(() -> {
            if (!running || attempt != generation) return;
            long now = clock.getAsLong();
            if (now - lastMonitor > 20_000) listener.diagnostic("scheduler_gap_ms=" + (now - lastMonitor));
            lastMonitor = now;
            protocol.tick(now);
            if (!running || attempt != generation) return;
            if (now - lastReceived > 30_000 || socket == null || !socket.send("{\"type\":\"heartbeat\"}")) {
                listener.diagnostic("heartbeat_timeout_ms=" + (now - lastReceived)); reconnect(attempt); return;
            }
            monitor(attempt);
        }, 10, TimeUnit.SECONDS);
    }
    private void reconnect(int attempt) {
        if (!running || attempt != generation) return;
        ++generation;
        WebSocket previous = socket; socket = null;
        if (previous != null) previous.cancel();
        listener.status("连接已断开，正在重连…");
        listener.diagnostic("retry_delay_s=" + retry);
        int delay = retry; retry = Math.min(30, retry * 2);
        retryTask = worker.schedule(this::connect, delay, TimeUnit.SECONDS);
    }
    public void networkAvailable() {
        dispatch(() -> {
            listener.diagnostic("default_network_changed");
            if (retryTask != null) retryTask.cancel(false);
            ++generation;
            WebSocket previous = socket; socket = null;
            if (previous != null) previous.cancel();
            retry = 1; connect();
        });
    }
    static String failureDetails(Throwable error) {
        StringBuilder value = new StringBuilder();
        for (int depth = 0; error != null && depth < 4; depth++, error = error.getCause()) {
            if (value.length() > 0) value.append(" <- ");
            value.append(error.getClass().getSimpleName()).append(": ");
            String message = String.valueOf(error.getMessage()).replace('\n', ' ').replace('\r', ' ')
                    .replaceAll("[A-Za-z0-9_-]{32,}", "[redacted]");
            value.append(message, 0, Math.min(message.length(), 200));
        }
        return value.toString();
    }
    private void fail(String message) {
        if (!running) return;
        listener.fatal(message); close();
    }
    @Override public synchronized void close() {
        running = false;
        Call currentCall = call; if (currentCall != null) currentCall.cancel();
        WebSocket currentSocket = socket; if (currentSocket != null) currentSocket.cancel();
        worker.shutdownNow(); http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
    }
}
