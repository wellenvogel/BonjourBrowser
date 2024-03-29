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
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
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


    private WebView webView;
    private String serviceName;
    private URI serviceUri;
    private ProgressDialog pd;
    private boolean clearHistory=false;
    private JavaScriptApi jsApi;
    private int downloadIndex=0;
    NotificationManager notificationManager;

    private Handler mHandler=new Handler();
    private void doSetBrightness(float newBrightness){
        Window w=getWindow();
        WindowManager.LayoutParams lp=w.getAttributes();
        lp.screenBrightness=newBrightness;
        w.setAttributes(lp);
    }
    static class MyReceiver extends BroadcastReceiver {
        WebViewActivity activity;
        MyReceiver(WebViewActivity a){
            activity=a;
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            int index=intent.getIntExtra(INDEX_NAME,-1);
            DownloadHandler handler=activity.runningDownloads.get(index);
            if (handler != null){
                try{
                    handler.stop();
                }catch (Throwable t){}
            }
        }
    }
    private MyReceiver receiver=new MyReceiver(this);
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
    static class DownloadRequest{
        String url;
        String userAgent;
        String contentDisposition;
        String mimeType;
        long contentLength;
        String cookies;
        String fileName;
    }
    static class UploadRequest{
        ValueCallback<Uri[]> filePathCallback;
    }
    private final HashMap<Integer,DownloadHandler> runningDownloads=new HashMap<>();
    synchronized private int nextDownloadIndex(){
        downloadIndex++;
        return downloadIndex;
    }
    class DownloadHandler implements Runnable{
        Uri uri;
        DownloadRequest rq;
        int index;
        boolean shouldStop;
        Thread thread;
        NotificationCompat.Builder notificationBuilder;
        DownloadHandler(Uri uri,DownloadRequest rq,int index){
            this.uri=uri;
            this.rq=rq;
            this.index=index;
            shouldStop=false;
        }
        void start(){
            thread=new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }
        void stop(){
            shouldStop=true;
            try{
                thread.interrupt();
            }catch(Throwable t){}
        }
        private void toast(String msg){
            WebViewActivity.this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(WebViewActivity.this,msg,Toast.LENGTH_LONG).show();
                }
            });
        }
        private void showDownloadNotification(String name){
            Intent action1Intent = new Intent()
                    .setAction(ACTION_CANCEL)
                    .putExtra(INDEX_NAME,index);
            PendingIntent action1PendingIntent = PendingIntent.getBroadcast(WebViewActivity.this, index, action1Intent, PendingIntent.FLAG_IMMUTABLE);
            notificationBuilder =
                    new NotificationCompat.Builder(WebViewActivity.this,MainActivity.CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_icon_bw)
                            .setContentTitle("download")
                            .setContentText(name)
                            .setProgress(100,0,false)
                            .setAutoCancel(false)
                            .addAction(new NotificationCompat.Action(R.drawable.ic_icon_bw,
                                    "Cancel", action1PendingIntent));
            notificationManager.notify(index, notificationBuilder.build());
        }
        private void updateDownloadNotification(int percent){
            NotificationCompat.Builder builder=notificationBuilder;
            if (builder == null) return;
            builder.setProgress(100,percent,false);
            notificationManager.notify(index, builder.build());
        }
        @Override
        public void run() {
            long total = 0;
            ParcelFileDescriptor pfd=null;
            FileOutputStream fileOutput=null;
            try {
                pfd = getContentResolver().
                        openFileDescriptor(uri, "w");
                if (pfd == null) {
                    throw new FileNotFoundException("unable to open");
                }
                fileOutput = new FileOutputStream(pfd.getFileDescriptor());
                showDownloadNotification(rq.fileName);
                toast("downloading...");
                URL url = new URL(rq.url);
                HttpURLConnection urlConnection = null;
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setRequestProperty("Cookie", rq.cookies);
                urlConnection.setRequestProperty("User-Agent", rq.userAgent);
                urlConnection.connect();
                InputStream downloadStream = null;
                downloadStream = urlConnection.getInputStream();
                byte[] buffer = new byte[10240];
                int count;
                while ((count = downloadStream.read(buffer)) != -1) {
                    total += count;
                    fileOutput.write(buffer, 0, count);
                    if (shouldStop) {

                        downloadStream.close();
                        throw new Exception("aborted");
                    }
                    if (rq.contentLength != 0) {
                        long percent = (total * 100) / rq.contentLength;
                        updateDownloadNotification((int) percent);
                    }
                }
                fileOutput.flush();
            } catch(Throwable t){
                try {
                    if (fileOutput != null) fileOutput.close();
                    if (pfd != null) pfd.close();
                }catch(Throwable x){}
                try {
                    DocumentFile.fromSingleUri(WebViewActivity.this, uri).delete();
                }catch (Throwable x){}
                WebViewActivity.this.runningDownloads.remove(index);
                if (shouldStop){
                    toast(rq.fileName+" download aborted");
                }
                else {
                    toast(rq.fileName + " download error " + t.getMessage());
                }
                notificationManager.cancel(index);    notificationManager.cancel(index);
                return;
            }
            try {
                if (fileOutput != null) fileOutput.close();
                if (pfd != null) pfd.close();
            }catch(Throwable x){}
            WebViewActivity.this.runningDownloads.remove(index);
            toast("saved "+rq.fileName);
            notificationManager.cancel(index);
        }
    }

    private UploadRequest uploadRequest=null;
    private DownloadRequest downloadRequest=null;
    private static final int REQUEST_DOWNLOAD=1;
    private static final int REQUEST_UPLOAD=2;
    private void downloadFile(Uri contentUri,DownloadRequest rq) throws FileNotFoundException {
        int index=nextDownloadIndex();
        DownloadHandler handler=new DownloadHandler(contentUri,rq,index);
        runningDownloads.put(index,handler);
        handler.start();
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_DOWNLOAD) {
            DownloadRequest rq = downloadRequest;
            downloadRequest = null;
            if (resultCode != Activity.RESULT_OK) return;
            if (rq == null || data == null) return;
            Uri uri = data.getData();
            try {
                downloadFile(uri,rq);
            } catch (FileNotFoundException e) {
                Log.e("WebView", "unable to download", e);
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
        registerReceiver(receiver,new IntentFilter(ACTION_CANCEL));
        notificationManager =(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        jsApi=new JavaScriptApi(this);
        getSupportActionBar().hide();
        webView=new WebView(this){
            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && pd.isShowing()){
                    webView.loadUrl("about:blank");
                    pd.hide();
                    finish();
                }
                return super.onKeyDown(keyCode, event);
            }
        };
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
                if (downloadRequest != null ) {
                    Toast.makeText(WebViewActivity.this, getText(R.string.download_running),Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    if (contentDisposition.indexOf("filename*=") >= 0){
                        contentDisposition=contentDisposition.replaceAll(".*filename\\*=utf-8''","");
                        contentDisposition= URLDecoder.decode(contentDisposition,"utf-8");
                        contentDisposition="attachment; filename="+contentDisposition;
                    }
                    DownloadRequest rq=new DownloadRequest();
                    rq.url=url;
                    rq.userAgent=userAgent;
                    rq.contentDisposition=contentDisposition;
                    rq.mimeType=mimeType;
                    rq.contentLength=contentLength;
                    rq.cookies=CookieManager.getInstance().getCookie(url);
                    rq.fileName=URLUtil.guessFileName(url, contentDisposition, mimeType);
                    downloadRequest=rq;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION+Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    intent.setType(mimeType);
                    intent.putExtra(Intent.EXTRA_TITLE, rq.fileName);
                    startActivityForResult(intent, REQUEST_DOWNLOAD);
                }catch (Throwable t){
                    downloadRequest=null;
                    Toast.makeText(getApplicationContext(), "no permission", Toast.LENGTH_LONG).show();
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
        unregisterReceiver(receiver);
        webView.destroy();
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
