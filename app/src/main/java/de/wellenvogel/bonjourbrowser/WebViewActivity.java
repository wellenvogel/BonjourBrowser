package de.wellenvogel.bonjourbrowser;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Instrumentation;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.HttpAuthHandler;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.security.Permission;
import java.util.HashMap;
import java.util.HashSet;

import static android.support.v4.content.PermissionChecker.PERMISSION_GRANTED;

public class WebViewActivity extends AppCompatActivity  {

    static final String URL_PARAM="url";
    static final String NAME_PARAM="name";
    static final String PREF_KEEP_ON="keepScreenOn";
    static final String PREF_HIDE_STATUS="hideStatus";
    static final String PREF_HIDE_NAVIGATION="hideNavigation";
    static final String PREF_ALLOW_DIM="allowDim";
    static final String PREF_TEXT_ZOOM="textZoom";


    private WebView webView;
    private String serviceName;
    private URI serviceUri;
    private ProgressDialog pd;
    private boolean clearHistory=false;
    private JavaScriptApi jsApi;
    private float currentBrigthness=1;
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
            currentBrigthness=newBrightness;
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
    static class DownloadRequest{
        String url;
        String userAgent;
        String contentDisposition;
        String mimeType;
        long contentLength;
        String cookies;
    }
    private DownloadRequest downloadRequest=null;
    private static final int REQUEST_DOWNLOAD=1;
    private void downloadFile(Uri contentUri,DownloadRequest rq) throws FileNotFoundException {
        ParcelFileDescriptor pfd = getContentResolver().
                openFileDescriptor(contentUri, "w");
        if (pfd == null){
            throw new FileNotFoundException("unable to open");
        }
        final FileOutputStream fileOutput =
                new FileOutputStream(pfd.getFileDescriptor());
        Toast.makeText(this,"downloading...",Toast.LENGTH_LONG).show();
        new AsyncTask<String, Void, String>() {
            String SDCard;

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected String doInBackground(String... params) {
                try {
                    URL url = new URL(rq.url);
                    HttpURLConnection urlConnection = null;
                    urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setRequestMethod("GET");
                    urlConnection.setRequestProperty("Cookie",rq.cookies);
                    urlConnection.setRequestProperty("User-Agent",rq.userAgent);
                    urlConnection.setDoOutput(true);
                    urlConnection.connect();
                    InputStream inputStream = null;
                    inputStream = urlConnection.getInputStream();
                    byte[] buffer = new byte[10240];
                    int count;
                    long total = 0;
                    while ((count = inputStream.read(buffer)) != -1) {
                        total += count;
                        fileOutput.write(buffer, 0, count);
                    }
                    fileOutput.flush();
                    fileOutput.close();
                    inputStream.close();
                } catch (Exception e){
                    return "error";
                }
                return "ok";
            }
            @Override
            protected void onPostExecute(final String result) {
                if (result.equals("error")){
                    Toast.makeText(WebViewActivity.this,"download error",Toast.LENGTH_LONG).show();
                }
                else{
                    Toast.makeText(WebViewActivity.this,"saved",Toast.LENGTH_SHORT).show();
                }
            }

        }.execute();
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_DOWNLOAD) return;
        DownloadRequest rq=downloadRequest;
        downloadRequest=null;
        if( resultCode != Activity.RESULT_OK) return;
        if (rq == null || data == null) return;
        Uri uri=data.getData();
        try {
            downloadFile(uri,rq);
        } catch (FileNotFoundException e) {
            Log.e("WebView","unable to download",e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        jsApi=new JavaScriptApi(this);
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
                if (downloadRequest != null) return;
                try {
                    DownloadRequest rq=new DownloadRequest();
                    rq.url=url;
                    rq.userAgent=userAgent;
                    rq.contentDisposition=contentDisposition;
                    rq.mimeType=mimeType;
                    rq.contentLength=contentLength;
                    rq.cookies=CookieManager.getInstance().getCookie(url);
                    downloadRequest=rq;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(mimeType);
                    intent.putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, contentDisposition, mimeType));
                    startActivityForResult(intent, REQUEST_DOWNLOAD);
                }catch (Throwable t){
                    downloadRequest=null;
                    Toast.makeText(getApplicationContext(), "no permission", Toast.LENGTH_LONG).show();
                }
            }
            /*
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
            }*/
        });
        Bundle b = getIntent().getExtras();
        serviceUri=(URI)b.get(URL_PARAM);
        serviceName=b.getString(NAME_PARAM);
        clearHistory=true;
        pd = ProgressDialog.show(this, "", getResources().getString(R.string.loading)+" "+serviceName, true);
        String url=serviceUri.toString();
        webView.loadUrl(url);
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
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            webView.loadUrl("about:blank");
            super.onBackPressed();
        }
    }

}
