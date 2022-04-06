package de.wellenvogel.bonjourbrowser;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
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
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    static class ResolveEntry{
        public NsdServiceInfo info;
        public ServiceDescription description;

        public ResolveEntry(NsdServiceInfo info, ServiceDescription description) {
            this.info = info;
            this.description = description;
        }
    }

    static final String CHANNEL_ID = "main";
    static final String PREF_INTERNAL_RESOLVER = "internalResolver";
    static final String PREF_LOCK_NET = "lockNet";
    static final String PREF_INTERNAL="internalBrowser";
    private ListView list;
    private ProgressBar spinner;
    private TargetAdapter adapter;
    private Button scanButton;
    private ArrayList<NsdManager.DiscoveryListener> discoveryListeners=new ArrayList<>();
    private NsdManager nsdManager;
    private static String PRFX="BonjourBrowser";
    public static ServiceDescription services[]=new ServiceDescription[]{
            new ServiceDescription("_http._tcp.","http",false),
            new ServiceDescription("_ssh._tcp.","ssh",true)
    };
    private boolean discoveryActive=false;
    Handler handler;
    private long startSequence=0;
    static final int ADD_SERVICE_MSG=1;
    private static final int REMOVE_SERVICE_MSG=2;
    private static final int TIMER_MSG=3;
    private static final long DISCOVERY_TIMER=1000;
    private static final long RESOLVE_TIMEOUT=30000; //restart resolve after this time anyway
    private ArrayList<ResolveEntry> resolveQueue=new ArrayList<>();
    private long lastResolveStart=0;
    private boolean resolveRunning=false;
    private long resolveSequence=0;
    private boolean useAndroidQuery=false;
    private HashSet<NetworkInterface> interfaces=new HashSet<NetworkInterface>();
    private HashSet<Resolver> internalResolvers=new HashSet<Resolver>();
    private HashSet<InetAddress> interfaceAddresses=new HashSet<>();
    private HashMap<NetworkInterface, Network> interfaceMappings=new HashMap<>();
    private ConnectivityManager connectivityManager;


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

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.setSound(null,null);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivityManager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        setTitle("BonjourBrowser "+ BuildConfig.VERSION_NAME);
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
        checkNetworkChanged();
        nsdManager = (NsdManager)getSystemService(Context.NSD_SERVICE);
        handler=new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case ADD_SERVICE_MSG:
                        addTarget((Target)msg.obj);
                        resolveNext();
                        break;
                    case REMOVE_SERVICE_MSG:
                        removeService((String)msg.obj);
                        break;
                    case TIMER_MSG:
                        Long seq=(Long)msg.obj;
                        if (seq != startSequence) return;
                        if (checkNetworkChanged()){
                            Toast.makeText(MainActivity.this,R.string.ifchange,Toast.LENGTH_LONG).show();
                            stopScan();
                            scan();
                        }
                        if (! useAndroidQuery){
                            for (Resolver r:internalResolvers) {
                                r.checkRetrigger();
                            }
                        }
                        startTimer();
                        resolveNext();
                        break;

                }
            }
        };
        spinner=findViewById(R.id.progressBar);
        spinner.setVisibility(View.INVISIBLE);
        createNotificationChannel();
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

    boolean checkNetworkChanged(){
        HashSet<InetAddress> newAddresses=new HashSet<>();
        HashSet<NetworkInterface> newInterfaces=new HashSet<NetworkInterface>();
        try {
            Enumeration<NetworkInterface> intfs = NetworkInterface.getNetworkInterfaces();
            while (intfs.hasMoreElements()) {
                NetworkInterface intf = intfs.nextElement();
                boolean hasIp4=false;
                for (InterfaceAddress a:intf.getInterfaceAddresses()){
                    if (a.getAddress() instanceof Inet4Address) {
                        hasIp4=true;
                        break;
                    }
                }
                if (!intf.supportsMulticast() || ! intf.isUp() || ! hasIp4) continue;
                newInterfaces.add(intf);
                Enumeration<InetAddress> ifaddresses = intf.getInetAddresses();
                while (ifaddresses.hasMoreElements()) {
                    InetAddress ifaddress = ifaddresses.nextElement();
                    newAddresses.add(ifaddress);
                }
            }
        } catch (SocketException e1) {
        }
        boolean rt=false;
        if (newAddresses.size() != this.interfaceAddresses.size()){
            rt=true;
        }
        else{
            for (InetAddress addr:newAddresses){
                if (! this.interfaceAddresses.contains(addr)){
                    rt=true;
                    break;
                }
            }
            if (! rt){
                for (InetAddress addr:this.interfaceAddresses){
                    if (! newAddresses.contains(addr)){
                        rt=true;
                        break;
                    }
                }
            }
        }
        if (rt){
            HashMap<NetworkInterface,Network> newMappings=new HashMap<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (Network n:connectivityManager.getAllNetworks()){
                    LinkProperties props=connectivityManager.getLinkProperties(n);
                    for (NetworkInterface intf:newInterfaces){
                        try {
                            if (intf.getName().equals(props.getInterfaceName())) {
                                newMappings.put(intf, n);
                            }
                        }catch (Throwable t){
                            Log.e(PRFX,"unable to compare network interface names",t);
                        }
                    }
                }
                this.interfaceMappings=newMappings;
            }
            this.interfaceAddresses=newAddresses;
            this.interfaces=newInterfaces;
        }
        return rt;
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
        boolean runInternal=sharedPref.getBoolean(PREF_INTERNAL,false) && ! target.description.alwaysExt;
        if (runInternal){
            Intent i = new Intent(this, WebViewActivity.class);
            i.putExtra(WebViewActivity.URL_PARAM, target.uri);
            i.putExtra(WebViewActivity.NAME_PARAM, target.name);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                sharedPref.getBoolean(PREF_LOCK_NET,false)) {
                if (target.intf != null) {
                    Network nw = interfaceMappings.get(target.intf);
                    if (nw != null) {
                        Log.i(PRFX,"binding to network "+target.intf.getDisplayName());
                        connectivityManager.bindProcessToNetwork(nw);
                    }
                }
            }
            startActivity(i);
            return;
        }
        else {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(target.uri.toString()));
            startActivity(browserIntent);
        }
    }

    private void scan(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.bindProcessToNetwork(null);
        }
        scanButton.setText(R.string.stop);
        spinner.setVisibility(View.VISIBLE);
        startSequence++;
        resolveSequence++;
        if (discoveryActive) {
            for (NsdManager.DiscoveryListener discoveryListener :discoveryListeners) {
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
            discoveryActive=false;
        }
        SharedPreferences sharedPref =
                PreferenceManager.getDefaultSharedPreferences(this);
        useAndroidQuery=!sharedPref.getBoolean(PREF_INTERNAL_RESOLVER,true);
        if (!useAndroidQuery){
            for (Resolver r : internalResolvers) {
                try {
                    r.stop();
                } catch (Exception e) {
                }
            }
            internalResolvers.clear();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
                    for (NetworkInterface intf:interfaces){
                        internalResolvers.add(Resolver.createResolver(this,intf));
                    }
                }
                else {
                    internalResolvers.add(Resolver.createResolver(this,null));
                }
            } catch (Exception e) {
                Log.e(PRFX,"unable to create resolver");
                Toast.makeText(this,"unable to create resolver",Toast.LENGTH_LONG).show();
            }
        }
        initializeDiscoveryListener();
        ArrayList<Target> items=new ArrayList<>();
        adapter.setItems(items);
        Log.i(PRFX,"start discovery");
        for (int i=0;i<services.length;i++) {
            nsdManager.discoverServices(
                    services[i].service, NsdManager.PROTOCOL_DNS_SD, discoveryListeners.get(i));
        }
        discoveryActive=true;
        startTimer();
        synchronized (this){
            resolveQueue.clear();
            resolveRunning=false;
        }
    }
    private void stopScan(){
        if (discoveryActive) {
            for (NsdManager.DiscoveryListener discoveryListener : discoveryListeners) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                }catch (Throwable t){
                    Log.e(PRFX,"unable to stop discovery:",t);
                }
            }
        }
        discoveryActive=false;
        scanButton.setText(R.string.scan);
        spinner.setVisibility(View.INVISIBLE);
        startSequence++; //stops timer
        synchronized (this){
            resolveQueue.clear();
        }
        if (!useAndroidQuery) {
            for (Resolver r : internalResolvers) {
                try {
                    r.stop();
                } catch (Exception e) {
                }
            }
            internalResolvers.clear();
        }
    }

    private void addTarget(Target target){
        if (target.uri == null) return;
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

    private void resolveService(final NsdServiceInfo service,ServiceDescription description) {
        if (useAndroidQuery) {
            resolveQueue.add(new ResolveEntry(service,description));
        }
        else{
            for (Resolver r:internalResolvers) {
                r.resolveService(service.getServiceName(), description,true);
            }
        }
    }

    private synchronized boolean resolveDone(long sequence){
        Log.i(PRFX,"resolve unlock s="+sequence+", rs="+resolveSequence);
        if (sequence != resolveSequence) return false;
        resolveRunning=false;
        return true;
    }

    static class AndroidResolver implements NsdManager.ResolveListener {
        private MainActivity activity;
        private long sequence;
        private ServiceDescription description;

        AndroidResolver(MainActivity activity, ServiceDescription description, long sequence) {
            this.activity = activity;
            this.sequence = sequence;
            this.description=description;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
            activity.resolveDone(sequence);
            Log.e(PRFX, "resolve failed for " + nsdServiceInfo.getServiceName() + ": " + Integer.toString(i));
        }

        @Override
        public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
            Target target = new Target();
            target.name = nsdServiceInfo.getServiceName();
            target.host = nsdServiceInfo.getHost().getHostName();
            try {
                target.uri = new URI(description.protocol, null, nsdServiceInfo.getHost().getHostAddress(), nsdServiceInfo.getPort(), null, null, null);
                target.description = description;
            } catch (URISyntaxException e) {
                Log.e(PRFX, e.getMessage());
            }
            Message targetMessage = activity.handler.obtainMessage(ADD_SERVICE_MSG, target);
            boolean rt = activity.resolveDone(sequence);
            Log.i(PRFX, "resolve success for " + nsdServiceInfo.getServiceName() + " with sequence " + sequence + " unlocked=" + rt);
            targetMessage.sendToTarget();
        }
    };



    private synchronized void resolveNext(){
        if (resolveQueue.size()<1 ) return;
        long now=System.currentTimeMillis();
        if (resolveRunning){
            if ((lastResolveStart+RESOLVE_TIMEOUT) < now){
                resolveRunning=false;
                Log.e(PRFX,"resolve timed out, trying next");
            }
            else return;
        }
        resolveSequence++;
        ResolveEntry entry=resolveQueue.remove(0);
        Log.i(PRFX,"start resolve for "+entry.info.getServiceName()+ " with sequence "+resolveSequence);
        resolveRunning=true;
        lastResolveStart=now;
        nsdManager.resolveService(entry.info, new AndroidResolver(this, entry.description,resolveSequence));

    }
    public void initializeDiscoveryListener() {

        // Instantiate a new DiscoveryListener
        discoveryListeners.clear();
        for (ServiceDescription d : this.services) {
            final String key=d.service;
            final ServiceDescription description=d;
            discoveryListeners.add(new NsdManager.DiscoveryListener() {

                // Called as soon as service discovery begins.
                @Override
                public void onDiscoveryStarted(String regType) {
                    Log.d(PRFX, key+" Service discovery started");
                }

                @Override
                public void onServiceFound(NsdServiceInfo service) {
                    // A service was found! Do something with it.
                    Log.d(PRFX, key+" Service discovery success" + service);
                    if (!service.getServiceType().equals(description.service)) {
                        return;
                    }
                    resolveService(service,description);

                }

                @Override
                public void onServiceLost(NsdServiceInfo service) {
                    // When the network service is no longer available.
                    // Internal bookkeeping code goes here.
                    Log.e(PRFX, key+" service lost: " + service);
                    Message targetMessage = handler.obtainMessage(REMOVE_SERVICE_MSG, service.getServiceName());
                    targetMessage.sendToTarget();
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    Log.i(PRFX, key+" Discovery stopped: " + serviceType);
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.e(PRFX, key+" Discovery failed: Error code:" + errorCode);
                    stopScan();
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    Log.e(PRFX, "Discovery failed: Error code:" + errorCode);
                    nsdManager.stopServiceDiscovery(this);
                }
            });
        }
    }

}
