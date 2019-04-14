package bonjourbrowser.de.wellenvogel.bonjourbrowser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Method;
import java.net.URI;

public class WebViewActivity extends AppCompatActivity {

    static final String URL_PARAM="url";

    private WebView webView;

    private class MyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
            /*
            if (Uri.parse(url).getHost().equals("https://www.example.com")) {
                // This is my website, so do not override; let my WebView load the page
                return false;
            }
            // Otherwise, the link is not for a page on my site, so launch another Activity that handles URLs
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
            return true;
            */
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        webView=new WebView(this);
        setContentView(webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.setWebViewClient(new MyWebViewClient());
        if (Build.VERSION.SDK_INT >= 16){
            try {
                WebSettings settings = webView.getSettings();
                Method m = WebSettings.class.getDeclaredMethod("setAllowUniversalAccessFromFileURLs", boolean.class);
                m.setAccessible(true);
                m.invoke(settings, true);
            }catch (Exception e){}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && BuildConfig.DEBUG) {
            try {
                Method m=WebView.class.getDeclaredMethod("setWebContentsDebuggingEnabled",boolean.class);
                m.setAccessible(true);
                m.invoke(webView,true);
                m=WebSettings.class.getDeclaredMethod("setMediaPlaybackRequiresUserGesture",boolean.class);
                m.setAccessible(true);
                m.invoke(webView.getSettings(),false);
            } catch (Exception e) {
            }
        }
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        String databasePath = webView.getContext().getDir("databases",
                Context.MODE_PRIVATE).getPath();
        webView.getSettings().setDatabasePath(databasePath);
        Bundle b = getIntent().getExtras();
        URI uri=(URI)b.get(URL_PARAM);
        webView.loadUrl(uri.toString());
    }
}
