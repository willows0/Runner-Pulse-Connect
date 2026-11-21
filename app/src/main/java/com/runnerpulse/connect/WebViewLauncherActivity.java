package com.runnerpulse.connect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import java.util.ArrayList;
import android.os.Build;
import android.content.pm.PackageManager;
import com.google.firebase.messaging.FirebaseMessaging;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.view.View;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.browser.customtabs.CustomTabsIntent;

/** Full-screen web shell. Google OAuth cannot be completed inside embedded WebView. */
public final class WebViewLauncherActivity extends Activity {
    private static final String HOME = "https://runner-pulse-web.onrender.com/";
    private static final int FILE_CHOOSER_REQUEST = 8021;
    private static final int SPEECH_INPUT_REQUEST = 8023;
    private ValueCallback<Uri[]> fileChooserCallback;
    private WebView web;
    private ProgressBar progress;
    private TextView error;
    private String pendingState;
    private String pendingCalendarResult;
    private FrameLayout splash;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int splashChecks;
    private void dismissSplash() {
        if (splash == null) return;
        FrameLayout old = splash;
        splash = null;
        old.setVisibility(View.GONE);
        if (old.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) old.getParent()).removeView(old);
        }
    }
    private long lastBackPressedAt = 0L;
    // Avoid launching repeated Health Connect activities during Home startup.
    // The web page can request the same data repeatedly as widgets initialize.
    private long lastHealthStatusAt = 0L;
    private long lastHealthStepsAt = 0L;
    private long lastHealthSyncAt = 0L;
    private long lastHealthSleepAt = 0L;
    private long lastHealthVitalsAt = 0L;
    private long lastHealthHomeAt = 0L;
    // Only one native Health Connect activity at a time. Without this queue,
    // concurrent Home / authorization / exercise requests can clear an earlier
    // activity and cancel its lifecycleScope while it is still reading records.
    private final NativeHealthRequestQueue healthQueue = new NativeHealthRequestQueue();
    private boolean healthActivityInBackground = false;

    private void requestNativeHealth(String action) {
        if (!allowHealthRequest(action)) return;
        String next = healthQueue.enqueue(action);
        if (next != null) launchNativeHealth(next);
    }

    private void launchNativeHealth(String action) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("runnerpulseconnect://" + action));
            startActivity(intent);
        } catch (Exception error) {
            android.util.Log.w("RunnerPulseHealth", "Impossibile avviare la lettura: " + action, error);
            String next = healthQueue.abort(action);
            if (next != null) handler.post(() -> launchNativeHealth(next));
        }
    }

    private void finishNativeHealth(String resultType) {
        String next = healthQueue.complete(resultType);
        // Return control to the WebView before starting another transparent activity.
        if (next != null) handler.postDelayed(() -> launchNativeHealth(next), 350L);
    }

    private boolean allowHealthRequest(String kind) {
        long now = SystemClock.elapsedRealtime();
        if ("status".equals(kind)) {
            if (lastHealthStatusAt != 0L && now - lastHealthStatusAt < 20000L) return false;
            lastHealthStatusAt = now;
        } else if ("steps".equals(kind)) {
            if (lastHealthStepsAt != 0L && now - lastHealthStepsAt < 30000L) return false;
            lastHealthStepsAt = now;
        } else if ("home".equals(kind)) {
            if (lastHealthHomeAt != 0L && now - lastHealthHomeAt < 60000L) return false;
            lastHealthHomeAt = now;
        } else if ("sleep".equals(kind)) {
            if (lastHealthSleepAt != 0L && now - lastHealthSleepAt < 120000L) return false;
            lastHealthSleepAt = now;
        } else if ("vitals".equals(kind)) {
            if (lastHealthVitalsAt != 0L && now - lastHealthVitalsAt < 120000L) return false;
            lastHealthVitalsAt = now;
        } else if ("sync".equals(kind)) {
            if (lastHealthSyncAt != 0L && now - lastHealthSyncAt < 60000L) return false;
            lastHealthSyncAt = now;
        }
        return true;
    }
    private static final int PUSH_PERMISSION_REQUEST = 8017;
    private boolean pushPermissionPending = false;
    private void emitPushToken(String token, String error) {
        if (web == null) return;
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:native-push-token',{detail:{token:" + JSONObject.quote(token == null ? "" : token) + ",error:" + JSONObject.quote(error == null ? "" : error) + "}}))";
        web.post(() -> web.evaluateJavascript(js, null));
    }
    // Esternalizza la dettatura al riconoscitore vocale Android: nessun audio
    // viene letto dalla WebView, il telefono restituisce soltanto il testo.
    private void emitSpeechResult(String text, String error, boolean cancelled) {
        if (web == null) return;
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:native-speech-result',{detail:{text:"
            + JSONObject.quote(text == null ? "" : text) + ",error:"
            + JSONObject.quote(error == null ? "" : error) + ",cancelled:"
            + (cancelled ? "true" : "false") + "}}));";
        web.post(() -> web.evaluateJavascript(js, null));
    }
    private void startSpeechInput() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Detta il messaggio per Runner Pulse");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            startActivityForResult(intent, SPEECH_INPUT_REQUEST);
        } catch (android.content.ActivityNotFoundException e) {
            emitSpeechResult(null, "Riconoscimento vocale Android non disponibile. Attiva la dettatura Google o usa il microfono della tastiera.", false);
        } catch (Exception e) {
            emitSpeechResult(null, "Impossibile avviare la dettatura Android. Controlla le impostazioni vocali del telefono.", false);
        }
    }
    private void obtainPushToken(boolean promptPermission) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            if (!promptPermission) { emitPushToken(null, "Autorizzazione notifiche non concessa."); return; }
            pushPermissionPending = true;
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, PUSH_PERMISSION_REQUEST);
            return;
        }
        RunnerPulseMessagingService.createChannel(this);
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) emitPushToken(task.getResult(), null);
            else emitPushToken(null, "Token Firebase non disponibile. Riprova.");
        });
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PUSH_PERMISSION_REQUEST || !pushPermissionPending) return;
        pushPermissionPending = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) obtainPushToken(false);
        else emitPushToken(null, "Notifiche bloccate: abilita Runner Pulse nelle impostazioni Android.");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {pendingState=state.getString("mobileLoginState");pendingCalendarResult=state.getString("calendarResult");}
        // Explicit insets: Android 15 enforces edge-to-edge even for target SDK 35.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(0);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(14, 25, 48));
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars() |
                WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(14, 25, 48));
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.rgb(244, 113, 28));
        frame.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        TextView splashTitle = new TextView(this);
        splashTitle.setText("RUNNER PULSE");
        splashTitle.setTextColor(Color.WHITE);
        splashTitle.setTextSize(30);
        splashTitle.setTypeface(null, Typeface.BOLD);
        splashTitle.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams titleParams=new FrameLayout.LayoutParams(-1, 100, android.view.Gravity.CENTER);
        titleParams.bottomMargin=160;
        splash.addView(splashTitle,titleParams);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, 12, android.view.Gravity.CENTER);
        p.leftMargin=70; p.rightMargin=70; p.topMargin=80;
        splash.addView(progress, p);
        error = new TextView(this);
        error.setTextColor(Color.WHITE);
        error.setTextSize(16);
        error.setPadding(40, 40, 40, 40);
        error.setVisibility(View.GONE);
        frame.addView(error, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER));
        setContentView(frame);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        // Android document picker returns content:// URIs, not file:// paths.
        settings.setAllowContentAccess(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        settings.setUserAgentString(settings.getUserAgentString() + " RunnerPulseAndroidWebView/1");
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void requestSpeechInput() {
                runOnUiThread(() -> {
                    // Consentito soltanto al sito Runner Pulse caricato via HTTPS.
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    startSpeechInput();
                });
            }
            @JavascriptInterface public void requestPushToken() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    obtainPushToken(true);
                });
            }
            @JavascriptInterface public void requestPushStatusToken() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    obtainPushToken(false);
                });
            }
            @JavascriptInterface public void requestPermissionStatus() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("status");
                });
            }
            @JavascriptInterface public void requestHealthAuthorization() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("authorize");
                });
            }
            @JavascriptInterface public void requestHealthRevocation() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("revoke-auto");
                });
            }
            @JavascriptInterface public void requestSteps() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("steps");
                });
            }
            @JavascriptInterface public void requestHomeHealth() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("home");
                });
            }
            @JavascriptInterface public void requestSleep() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("sleep");
                });
            }
            @JavascriptInterface public void requestVitals() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("vitals");
                });
            }
            @JavascriptInterface public void requestExerciseSync() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    requestNativeHealth("sync");
                });
            }
        }, "RunnerPulseHealthNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("*/*");
                    picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream", "application/zip", "application/x-zip-compressed",
                        "application/vnd.ant.fit", "application/xml", "text/xml", "*/*"});
                    startActivityForResult(picker, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                    android.widget.Toast.makeText(WebViewLauncherActivity.this,
                        "Impossibile aprire i file Android", android.widget.Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("runnerpulsecalendar".equalsIgnoreCase(scheme) && "start".equalsIgnoreCase(uri.getHost())) {
                    String target = uri.getQueryParameter("url");
                    if (target == null) return true;
                    try {
                        Uri browserUrl=Uri.parse(target);
                        // Accettiamo soltanto l'URL di autorizzazione Google creato dal nostro server.
                        if (!"https".equalsIgnoreCase(browserUrl.getScheme()) ||
                            !"accounts.google.com".equalsIgnoreCase(browserUrl.getHost()) ||
                            !"/o/oauth2/v2/auth".equals(browserUrl.getPath()) ||
                            browserUrl.getQueryParameter("state") == null) return true;
                        try { new CustomTabsIntent.Builder().build().launchUrl(WebViewLauncherActivity.this, browserUrl); }
                        catch(Exception e){ startActivity(new Intent(Intent.ACTION_VIEW,browserUrl)); }
                    } catch(Exception ignored) { }
                    return true;
                }
                if ("runnerpulseauth".equalsIgnoreCase(scheme) && "start".equalsIgnoreCase(uri.getHost())) {
                    String state = uri.getQueryParameter("state");
                    if (state != null && state.matches("[A-Za-z0-9_-]{32,128}")) {
                        pendingState = state;
                        Uri browserUrl = Uri.parse("https://runner-pulse-web.onrender.com/mobile-login.html?state=" + Uri.encode(state));
                        try { new CustomTabsIntent.Builder().build().launchUrl(WebViewLauncherActivity.this, browserUrl); }
                        catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, browserUrl)); }
                    }
                    return true;
                }
                if ("runnerpulseconnect".equalsIgnoreCase(scheme) ||
                    ("intent".equalsIgnoreCase(scheme) && "authorize".equalsIgnoreCase(uri.getHost()) &&
                     uri.toString().contains("scheme=runnerpulseconnect"))) {
                    try {
                        String action = "authorize";
                        if ("runnerpulseconnect".equalsIgnoreCase(scheme)) {
                            String hostAction = uri.getHost();
                            if ("revoke".equalsIgnoreCase(hostAction) || "status".equalsIgnoreCase(hostAction) || "sync".equalsIgnoreCase(hostAction) || "steps".equalsIgnoreCase(hostAction)) action = hostAction;
                        }
                        requestNativeHealth(action);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(WebViewLauncherActivity.this,
                            "Impossibile aprire Health Connect: " + e.getClass().getSimpleName(),
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                    return true;
                }
                if (!"https".equalsIgnoreCase(scheme)) return true;
                String host = uri.getHost();
                if (host != null && (host.equals("runner-pulse-web.onrender.com"))) return false;
                // Google/Firebase auth must use a real browser; browser cookies are not shared with WebView.
                try { new CustomTabsIntent.Builder().build().launchUrl(WebViewLauncherActivity.this, uri); }
                catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                // The web login gate is optional: never leave a full-screen splash
                // covering a successfully loaded site when its markup changes.
                if (url != null && url.startsWith(HOME)) {
                    handler.postDelayed(() -> {
                        if (web != null && splash != null && splash.getVisibility() == View.VISIBLE) {
                            dismissSplash();
                        }
                    }, 1200);
                }
                if (url != null && url.startsWith(HOME)) { checkAppReady(); deliverCalendarResult(); }
            }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) showError("Impossibile caricare Runner Pulse. Controlla la connessione e riapri l'app.");
            }
        });
        // Production: open Runner Pulse directly, without diagnostic pages.
        web.loadUrl(HOME);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH_INPUT_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> candidates = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (candidates != null && !candidates.isEmpty() && candidates.get(0) != null && !candidates.get(0).trim().isEmpty()) {
                    emitSpeechResult(candidates.get(0), null, false);
                } else {
                    emitSpeechResult(null, "Nessuna parola riconosciuta. Riprova.", false);
                }
            } else {
                emitSpeechResult(null, null, true);
            }
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST) return;
        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        if (callback == null) return;
        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                uris = new Uri[count];
                for (int i = 0; i < count; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) uris = new Uri[]{data.getData()};
        }
        callback.onReceiveValue(uris);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveLoginCallback(intent);
        receiveCalendarCallback(intent);
        receiveHealthCallback(intent);
        receiveHealthSync(intent);
        receiveHealthSteps(intent);
        receiveHealthSleep(intent);
        receiveHealthVitals(intent);
        receiveHealthHome(intent);
        receiveNotificationTap(intent);
    }
    @Override protected void onPause() {
        // Mark only pauses caused while a Health Connect request is active.
        if (healthQueue.current() != null) healthActivityInBackground = true;
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        receiveLoginCallback(getIntent());
        receiveCalendarCallback(getIntent());
        receiveHealthCallback(getIntent());
        receiveHealthSync(getIntent());
        receiveHealthSteps(getIntent());
        receiveHealthSleep(getIntent());
        receiveHealthVitals(getIntent());
        receiveHealthHome(getIntent());
        receiveNotificationTap(getIntent());
        if (healthActivityInBackground) {
            healthActivityInBackground = false;
            // Android may bring the WebView back without a callback when the
            // transparent native activity is dismissed. Recover the queue only
            // when this WebView is actually foreground and no result arrived.
            final String awaiting = healthQueue.current();
            if (awaiting != null) handler.postDelayed(() -> {
                if (!awaiting.equals(healthQueue.current()) || !hasWindowFocus()) return;
                android.util.Log.w("RunnerPulseHealth", "Lettura interrotta prima del risultato: " + awaiting);
                if ("sync".equals(awaiting) && web != null) {
                    web.evaluateJavascript("window.dispatchEvent(new CustomEvent('runnerpulse:health-sync',{detail:{error:'Lettura interrotta. Riprova dal Profilo.'}}))", null);
                }
                String next = healthQueue.abort(awaiting);
                if (next != null) handler.postDelayed(() -> launchNativeHealth(next), 350L);
            }, 1500L);
        }
    }
    private void receiveNotificationTap(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("runnerpulse_notification_path")) return;
        String path = intent.getStringExtra("runnerpulse_notification_path");
        intent.removeExtra("runnerpulse_notification_path");
        if (path != null && path.startsWith("/") && !path.startsWith("//")) web.loadUrl(HOME.substring(0, HOME.length()-1) + path);
    }
    private void receiveHealthCallback(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("healthconnect_result")) return;
        String result = intent.getStringExtra("healthconnect_result");
        intent.removeExtra("healthconnect_result");
        if (result == null || !result.matches("authorized|partial|denied|error|unavailable")) return;
        // Aggiorna il pulsante nella pagina corrente senza ricaricare la WebView.
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:health-permission-status',{detail:{status:"
            + JSONObject.quote(result) + "}}))";
        web.post(() -> web.evaluateJavascript(js, null));
        finishNativeHealth("permission");
    }
    private void receiveHealthSteps(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("healthconnect_steps")) return;
        String payload = intent.getStringExtra("healthconnect_steps");
        intent.removeExtra("healthconnect_steps");
        if (payload == null) return;
        try { new JSONObject(payload); } catch (Exception e) { return; }
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:health-steps',{detail:" + payload + "}))";
        web.post(() -> web.evaluateJavascript(js, null));
        finishNativeHealth("steps");
    }
    private void receiveHealthHome(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("healthconnect_home")) return;
        String payload=intent.getStringExtra("healthconnect_home");
        intent.removeExtra("healthconnect_home");
        if (payload == null) return;
        try {
            JSONObject parts=new JSONObject(payload);
            StringBuilder js=new StringBuilder();
            for (String part: new String[]{"steps","sleep","vitals"}) {
                JSONObject data=parts.optJSONObject(part);
                if (data == null) continue;
                String event=part.equals("steps")?"runnerpulse:health-steps":part.equals("sleep")?"runnerpulse:health-sleep":"runnerpulse:health-vitals";
                js.append("window.dispatchEvent(new CustomEvent(").append(JSONObject.quote(event)).append(",{detail:").append(data.toString()).append("}));");
            }
            if (js.length()>0) web.post(() -> web.evaluateJavascript(js.toString(),null));
        finishNativeHealth("home");
        } catch (Exception ignored) { }
    }
    private void receiveHealthSleep(Intent intent) {
        emitNativeResult(intent,"healthconnect_sleep","runnerpulse:health-sleep");
    }
    private void receiveHealthVitals(Intent intent) {
        emitNativeResult(intent,"healthconnect_vitals","runnerpulse:health-vitals");
    }
    private void emitNativeResult(Intent intent,String extra,String event) {
        if (web == null || intent == null || !intent.hasExtra(extra)) return;
        String payload=intent.getStringExtra(extra);
        intent.removeExtra(extra);
        if (payload == null) return;
        try { new JSONObject(payload); } catch (Exception e) { return; }
        String js="window.dispatchEvent(new CustomEvent(" + JSONObject.quote(event) + ",{detail:" + payload + "}))";
        web.post(() -> web.evaluateJavascript(js,null));
        finishNativeHealth("healthconnect_sleep".equals(extra) ? "sleep" : "vitals");
    }
    private void receiveHealthSync(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("healthconnect_sync")) return;
        String payload = intent.getStringExtra("healthconnect_sync");
        intent.removeExtra("healthconnect_sync");
        if (payload == null) return;
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:health-sync',{detail:" + payload + "}))";
        web.post(() -> web.evaluateJavascript(js, null));
        finishNativeHealth("sync");
    }
    private void receiveCalendarCallback(Intent intent) {
        if (web == null || intent == null || intent.getData() == null) return;
        Uri uri=intent.getData();
        if (!"runnerpulsecalendar".equalsIgnoreCase(uri.getScheme()) ||
            !"callback".equalsIgnoreCase(uri.getHost())) return;
        String result=uri.getQueryParameter("result");
        // Non sono accettati codici OAuth o token nel deep link Android.
        if (result == null || !result.matches("connected|invalid_client|invalid_grant|callback_error|missing_refresh|token_missing|consent_denied|missing_code|expired_state|state_storage|invalid_state")) return;
        pendingCalendarResult=result;
        intent.setData(null);
        deliverCalendarResult();
    }
    private void deliverCalendarResult() {
        if (web==null || pendingCalendarResult==null) return;
        final String value=pendingCalendarResult;
        web.evaluateJavascript("Boolean(window.rpActivatePage && document.getElementById('rp-calendar-status'))", ready -> {
            if (!"true".equals(ready)) return; // onPageFinished consegnerà il risultato quando la pagina è pronta.
            if (!value.equals(pendingCalendarResult)) return;
            pendingCalendarResult=null;
            String js="window.rpActivatePage('plan');window.dispatchEvent(new CustomEvent('runnerpulse:calendar-callback',{detail:{result:"+JSONObject.quote(value)+"}}));";
            web.evaluateJavascript(js,null);
        });
    }
    private void receiveLoginCallback(Intent intent) {
        if (web == null || intent == null || intent.getData() == null) return;
        Uri uri=intent.getData();
        if (!"runnerpulseauth".equalsIgnoreCase(uri.getScheme()) || !"callback".equalsIgnoreCase(uri.getHost())) return;
        String state=uri.getQueryParameter("state"),code=uri.getQueryParameter("code");
        if (state==null || !state.equals(pendingState) || code==null || !code.matches("[A-Za-z0-9_-]{32,128}")) return;
        pendingState=null;
        intent.setData(null);
        String js="window.runnerPulseCompleteNativeLogin("+org.json.JSONObject.quote(state)+","+org.json.JSONObject.quote(code)+")";
        web.post(()->web.evaluateJavascript(js,null));
    }
    private void checkAppReady() {
        if (web == null || splash == null || splash.getVisibility() != View.VISIBLE) return;
        web.evaluateJavascript("Boolean(document.getElementById('rp-login-gate'))", value -> {
            if ("true".equals(value)) { dismissSplash(); return; }
            if (++splashChecks < 150) handler.postDelayed(this::checkAppReady, 800);
            else showError("Runner Pulse impiega troppo tempo a caricarsi. Riapri l'app.");
        });
    }
    private void showError(String message) { dismissSplash(); progress.setVisibility(View.GONE); error.setText(message); error.setVisibility(View.VISIBLE); }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); out.putString("mobileLoginState",pendingState);out.putString("calendarResult",pendingCalendarResult); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() {
        // A second Back press always exits, even if the web app has accumulated
        // a long SPA navigation history or a modal has trapped navigation.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBackPressedAt < 2200) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        if (web != null && web.canGoBack()) {
            web.goBack();
            android.widget.Toast.makeText(this, "Premi di nuovo Indietro per uscire", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            finish();
        }
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (fileChooserCallback != null) { fileChooserCallback.onReceiveValue(null); fileChooserCallback = null; } if (web != null) { web.destroy(); web = null; } super.onDestroy(); }
}
