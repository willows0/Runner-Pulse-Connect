package com.runnerpulse.connect;
final class UpdatePolicyTest {
    private static int n;
    private static void test(boolean condition) { if (!condition) throw new AssertionError("Test " + (n+1)); n++; }
    public static void main(String[] args) {
        String p = UpdatePolicy.DOWNLOAD_PATH;
        test(UpdatePolicy.validRelease(30,31,"3.1",p));
        test(!UpdatePolicy.validRelease(31,31,"3.1",p));
        test(!UpdatePolicy.validRelease(31,30,"3.0",p));
        test(!UpdatePolicy.validRelease(0,31,"3.1",p));
        test(!UpdatePolicy.validRelease(31,2100000001L,"4.0",p));
        test(!UpdatePolicy.validRelease(31,32,"",p));
        test(!UpdatePolicy.validRelease(31,32,null,p));
        test(!UpdatePolicy.validRelease(31,32,"3.2","https://other.invalid/app.apk"));
        test(!UpdatePolicy.validRelease(31,32,"3.2","//other.invalid/app.apk"));
        test(!UpdatePolicy.validRelease(31,32,"3.2","/runner-pulse.apk?redirect=x"));
        long now = 100000L;
        test(UpdatePolicy.snoozed(32,32,now+UpdatePolicy.SNOOZE_MS,now));
        test(!UpdatePolicy.snoozed(33,32,now+1000,now));
        test(!UpdatePolicy.snoozed(32,32,now,now));
        test(!UpdatePolicy.snoozed(32,32,now+UpdatePolicy.SNOOZE_MS+1,now));
        System.out.println(n+" update policy tests passed");
    }
}
