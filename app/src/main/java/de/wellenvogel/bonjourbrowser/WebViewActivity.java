package de.wellenvogel.bonjourbrowser;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;

public class WebViewActivity extends AppCompatActivity {

    static final String URL_PARAM="url";
    static final String NAME_PARAM="name";
    static final String PREF_KEEP_ON="keepScreenOn";
    static final String PREF_TEXT_ZOOM="textZoom";


    private WebView webView;
    private String serviceName;
    private URI serviceUri;
    private ProgressDialog pd;
    private boolean clearHistory=false;
    private class MyWebViewClient extends WebViewClient {
        private String lastAuthHost="";
        private String lastAuthRealm="";
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;

        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (pd.isShowing()) pd.dismiss();
            if (clearHistory){
                view.clearHistory();
                clearHistory=false;
            }
        }
        @Override
        public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, final String host, final String realm) {
            String[] up = view.getHttpAuthUsernamePassword(host, realm);
            if ((up != null && up.length == 2 ) &&  ! (lastAuthHost.equals(host) && lastAuthRealm.equals(realm))) {
                handler.proceed(up[0], up[1]);
            }
            else {
                AlertDialog.Builder builder = new AlertDialog.Builder(WebViewActivity.this);
                builder.setTitle(R.string.authentication);
                builder.setMessage(host+" "+realm);
                LinearLayout layout=new LinearLayout(WebViewActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(20,0,20,0);
                // Set up the inputs
                final EditText username = new EditText(WebViewActivity.this);
                final EditText password = new EditText(WebViewActivity.this);
                username.setHint("Username");
                password.setHint("Password");
                // Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
                password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                username.setInputType(InputType.TYPE_CLASS_TEXT);
                layout.addView(username);
                layout.addView(password);
                builder.setView(layout);
                final WebView wv=view;

                // Set up the buttons
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        wv.setHttpAuthUsernamePassword(host,realm,username.getText().toString(),password.getText().toString());
                        handler.proceed(username.getText().toString(),password.getText().toString());
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                        dialog.dismiss();
                    }
                });
                builder.create().show();
                Log.e("", "Could not find username/password for domain: " + host + ", with realm = " + realm);
            }
            lastAuthRealm=realm;
            lastAuthHost=host;
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        webView=new WebView(this);
        setContentView(webView);
        SharedPreferences sharedPref =
                PreferenceManager.getDefaultSharedPreferences(this);
        Boolean keepOn=sharedPref.getBoolean(PREF_KEEP_ON,false);
        if (keepOn){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.setWebViewClient(new MyWebViewClient());
        webView.canZoomIn();
        webView.canZoomOut();
        webView.canGoBack();
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
        webView.getSettings().setTextZoom(sharedPref.getInt(PREF_TEXT_ZOOM,100));
        String databasePath = webView.getContext().getDir("databases",
                Context.MODE_PRIVATE).getPath();
        webView.getSettings().setDatabasePath(databasePath);
        Bundle b = getIntent().getExtras();
        serviceUri=(URI)b.get(URL_PARAM);
        serviceName=b.getString(NAME_PARAM);
        clearHistory=true;
        pd = ProgressDialog.show(this, "", getResources().getString(R.string.loading)+" "+serviceName, true);
        String url=serviceUri.toString();
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            webView.loadUrl("about:blank");
            super.onBackPressed();
        }
    }

}
