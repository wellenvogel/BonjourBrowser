package de.wellenvogel.bonjourbrowser;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.HttpAuthHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;

public class WebViewActivity extends AppCompatActivity  {

    static final String URL_PARAM="url";
    static final String NAME_PARAM="name";
    static final String PREF_KEEP_ON="keepScreenOn";
    static final String PREF_HIDE_STATUS="hideStatus";
    static final String PREF_HIDE_NAVIGATION="hideNavigation";
    static final String PREF_ALLOW_DIM="allowDim";
    static final String PREF_TEXT_ZOOM="textZoom";
    private static final String ACTION_CANCEL ="de.wellenvogel.bonjourbrowser.cancel" ;
    private static final String INDEX_NAME="dlindex";
    private static final String LOGPRFX="BonjourBrowserWV";

    private WebView webView;
    private String serviceName;
    private URI serviceUri;
    private ProgressDialog pd;
    private boolean clearHistory=false;
    private JavaScriptApi jsApi;
    NotificationManager notificationManager;

    View dlProgress=null;
    TextView dlText=null;

    private Handler mHandler=new Handler();
    private void doSetBrightness(float newBrightness){
        Window w=getWindow();
        WindowManager.LayoutParams lp=w.getAttributes();
        lp.screenBrightness=newBrightness;
        w.setAttributes(lp);
    }

    private Handler screenBrightnessHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int percent=msg.what;
            float newBrightness;
            if (percent >= 100){
                newBrightness= WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            }
            else {
                newBrightness = (float) percent / 100;
                if (newBrightness < 0.01f) newBrightness = 0.01f;
                if (newBrightness > 1) newBrightness = 1;
            }
            doSetBrightness(newBrightness);
        }
    };
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

    public void setBrightness(int percent){
        Message msg=screenBrightnessHandler.obtainMessage(percent);
        screenBrightnessHandler.sendMessage(msg);
    }

    static class UploadRequest{
        ValueCallback<Uri[]> filePathCallback;
    }


    private UploadRequest uploadRequest=null;
    private DownloadHandler.Download downloadRequest=null;
    private static final int REQUEST_DOWNLOAD=1;
    private static final int REQUEST_UPLOAD=2;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_DOWNLOAD) {
            if (downloadRequest == null) return;
            if (resultCode != Activity.RESULT_OK || data == null){
                downloadRequest=null;
                return;
            }
            Uri returnUri = data.getData();
            try {
                OutputStream os=getContentResolver().openOutputStream(returnUri);
                downloadRequest.start(os,returnUri);
            } catch (Throwable e) {
                Toast.makeText(this,"unable to open "+returnUri,Toast.LENGTH_SHORT).show();
                downloadRequest=null;
            }

        }
        if (requestCode == REQUEST_UPLOAD){
            UploadRequest rq=uploadRequest;
            uploadRequest=null;
            if (rq == null) return;
            if (resultCode != Activity.RESULT_OK || data == null) {
                rq.filePathCallback.onReceiveValue(null);
                return;
            }
            rq.filePathCallback.onReceiveValue(new Uri[]{data.getData()});
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationManager =(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        setContentView(R.layout.activity_webview);
        jsApi=new JavaScriptApi(this);
        getSupportActionBar().hide();
        dlProgress=findViewById(R.id.dlIndicator);
        dlProgress.setOnClickListener(view -> {
            if (downloadRequest != null) downloadRequest.stop();
        });
        dlText=findViewById(R.id.dlInfo);
        webView=findViewById(R.id.webmain);
        webView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && pd.isShowing()){
                    webView.loadUrl("about:blank");
                    pd.hide();
                    finish();
                }
                return false;
            }
        });
        SharedPreferences sharedPref =
                PreferenceManager.getDefaultSharedPreferences(this);
        Boolean keepOn=sharedPref.getBoolean(PREF_KEEP_ON,false);
        if (keepOn){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        Boolean allowDim=sharedPref.getBoolean(PREF_ALLOW_DIM,false);
        if (allowDim) {
            webView.addJavascriptInterface(jsApi, "bonjourBrowser");
        }
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
        webView.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String userAgent, String
                    contentDisposition, String mimeType, long contentLength) {
                Log.i(LOGPRFX, "download request for " + url);
                if (downloadRequest != null && downloadRequest.isRunning()) return;
                try {
                    DownloadHandler.Download nextDownload = DownloadHandler.createHandler(WebViewActivity.this,
                            url, userAgent, contentDisposition, mimeType, contentLength);
                    nextDownload.progress = WebViewActivity.this.dlProgress;
                    nextDownload.dlText = WebViewActivity.this.dlText;
                    downloadRequest = nextDownload;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(mimeType);
                    intent.putExtra(Intent.EXTRA_TITLE, nextDownload.fileName);
                    startActivityForResult(intent, REQUEST_DOWNLOAD);
                } catch (Throwable t) {
                    Toast.makeText(WebViewActivity.this, "download error:" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                Intent i = null;
                if (uploadRequest != null) return false;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    i = fileChooserParams.createIntent();
                    UploadRequest rq=new UploadRequest();
                    rq.filePathCallback=filePathCallback;
                    uploadRequest=rq;
                    startActivityForResult(Intent.createChooser(i, "SelectFile"), REQUEST_UPLOAD);
                    return true;
                }
                return false;
            }
        });
        Bundle b = getIntent().getExtras();
        serviceUri=(URI)b.get(URL_PARAM);
        serviceName=b.getString(NAME_PARAM);
        clearHistory=true;
        pd = new ProgressDialog(this);
        pd.setMessage(getResources().getString(R.string.loading) + " " + serviceName);
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                webView.loadUrl("about:blank");
                pd.hide();
                finish();
            }
        });
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        String url=serviceUri.toString();
        webView.loadUrl(url);
        Log.i("WebView","url lading started: "+url);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        SharedPreferences sharedPref =
                PreferenceManager.getDefaultSharedPreferences(this);
        boolean hideStatus=sharedPref.getBoolean(PREF_HIDE_STATUS,false);
        boolean hideNavigation=sharedPref.getBoolean(PREF_HIDE_NAVIGATION,false);
        if (hideStatus || hideNavigation) {
            View decorView = getWindow().getDecorView();
            int flags=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if (hideStatus) flags+=View.SYSTEM_UI_FLAG_FULLSCREEN;
            if (hideNavigation) flags+=View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            decorView.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        if (downloadRequest != null) downloadRequest.stop();
        super.onDestroy();
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
