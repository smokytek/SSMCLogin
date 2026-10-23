package it.ssmc.login.core;
import java.util.*;

/** Per-address limit persists across reconnects within the current server process. */
public final class AttemptLimit {
    private record Window(long start, int count) {}
    private final Map<String, Window> windows = new HashMap<>();
    public synchronized boolean allow(String address) {
        long now = System.currentTimeMillis();
        if (windows.size() >= 10_000) windows.values().removeIf(w -> now - w.start >= 60_000);
        Window w = windows.get(address);
        if (w == null || now - w.start >= 60_000) {
            if (windows.size() >= 10_000 && w == null) return false;
            windows.put(address, new Window(now, 1)); return true;
        }
        if (w.count >= 10) return false;
        windows.put(address, new Window(w.start, w.count + 1)); return true;
    }
}
