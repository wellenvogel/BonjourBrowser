package de.wellenvogel.bonjourbrowser;


import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;

import java.io.File;

/**
 * A simple {@link Fragment} subclass.
 */
public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {


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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N){
            Preference p=findPreference(MainActivity.PREF_LOCK_NET);
            if (p != null) getPreferenceScreen().removePreference(p);
        }
    }


    @Override
    public void addPreferencesFromResource(int preferencesResId) {
        super.addPreferencesFromResource(preferencesResId);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
            Preference p=findPreference(MainActivity.PREF_LOCK_NET);
            boolean enable=sharedPreferences.getBoolean(MainActivity.PREF_INTERNAL_RESOLVER,false)
                    && sharedPreferences.getBoolean(MainActivity.PREF_INTERNAL,false);
            if (p != null) p.setEnabled(enable);
        }
    }
}
