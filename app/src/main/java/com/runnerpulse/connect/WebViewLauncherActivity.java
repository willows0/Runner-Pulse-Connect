package com.runnerpulse.connect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14, 25, 48));
        getWindow().setNavigationBarColor(Color.rgb(14, 25, 48));
        FrameLayout frame = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(14, 25, 48));
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(110, 110, android.view.Gravity.CENTER);
        frame.addView(progress, p);
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
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("runnerpulseconnect".equalsIgnoreCase(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(getPackageName())); }
                    catch (Exception e) { showError("Health Connect non disponibile: " + e.getClass().getSimpleName()); }
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
            @Override public void onPageFinished(WebView view, String url) { progress.setVisibility(View.GONE); }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) showError("Impossibile caricare Runner Pulse. Controlla la connessione e riapri l'app.");
            }
        });
        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }
    private void showError(String message) { progress.setVisibility(View.GONE); error.setText(message); error.setVisibility(View.VISIBLE); }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (web != null) { web.destroy(); web = null; } super.onDestroy(); }
}
