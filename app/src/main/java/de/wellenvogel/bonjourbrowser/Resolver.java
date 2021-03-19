package de.wellenvogel.bonjourbrowser;

import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import net.straylightlabs.hola.dns.ARecord;
import net.straylightlabs.hola.dns.Domain;
import net.straylightlabs.hola.dns.Message;
import net.straylightlabs.hola.dns.Record;
import net.straylightlabs.hola.dns.Response;
import net.straylightlabs.hola.dns.SrvRecord;
import net.straylightlabs.hola.sd.Query;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static net.straylightlabs.hola.sd.Query.MDNS_IP4_ADDRESS;

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
    static final String SUFFIX=MainActivity.SERVICE_TYPE+Domain.LOCAL.getName();
    MainActivity activity;
    private InetAddress mdnsGroupIPv4;
    DatagramSocket socket;
    HashMap<String,Host> hostAddresses=new HashMap<>();
    HashSet<SrvRecord> waitingServices=new HashSet<>();
    static class ServiceRequest{
        String name;
        long requestTime;
        int retries=0;
        ServiceRequest(String name){
            this.name=name;
            requestTime=System.currentTimeMillis();
        }
        boolean expired(long now){
            if ((requestTime+RETRIGGER_TIME) < now) return true;
            return false;
        }
    }
    final HashSet<ServiceRequest> openRequests=new HashSet<>();

    public Resolver(MainActivity activity) throws SocketException, UnknownHostException {
        socket=new DatagramSocket();
        mdnsGroupIPv4 = InetAddress.getByName(MDNS_IP4_ADDRESS);
        this.activity=activity;
    }

    public void checkRetrigger(){
        long now=System.currentTimeMillis();
        synchronized (openRequests){
            for (ServiceRequest r:openRequests){
                if (r.expired(now) && r.retries < MAX_RETRIGGER){
                    Log.i(LPRFX,"retrigger query for "+r.name);
                    resolveService(r.name,false);
                    r.requestTime=now;
                    r.retries++;
                }
            }
        }
    }

    public static Resolver createResolver(MainActivity activity) throws SocketException, UnknownHostException {
        Resolver r=new Resolver(activity);
        Thread thr=new Thread(r);
        thr.setDaemon(true);
        thr.start();
        return r;
    }
    private void sendResolved(SrvRecord srv, Host host){
        Target target=new Target();
        target.name=srv.getName().substring(0,srv.getName().length()-SUFFIX.length()-1);
        target.host=srv.getTarget();
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
            target.uri = new URI("http", null, host.address.getHostAddress(), srv.getPort(), null, null, null);
            android.os.Message targetMessage = activity.handler.obtainMessage(MainActivity.ADD_SERVICE_MSG, target);
            Log.i(LPRFX, "resolve success for " + target.name+" "+target.uri);
            targetMessage.sendToTarget();
        } catch (URISyntaxException e) {
            Log.e(LPRFX, e.getMessage());
        }
    }
    @Override
    public void run() {
        while (!socket.isClosed()){
            byte[] responseBuffer = new byte[Message.MAX_LENGTH];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            try {
                socket.receive(responsePacket);
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
            } catch (IOException e) {
                Log.e(LPRFX,"exception in receive",e);
            }
        }
        Log.i(LPRFX,"resolver thread finished");
    }

    private Question serviceQuestion(String name){
        return new Question(name+"."+SUFFIX, Question.QType.SRV, Question.QClass.IN);
    }
    public void resolveService(String name,boolean storeRequest){
        Question q= serviceQuestion(name);
        if (storeRequest){
            synchronized (openRequests){
                openRequests.add(new ServiceRequest(name));
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
        DatagramPacket packet = new DatagramPacket(buffer.array(), buffer.position(), mdnsGroupIPv4, Query.MDNS_PORT);
        packet.setAddress(mdnsGroupIPv4);
        socket.send(packet);
    }

    public void stop(){
        socket.close();
        waitingServices.clear();
        hostAddresses.clear();
        openRequests.clear();
    }
}
