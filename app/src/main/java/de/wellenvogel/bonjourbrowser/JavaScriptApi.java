package de.wellenvogel.bonjourbrowser;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

public class JavaScriptApi {
    WebViewActivity activity;
    public JavaScriptApi(WebViewActivity activity){
        this.activity=activity;
    }
    @JavascriptInterface
    public boolean dimScreen(int percent){
        if (percent < 0 || percent > 100) return false;
        activity.setBrightness(percent);
        return true;
    }
}
