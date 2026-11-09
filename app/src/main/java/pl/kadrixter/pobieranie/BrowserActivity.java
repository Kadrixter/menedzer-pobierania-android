package pl.kadrixter.pobieranie;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Wbudowana przeglądarka: użytkownik przeklikuje stronę (ostrzeżenia, liczniki, przyciski „Pobierz").
 * Gdy strona zacznie pobierać plik, łapiemy prawdziwy link + ciasteczka, zamykamy stronę
 * i oddajemy pobieranie do menedżera.
 */
public class BrowserActivity extends Activity {
    static final String EXTRA_ID = "id";

    private WebView web;
    private String itemId;
    private boolean caught;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_browser);
        itemId = getIntent().getStringExtra(EXTRA_ID);
        Downloader.Item it = itemId == null ? null : Downloader.get(this).find(itemId);
        if (it == null) { finish(); return; }

        TextView url = findViewById(R.id.url);
        ProgressBar prog = findViewById(R.id.prog);
        findViewById(R.id.close).setOnClickListener(v -> finish());

        web = findViewById(R.id.web);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false); // target=_blank otwiera się w tym samym oknie
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                // intent://, market:// itp. – nie wychodź z apki, zostań na stronie
                return sc == null || !(sc.equals("http") || sc.equals("https"));
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
                url.setText(u);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                prog.setProgress(p);
                prog.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });

        // strona zaczęła pobierać plik -> przejmij go
        web.setDownloadListener((dlUrl, userAgent, contentDisposition, mimetype, length) -> {
            if (caught) return;
            if (dlUrl == null || !(dlUrl.startsWith("http://") || dlUrl.startsWith("https://"))) {
                Toast.makeText(this, "Ta strona generuje plik w przeglądarce (blob) – nie da się go przejąć", Toast.LENGTH_LONG).show();
                return;
            }
            caught = true;
            cm.flush();
            Downloader.get(this).resolveFromWeb(itemId, dlUrl, userAgent, web.getUrl());
            DownloadService.ensureRunning(this);
            Toast.makeText(this, "Wykryto plik – pobieranie ruszyło", Toast.LENGTH_SHORT).show();
            finish();
        });

        String start = it.origUrl != null ? it.origUrl : it.url;
        url.setText(start);
        web.loadUrl(start);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
