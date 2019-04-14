package de.wellenvogel.bonjourbrowser;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView list;
    private ProgressBar spinner;
    private TargetAdapter adapter;
    private Button scanButton;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager nsdManager;
    private static String PRFX="BonjourBrowser";
    private static final String SERVICE_TYPE="_http._tcp.";
    private boolean discoveryActive=false;
    private Handler handler;
    private long startSequence=0;
    private static final int ADD_SERVICE_MSG=1;
    private static final int REMOVE_SERVICE_MSG=2;
    private static final int TIMER_MSG=3;
    private static final long DISCOVERY_TIMER=1000;
    private static final long RESOLVE_TIMEOUT=10000; //restart resolve after this time anyway
    private static final String PREF_INTERNAL="internalBrowser";
    private ArrayList<NsdServiceInfo> resolveQueue=new ArrayList<>();
    private long lastResolveStart=0;
    private boolean resolveRunning=false;


    static class Target{
        public String name;
        public String host;
        public URI uri;
    }
    static class TargetAdapter extends ArrayAdapter<Target>{

        public TargetAdapter(@NonNull Context context) {
            super(context,-1);
        }

        public void setItems(List<Target> items){
            super.clear();
            super.addAll(items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) getContext()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View rowView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            TextView title=rowView.findViewById(android.R.id.text1);
            Target item=getItem(position);
            title.setText(item.name);
            TextView sub=rowView.findViewById(android.R.id.text2);
            sub.setText(item.uri.toString());
            return rowView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        scanButton=(Button)findViewById(R.id.scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (discoveryActive){
                    stopScan();
                }
                else {
                    scan();
                }
            }
        });
        list=(ListView)findViewById(R.id.list);
        adapter=new TargetAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                Log.i(PRFX,"Clicked: "+Integer.toString(i));
                handleItemClick(i);
            }
        });
        nsdManager = (NsdManager)getSystemService(Context.NSD_SERVICE);
        handler=new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case ADD_SERVICE_MSG:
                        addTarget((Target)msg.obj);
                        break;
                    case REMOVE_SERVICE_MSG:
                        removeService((String)msg.obj);
                        break;
                    case TIMER_MSG:
                        Long seq=(Long)msg.obj;
                        if (seq != startSequence) return;
                        startTimer();
                        resolveNext();
                        break;

                }
            }
        };
        spinner=findViewById(R.id.progressBar);
        spinner.setVisibility(View.INVISIBLE);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
    }

    @Override
    public void onStop(){
        stopScan();
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater=getMenuInflater();
        inflater.inflate(R.menu.main_menu,menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.settings){
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        scan();
    }

    private void startTimer(){
        Message nextTimer=handler.obtainMessage(TIMER_MSG,startSequence);
        handler.sendMessageDelayed(nextTimer,DISCOVERY_TIMER);
    }

    private void handleItemClick(int position){
        Target target=adapter.getItem(position);
        if (target == null) return;
        SharedPreferences sharedPref =
                PreferenceManager.getDefaultSharedPreferences(this);
        Boolean runInternal=sharedPref.getBoolean(PREF_INTERNAL,false);
        if (runInternal){
            Intent i = new Intent(this, WebViewActivity.class);
            i.putExtra(WebViewActivity.URL_PARAM, target.uri);
            i.putExtra(WebViewActivity.NAME_PARAM, target.name);
            startActivity(i);
            return;
        }
        else {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(target.uri.toString()));
            startActivity(browserIntent);
        }
    }

    private void scan(){
        scanButton.setText(R.string.stop);
        spinner.setVisibility(View.VISIBLE);
        startSequence++;
        if (discoveryActive) {
            nsdManager.stopServiceDiscovery(discoveryListener);
            discoveryActive=false;
        }
        initializeDiscoveryListener();
        ArrayList<Target> items=new ArrayList<>();
        adapter.setItems(items);
        Log.i(PRFX,"start discovery");
        nsdManager.discoverServices(
                SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        discoveryActive=true;
        startTimer();
    }
    private void stopScan(){
        if (discoveryActive) {
            nsdManager.stopServiceDiscovery(discoveryListener);
        }
        discoveryActive=false;
        scanButton.setText(R.string.scan);
        spinner.setVisibility(View.INVISIBLE);
        startSequence++; //stops timer
    }

    private void addTarget(Target target){
        int num=adapter.getCount();
        for (int i=0;i<num;i++){
            if (adapter.getItem(i).uri.toString().equals(target.uri.toString())) return;
        }
        adapter.add(target);
    }
    private void removeService(String name){
        ArrayList<Target> removeItems=new ArrayList<>();
        int num=adapter.getCount();
        for (int i=0;i<num;i++){
            Target item=adapter.getItem(i);
            if (item.name.equals(name)){
                removeItems.add(item);
            }
        }
        for (Target item:removeItems){
            adapter.remove(item);
        }
    }

    private void resolveService(final NsdServiceInfo service) {
        resolveQueue.add(service);
    }

    private synchronized void resolveDone(){
        resolveRunning=false;
    }

    private synchronized void resolveNext(){
        if (resolveQueue.size()<1 ) return;
        long now=System.currentTimeMillis();
        if (resolveRunning){
            if ((lastResolveStart+RESOLVE_TIMEOUT) < now){
                resolveRunning=false;
            }
            else return;
        }
        NsdServiceInfo service=resolveQueue.remove(0);
        resolveRunning=true;
        lastResolveStart=now;
        nsdManager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
                resolveDone();
                Log.e(PRFX,"resolve failed for "+nsdServiceInfo.getServiceName()+": "+Integer.toString(i));
            }

            @Override
            public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                resolveDone();
                Target target=new Target();
                target.name=nsdServiceInfo.getServiceName();
                target.host=nsdServiceInfo.getHost().getHostName();
                try {
                    target.uri=new URI("http",null,nsdServiceInfo.getHost().getHostAddress(),nsdServiceInfo.getPort(),null,null,null);
                    Message targetMessage=handler.obtainMessage(ADD_SERVICE_MSG,target);
                    targetMessage.sendToTarget();
                } catch (URISyntaxException e) {
                    Log.e(PRFX,e.getMessage());
                }
            }
        });
    }
    public void initializeDiscoveryListener() {

        // Instantiate a new DiscoveryListener
        discoveryListener = new NsdManager.DiscoveryListener() {

            // Called as soon as service discovery begins.
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(PRFX, "Service discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                // A service was found! Do something with it.
                Log.d(PRFX, "Service discovery success" + service);
                if (!service.getServiceType().equals(SERVICE_TYPE)) {
                    return;
                }
                resolveService(service);

            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                Log.e(PRFX, "service lost: " + service);
                Message targetMessage=handler.obtainMessage(REMOVE_SERVICE_MSG,service.getServiceName());
                targetMessage.sendToTarget();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(PRFX, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(PRFX, "Discovery failed: Error code:" + errorCode);
                stopScan();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(PRFX, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }
        };
    }

}
