package com.offworkpet.receiver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the APK's actual Java transport against a temporary real Python relay. */
public class RelayProbe {
    public static void main(String[] args) throws Exception {
        CountDownLatch received = new CountDownLatch(3);
        AtomicReference<String> failure = new AtomicReference<>();
        RelayClient.Identity identity = new RelayClient.Identity() {
            private String value = "";
            public String load() { return value; }
            public void save(String token) { value = token; }
        };
        try (RelayClient receiver = new RelayClient(args[0], args[1], "手机测试端", identity,
                () -> System.nanoTime() / 1_000_000, new RelayClient.Listener() {
            public void status(String text) { System.out.println(AlertProtocol.json("event", "status", "text", text)); }
            public void alert(AlertProtocol.Alert signal) {
                System.out.println(AlertProtocol.json("event", "alert", "label", signal.label, "level", signal.level, "remaining_ms", signal.remainingMillis));
                received.countDown();
            }
            public void fatal(String text) { failure.set(text); while (received.getCount() > 0) received.countDown(); }
        })) {
            receiver.start();
            if (!received.await(60, TimeUnit.SECONDS)) throw new AssertionError("Real relay did not deliver three alerts");
            if (failure.get() != null) throw new AssertionError(failure.get());
            System.out.println("{\"event\":\"passed\"}");
        }
    }
}
