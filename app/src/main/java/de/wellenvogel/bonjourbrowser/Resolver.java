package de.wellenvogel.bonjourbrowser;

import android.os.Build;
import android.util.Log;

import net.straylightlabs.hola.dns.ARecord;
import net.straylightlabs.hola.dns.Domain;
import net.straylightlabs.hola.dns.Message;
import net.straylightlabs.hola.dns.Record;
import net.straylightlabs.hola.dns.Response;
import net.straylightlabs.hola.dns.SrvRecord;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static net.straylightlabs.hola.sd.Query.MDNS_IP4_ADDRESS;
import static net.straylightlabs.hola.sd.Query.MDNS_PORT;

public class Resolver implements Runnable{
    static final long RETRIGGER_TIME=6000; //6s
    static final int MAX_RETRIGGER=5;
    static class Host{
        InetAddress address;
        String name;
        long ttl;
        long updated;
        public Host(String name, InetAddress address,long ttl){
            updated=System.currentTimeMillis()*1000;
            this.ttl=ttl;
            this.name=name;
            this.address=address;
        }
        public Host(ARecord record){
            this(record.getName(),record.getAddress(),record.getTTL());
        }
    }
    static final String LPRFX="InternalReceiver";
    MainActivity activity;
    private SocketAddress mdnsGroupIPv4;
    private NetworkInterface intf;
    DatagramChannel channel;
    HashMap<String,Host> hostAddresses=new HashMap<>();
    HashSet<SrvRecord> waitingServices=new HashSet<>();
    static class ServiceRequest{
        String name;
        ServiceDescription description;
        long requestTime;
        int retries=0;
        ServiceRequest(String name,ServiceDescription description){
            this.name=name;
            this.description=description;
            requestTime=System.currentTimeMillis();
        }
        boolean expired(long now){
            if ((requestTime+RETRIGGER_TIME) < now) return true;
            return false;
        }
    }
    final HashSet<ServiceRequest> openRequests=new HashSet<>();

    public Resolver(MainActivity activity,NetworkInterface intf) throws IOException {
        this.intf=intf;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intf != null) {
            channel =DatagramChannel.open(StandardProtocolFamily.INET);
            channel.setOption(StandardSocketOptions.IP_MULTICAST_IF,intf);
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress("0.0.0.0",MDNS_PORT));
            InetAddress group=Inet4Address.getByName(MDNS_IP4_ADDRESS);
            channel.join(group,intf);
        }
        else{
            channel =DatagramChannel.open();
            channel.socket().bind(new InetSocketAddress(Inet4Address.getByName("0.0.0.0"),0));
        }
        mdnsGroupIPv4 = new InetSocketAddress(InetAddress.getByName(MDNS_IP4_ADDRESS),MDNS_PORT);
        this.activity=activity;
    }

    public void checkRetrigger(){
        long now=System.currentTimeMillis();
        synchronized (openRequests){
            for (ServiceRequest r:openRequests){
                if (r.expired(now) && r.retries < MAX_RETRIGGER){
                    Log.i(LPRFX,"retrigger query for "+r.name);
                    resolveService(r.name,r.description,false);
                    r.requestTime=now;
                    r.retries++;
                }
            }
        }
    }

    public static Resolver createResolver(MainActivity activity,NetworkInterface intf) throws IOException {
        Resolver r=new Resolver(activity,intf);
        Thread thr=new Thread(r);
        thr.setDaemon(true);
        thr.start();
        return r;
    }
    private void sendResolved(SrvRecord srv, Host host){
        ServiceDescription description=null;
        for (ServiceDescription d:MainActivity.services){
            if (srv.getName().contains(d.service)){
                description=d;
                break;
            }
        }
        if (description == null){
            Log.i(LPRFX,"unable to match "+srv.getName()+" to a known service");
            return;
        }
        Target target=new Target();
        String SUFFIX=description.service+Domain.LOCAL.getName();
        target.name=srv.getName().substring(0,srv.getName().length()-SUFFIX.length()-1);
        target.host=srv.getTarget();
        target.intf=intf;
        synchronized (openRequests){
            ArrayList<ServiceRequest> finished=new ArrayList<>();
            for (ServiceRequest r:openRequests){
                if (r.name.equals(target.name)) finished.add(r);
            }
            for (ServiceRequest r: finished){
                openRequests.remove(r);
            }
        }
        try {
            target.uri = new URI(description.protocol, null, host.address.getHostAddress(), srv.getPort(), null, null, null);
            target.description=description;
            android.os.Message targetMessage = activity.handler.obtainMessage(MainActivity.ADD_SERVICE_MSG, target);
            Log.i(LPRFX, "resolve success for " + target.name+" "+target.uri);
            targetMessage.sendToTarget();
        } catch (Throwable e) {
            Log.e(LPRFX, e.getMessage());
        }
    }
    @Override
    public void run() {
        while (channel.isOpen()){
            ByteBuffer responseBuffer = ByteBuffer.allocate(Message.MAX_LENGTH);
            try {
                channel.receive(responseBuffer);
                responseBuffer.flip();
                if (responseBuffer.limit() == 0){
                    continue;
                }
                byte[] bytes = new byte[responseBuffer.limit()];
                responseBuffer.get(bytes, 0, responseBuffer.limit());
                DatagramPacket responsePacket=new DatagramPacket(bytes,bytes.length);
                Response resp=Response.createFrom(responsePacket);
                Log.i(LPRFX,"response: "+resp);
                boolean hasA=false;
                for (Record record : resp.getRecords()){
                    if (record instanceof ARecord){
                        hasA=true;
                        hostAddresses.put(record.getName(),new Host((ARecord)record));
                    }
                }
                if (hasA){
                    ArrayList<SrvRecord> resolved=new ArrayList<>();
                    for (SrvRecord record:waitingServices){
                        Host host=hostAddresses.get(record.getTarget());
                        if (host != null){
                            sendResolved(record,host);
                            resolved.add(record);
                        }
                    }
                    for (SrvRecord record:resolved){
                        waitingServices.remove(record);
                    }
                }
                for (Record record:resp.getRecords()){
                    if (record instanceof SrvRecord){
                        SrvRecord srv=(SrvRecord)record;
                        Host host=hostAddresses.get(srv.getTarget());
                        if (host != null){
                            sendResolved(srv,host);
                        }
                        else{
                            waitingServices.add(srv);
                            Question q=new Question(srv.getTarget(), net.straylightlabs.hola.dns.Question.QType.A, net.straylightlabs.hola.dns.Question.QClass.IN);
                            try{
                                sendQuestion(q);
                            }catch (Exception e){
                                Log.e(LPRFX,"exception when sending aquery",e);
                            }

                        }
                    }
                }
            } catch (Throwable e) {
                Log.e(LPRFX,"exception in receive",e);
            }
        }
        Log.i(LPRFX,"resolver thread finished");
    }

    private Question serviceQuestion(String name,String type){
        return new Question(name+"."+type+Domain.LOCAL.getName(), Question.QType.SRV, Question.QClass.IN);
    }
    public void resolveService(String name,ServiceDescription description,boolean storeRequest){
        Question q= serviceQuestion(name,description.service);
        if (storeRequest){
            synchronized (openRequests){
                openRequests.add(new ServiceRequest(name,description));
            }
        }
        Thread st=new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sendQuestion(q);
                } catch (IOException e) {
                    Log.e(LPRFX,"unable to send query",e);
                }
            }
        });
        st.setDaemon(true);
        st.start();
    }

    public void sendQuestion(Question question) throws IOException {
        ByteBuffer buffer = question.getBuffer();
        buffer.flip();
        channel.send(buffer,mdnsGroupIPv4);
    }

    public void stop() throws IOException {
        channel.close();
        waitingServices.clear();
        hostAddresses.clear();
        openRequests.clear();
    }
}
