package bonjourbrowser.de.wellenvogel.bonjourbrowser;

import android.content.Context;
import android.content.Intent;
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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView list;
    private TargetAdapter adapter;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager nsdManager;
    private static String PRFX="BonjourBrowser";
    private static final String SERVICE_TYPE="_http._tcp.";
    private boolean discoveryActive=false;
    private Handler handler;


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
        Button scanButton=(Button)findViewById(R.id.scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scan();
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
                addTarget((Target)msg.obj);
            }
        };
    }

    private void handleItemClick(int position){
        Target target=adapter.getItem(position);
        if (target == null) return;
        Intent browserIntent = new Intent(Intent.ACTION_VIEW,Uri.parse(target.uri.toString()));
        startActivity(browserIntent);
    }

    private void scan(){
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
    }

    private void addTarget(Target target){
        int num=adapter.getCount();
        for (int i=0;i<num;i++){
            if (adapter.getItem(i).uri.toString().equals(target.uri.toString())) return;
        }
        adapter.add(target);
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
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
                        Log.e(PRFX,"resolve failed for "+nsdServiceInfo.getServiceName());
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                        Target target=new Target();
                        target.name=nsdServiceInfo.getServiceName();
                        target.host=nsdServiceInfo.getHost().getHostName();
                        try {
                            target.uri=new URI("http",null,nsdServiceInfo.getHost().getHostAddress(),nsdServiceInfo.getPort(),null,null,null);
                            Message targetMessage=handler.obtainMessage(1,target);
                            targetMessage.sendToTarget();
                        } catch (URISyntaxException e) {
                            Log.e(PRFX,e.getMessage());
                        }
                    }
                });

                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
                        Log.e(PRFX,"resolve failed for "+nsdServiceInfo.getServiceName());
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                        Target target=new Target();
                        target.name=nsdServiceInfo.getServiceName();
                        target.host=nsdServiceInfo.getHost().getHostName();
                        try {
                            target.uri=new URI("http",null,nsdServiceInfo.getHost().getHostAddress(),nsdServiceInfo.getPort(),null,null,null);
                            Message targetMessage=handler.obtainMessage(1,target);
                            targetMessage.sendToTarget();
                        } catch (URISyntaxException e) {
                            Log.e(PRFX,e.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                Log.e(PRFX, "service lost: " + service);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(PRFX, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(PRFX, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(PRFX, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }
        };
    }

}
