package com.runnerpulse.connect;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Pure-Java serialization of Android Health Connect requests (UI thread). */
final class NativeHealthRequestQueue {
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "authorize", "revoke", "revoke-auto", "status", "sync", "steps", "sleep", "vitals", "home"));
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private String active;

    synchronized String enqueue(String action) {
        if (!ACTIONS.contains(action) || action.equals(active) || pending.contains(action)) return null;
        pending.addLast(action);
        return startNext();
    }

    synchronized String complete(String result) {
        if (active == null || !isResultForActive(result)) return null;
        active = null;
        return startNext();
    }

    synchronized String abort(String action) {
        if (active == null || !active.equals(action)) return null;
        active = null;
        return startNext();
    }

    synchronized String current() { return active; }

    private String startNext() {
        if (active != null || pending.isEmpty()) return null;
        active = pending.removeFirst();
        return active;
    }

    private boolean isResultForActive(String result) {
        if ("permission".equals(result)) {
            return "status".equals(active) || "authorize".equals(active)
                    || "revoke".equals(active) || "revoke-auto".equals(active);
        }
        return active.equals(result);
    }
}
