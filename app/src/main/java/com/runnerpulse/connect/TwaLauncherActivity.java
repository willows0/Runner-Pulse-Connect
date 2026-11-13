package com.runnerpulse.connect;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Entry point: launch the verified Trusted Web Activity without an intermediate screen. */
public class TwaLauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    @Override public void onCreate(Bundle state) {
        try {
            super.onCreate(state);
        } catch (RuntimeException error) {
            Log.e("RunnerPulseTWA", "TWA launch failed", error);
            getSharedPreferences("rp_twa_diagnostic", MODE_PRIVATE).edit()
                .putString("last_error", Log.getStackTraceString(error)).apply();
            try { startActivity(new Intent(this, DirectLauncherActivity.class)); }
            catch (RuntimeException fallbackError) {
                Log.e("RunnerPulseTWA", "Fallback launch failed", fallbackError);
            }
            finish();
        }
    }
}
