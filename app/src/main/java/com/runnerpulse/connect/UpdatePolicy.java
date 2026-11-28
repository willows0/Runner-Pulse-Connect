package com.runnerpulse.connect;

/** Pure update policy; no network or Android dependencies. */
final class UpdatePolicy {
    static final String DOWNLOAD_PATH = "/runner-pulse.apk";
    static final long SNOOZE_MS = 24L * 60 * 60 * 1000;
    static boolean validRelease(long installed, long offered, String name, String path) {
        return installed > 0 && offered > installed && offered <= 2100000000L
            && name != null && !name.trim().isEmpty() && name.length() <= 100
            && DOWNLOAD_PATH.equals(path);
    }
    static boolean snoozed(long offered, long dismissedVersion, long until, long now) {
        return offered == dismissedVersion && until > now && until - now <= SNOOZE_MS;
    }
    private UpdatePolicy() {}
}
