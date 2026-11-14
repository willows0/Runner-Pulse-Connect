package com.runnerpulse.connect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.browser.customtabs.CustomTabsIntent;

/** Full-screen web shell. Google OAuth cannot be completed inside embedded WebView. */
public final class WebViewLauncherActivity extends Activity {
    private static final String HOME = "https://runner-pulse-web.onrender.com/";
    private WebView web;
    private ProgressBar progress;
    private TextView error;
    private String pendingState;
    private FrameLayout splash;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int splashChecks;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingState=state.getString("mobileLoginState");
        getWindow().setStatusBarColor(Color.rgb(14, 25, 48));
        getWindow().setNavigationBarColor(Color.rgb(14, 25, 48));
        FrameLayout frame = new FrameLayout(this);
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
        settings.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        settings.setUserAgentString(settings.getUserAgentString() + " RunnerPulseAndroidWebView/1");
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void requestPermissionStatus() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    Intent i = new Intent(WebViewLauncherActivity.this, MainActivity.class);
                    i.setAction(Intent.ACTION_VIEW);
                    i.setData(Uri.parse("runnerpulseconnect://status"));
                    startActivity(i);
                });
            }
            @JavascriptInterface public void requestSteps() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    Intent i = new Intent(WebViewLauncherActivity.this, MainActivity.class);
                    i.setAction(Intent.ACTION_VIEW);
                    i.setData(Uri.parse("runnerpulseconnect://steps"));
                    startActivity(i);
                });
            }
            @JavascriptInterface public void requestExerciseSync() {
                runOnUiThread(() -> {
                    if (web == null || web.getUrl() == null || !web.getUrl().startsWith(HOME)) return;
                    Intent i = new Intent(WebViewLauncherActivity.this, MainActivity.class);
                    i.setAction(Intent.ACTION_VIEW);
                    i.setData(Uri.parse("runnerpulseconnect://sync"));
                    startActivity(i);
                });
            }
        }, "RunnerPulseHealthNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
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
                        Intent health = new Intent(WebViewLauncherActivity.this, MainActivity.class);
                        health.setAction(Intent.ACTION_VIEW);
                        health.setData(Uri.parse("runnerpulseconnect://" + action));
                        startActivity(health);
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
            @Override public void onPageFinished(WebView view, String url) { checkAppReady(); }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) showError("Impossibile caricare Runner Pulse. Controlla la connessione e riapri l'app.");
            }
        });
        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveLoginCallback(intent);
        receiveHealthCallback(intent);
        receiveHealthSync(intent);
        receiveHealthSteps(intent);
    }
    @Override protected void onResume() {
        super.onResume();
        receiveLoginCallback(getIntent());
        receiveHealthCallback(getIntent());
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
    }
    private void receiveHealthSteps(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("healthconnect_steps")) return;
        String payload = intent.getStringExtra("healthconnect_steps");
        intent.removeExtra("healthconnect_steps");
        if (payload == null) return;
        try { new JSONObject(payload); } catch (Exception e) { return; }
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:health-steps',{detail:" + payload + "}))";
        web.post(() -> web.evaluateJavascript(js, null));
    }
    private void receiveHealthSync(Intent intent) {
        if (web == null || intent == null || !intent.hasExtra("healthconnect_sync")) return;
        String payload = intent.getStringExtra("healthconnect_sync");
        intent.removeExtra("healthconnect_sync");
        if (payload == null) return;
        String js = "window.dispatchEvent(new CustomEvent('runnerpulse:health-sync',{detail:" + payload + "}))";
        web.post(() -> web.evaluateJavascript(js, null));
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
            if ("true".equals(value)) { splash.setVisibility(View.GONE); return; }
            if (++splashChecks < 150) handler.postDelayed(this::checkAppReady, 800);
            else showError("Runner Pulse impiega troppo tempo a caricarsi. Riapri l'app.");
        });
    }
    private void showError(String message) { if(splash!=null)splash.setVisibility(View.GONE); progress.setVisibility(View.GONE); error.setText(message); error.setVisibility(View.VISIBLE); }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); out.putString("mobileLoginState",pendingState); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (web != null) { web.destroy(); web = null; } super.onDestroy(); }
}
