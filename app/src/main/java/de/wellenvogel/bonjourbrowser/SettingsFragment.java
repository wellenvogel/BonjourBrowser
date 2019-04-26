package de.wellenvogel.bonjourbrowser;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;
import android.widget.TextView;

import java.io.File;

/**
 * A simple {@link Fragment} subclass.
 */
public class SettingsFragment extends PreferenceFragmentCompat {


    public SettingsFragment() {
        // Required empty public constructor
    }
    private void deleteFile(File f){
        if (! f.exists()) return;
        if (f.isDirectory()){
            for (File cf:f.listFiles()){
                deleteFile(cf);
            }
            f.delete();
            return;
        }
        f.delete();
    }
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences,rootKey);
        Preference pref=findPreference("reset");
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                File base=getActivity().getCacheDir().getParentFile();
                String deletes[]=new String[]{"app_webview","cache","databases"};
                for (String ddir:deletes){
                    deleteFile(new File(base,ddir));
                }
                System.exit(0);
                return true;
            }
        });
    }


}
