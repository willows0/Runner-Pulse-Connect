package com.runnerpulse.connect;

public final class NativeHealthRequestQueueTest {
    private static int passed = 0;
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++;
    }
    private static void eq(String actual, String expected, String description) {
        check(expected == null ? actual == null : expected.equals(actual),
                description + " (atteso " + expected + ", ottenuto " + actual + ")");
    }
    public static void main(String[] args) {
        NativeHealthRequestQueue q = new NativeHealthRequestQueue();
        eq(q.enqueue("status"), "status", "status begins");
        eq(q.enqueue("sync"), null, "sync waits while status is active");
        eq(q.enqueue("home"), null, "home waits behind sync");
        eq(q.enqueue("sync"), null, "duplicate queued sync ignored");
        eq(q.enqueue("status"), null, "duplicate active status ignored");
        eq(q.current(), "status", "status remains active");
        eq(q.complete("sync"), null, "unrelated callback cannot complete current job");
        eq(q.current(), "status", "unrelated callback did not advance");
        eq(q.complete("permission"), "sync", "permission response begins sync");
        eq(q.current(), "sync", "sync now active");
        eq(q.complete("sync"), "home", "sync response begins home");
        eq(q.complete("home"), null, "home finishes queue");
        eq(q.current(), null, "queue empty after home");
        eq(q.enqueue("unknown"), null, "unknown action cannot start");
        eq(q.enqueue("authorize"), "authorize", "authorization starts");
        eq(q.complete("permission"), null, "authorization returns a permission callback");
        eq(q.enqueue("revoke-auto"), "revoke-auto", "revoke starts");
        eq(q.complete("permission"), null, "revoke returns a permission callback");
        eq(q.enqueue("steps"), "steps", "steps starts");
        eq(q.enqueue("sleep"), null, "sleep waits");
        eq(q.abort("not-steps"), null, "wrong abort cannot cancel current");
        eq(q.abort("steps"), "sleep", "failed native activity unblocks queue");
        eq(q.complete("sleep"), null, "sleep finishes");
        eq(q.enqueue("vitals"), "vitals", "vitals starts");
        eq(q.complete("vitals"), null, "vitals finishes");
        System.out.println("OK: " + passed + " verifiche NativeHealthRequestQueue");
    }
}
