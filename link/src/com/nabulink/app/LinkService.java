package com.nabulink.app;

import android.app.*;
import android.content.Intent;
import android.net.nsd.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.telephony.TelephonyManager;
import android.widget.*;
import android.view.WindowManager;
import android.text.InputType;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

public class LinkService extends Service {
    private static final int PORT=37851, PROTOCOL=2;
    private final SecureRandom random=new SecureRandom();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private volatile ServerSocket listener;
    private volatile boolean running, destroyed;
    private volatile String token;
    private boolean watching;
    private SmsBridge sms;
    private CallBridge calls;
    private AudioRouteMonitor audio;
    public static volatile String remoteAudio="Unavailable";
    public static volatile String callInfo="{}",callResult="";
    public static volatile boolean callBusy;
    private String ringingEpoch="";
    public static volatile String smsState="";
    public static volatile String smsInbox="[]";
    public static volatile boolean smsAllowed;
    public static volatile boolean smsBusy;
    private volatile String pendingId="";
    private String notifiedId="";
    private volatile String connectedIp="";
    private final LinkedHashSet<String> discovered=new LinkedHashSet<>();
    private NsdManager.DiscoveryListener clientDiscovery;
    private final ArrayDeque<NsdServiceInfo> resolveQueue=new ArrayDeque<>();
    private boolean resolvingClient;
    private final HashSet<String> seenServices=new HashSet<>();
    private int failures;
    private volatile long pairUntil;
    public static volatile String verification="";
    private volatile CountDownLatch approval;
    private volatile boolean approved;
    private volatile Socket pairingSocket;
    private volatile NsdManager.RegistrationListener registration;
    private volatile boolean hostMode;
    private volatile boolean pairingClient;
    public static final String TYPE="_nabulink._tcp.";
    public static volatile String title="Disconnected", body="";
    public static volatile String hostInfo="";
    public static volatile boolean active;
    private static final String CHANNEL="nabu_link";
    private static final int NOTIFICATION=31;

    private byte[] randomBytes(int n){byte[] b=new byte[n];random.nextBytes(b);return b;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static byte[] key(String s){byte[] b=new byte[16];for(int i=0;i<16;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    private static byte[] aad(byte role, byte[] a, byte[] b){
        byte[] v=new byte[1+a.length+b.length];v[0]=role;System.arraycopy(a,0,v,1,a.length);System.arraycopy(b,0,v,1+a.length,b.length);return v;
    }
    private byte[] crypt(int mode,String secret,byte[] iv,byte[] context,byte[] input)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode,new SecretKeySpec(key(secret),"AES"),new GCMParameterSpec(128,iv));
        c.updateAAD(context);return c.doFinal(input);
    }
    private void send(DataOutputStream out,String secret,byte[] context,byte[] data)throws Exception{
        byte[] iv=randomBytes(12),enc=crypt(Cipher.ENCRYPT_MODE,secret,iv,context,data);
        out.write(iv);out.writeInt(enc.length);out.write(enc);out.flush();
    }
    private byte[] receive(DataInputStream in,String secret,byte[] context)throws Exception{
        byte[] iv=new byte[12];in.readFully(iv);int n=in.readInt();
        if(n<16||n>32768)throw new IOException("Invalid frame");
        byte[] enc=new byte[n];in.readFully(enc);return crypt(Cipher.DECRYPT_MODE,secret,iv,context,enc);
    }
    private void show(String s,String d){
        if(destroyed)return;
        title=s;body=d;
        if(active)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LinkService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nabu Link • "+title).setContentText(notificationSummary())
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,"Disconnect",stop).build()).build();
    }
    private String notificationSummary(){
        for(String line:body.split("\\n"))if(line.startsWith("Signal:"))return "Remote modem • "+line;
        return body.split("\\n",2)[0];
    }
    private void runOnUiThread(Runnable r){handler.post(r);}
    @Override public void onCreate(){
        super.onCreate();
        sms=new SmsBridge(this);calls=new CallBridge(this);audio=new AudioRouteMonitor(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("nabu_calls","Nabu Link calls",NotificationManager.IMPORTANCE_HIGH));
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("nabu_sms","Nabu Link messages",NotificationManager.IMPORTANCE_DEFAULT));
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Nabu Link connection",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null||"STOP".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        String action=i.getAction();
        if("AUDIO_REFRESH".equals(action)){if(hostMode)audio.ensureProxy();return START_NOT_STICKY;}
        if("CALL_CONTROL".equals(action)){sendCall(i.getStringExtra("op"),i.getStringExtra("number"),i.getStringExtra("epoch"));return START_NOT_STICKY;}
        if("SMS_SEND".equals(action)){sendSms(i.getStringExtra("to"),i.getStringExtra("text"));return START_NOT_STICKY;}
        if("APPROVE".equals(action)||"REJECT".equals(action)){
            CountDownLatch l=approval;if(l!=null){approved="APPROVE".equals(action);l.countDown();}return START_NOT_STICKY;
        }
        if("ALLOW".equals(action)&&hostMode&&running){pairUntil=SystemClock.elapsedRealtime()+60000;show("Phone host active","Pairing allowed for 60 seconds");return START_NOT_STICKY;}
        if("FORGET".equals(action)){cancelPair();Pairing.forget(this);stopSelf();return START_NOT_STICKY;}
        active=true;title="Starting";body="";startForeground(NOTIFICATION,notification());
        if("HOST".equals(action)){stopHost();startHost();}
        else if("PAIR".equals(action)){startPair(i.getStringExtra("ip"));}
        else if("CLIENT".equals(action)){String secret=Pairing.load(this,"client");if(secret==null)startPair(i.getStringExtra("ip"));else connect(i.getStringExtra("ip"),secret);}
        else if("AUTO".equals(action)){String secret=Pairing.load(this,"client");if(secret==null){show("Pair first","Find nearby phone to pair");stopSelf();}else connect(getSharedPreferences("link",MODE_PRIVATE).getString("last_ip",""),secret);}
        else stopSelf();
        return START_NOT_STICKY;
    }
    private void advertise(){
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceType(TYPE);info.setServiceName("Nabu Link "+android.os.Build.MODEL);info.setPort(PORT);
        NsdManager.RegistrationListener l=new NsdManager.RegistrationListener(){
            public void onServiceRegistered(NsdServiceInfo n){}
            public void onRegistrationFailed(NsdServiceInfo n,int e){if(registration==this)runOnUiThread(()->show("Phone host active","Discovery failed; restart host"));}
            public void onServiceUnregistered(NsdServiceInfo n){}
            public void onUnregistrationFailed(NsdServiceInfo n,int e){}
        };registration=l;
        try{getSystemService(NsdManager.class).registerService(info,NsdManager.PROTOCOL_DNS_SD,l);}catch(RuntimeException e){registration=null;}
    }
    private synchronized void unadvertise(){NsdManager.RegistrationListener l=registration;registration=null;if(l!=null)try{getSystemService(NsdManager.class).unregisterService(l);}catch(RuntimeException ignored){}}
    private boolean confirm(String code)throws Exception{
        CountDownLatch l=new CountDownLatch(1);approval=l;approved=false;verification=code;
        runOnUiThread(()->show("Compare pairing codes","Confirm in both applications"));
        try{return l.await(60,TimeUnit.SECONDS)&&approved&&!destroyed;}
        finally{if(approval==l){approval=null;verification="";}}
    }
    private void cancelPair(){CountDownLatch l=approval;if(l!=null){approved=false;l.countDown();}try{if(pairingSocket!=null)pairingSocket.close();}catch(IOException ignored){}verification="";}
    private void startPair(String ip){
        if(ip==null||pairingClient)return;
        stopHost();disconnect();pairingClient=true;final int g=generation.get();show("Pairing","Waiting for phone approval...");
        new Thread(()->{
            try(Socket socket=new Socket()){
                pairingSocket=socket;InetAddress target=InetAddress.getByName(ip);if(!target.isSiteLocalAddress())throw new IOException("Private LAN only");
                socket.connect(new InetSocketAddress(target,PORT),4000);socket.setSoTimeout(75000);
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());DataInputStream in=new DataInputStream(socket.getInputStream());
                out.writeInt(Pairing.MAGIC);out.flush();if(in.readByte()!=1)throw new IOException("Allow pairing on phone");
                String secret=Pairing.exchange(in,out,false,this::confirm);
                if(destroyed||generation.get()!=g)return;
                Pairing.save(this,"client",secret);
                runOnUiThread(()->{if(!destroyed&&generation.get()==g){pairingClient=false;connect(ip,secret);}});
            }catch(Exception e){runOnUiThread(()->{if(!destroyed&&generation.get()==g){pairingClient=false;show("Pairing failed","Allow pairing on phone and try again.");}});}
            finally{pairingSocket=null;}
        },"link-pair").start();
    }
    private String addresses()throws Exception{
        StringBuilder s=new StringBuilder();
        for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))
            for(InetAddress a:Collections.list(n.getInetAddresses()))
                if(n.isUp()&&a instanceof Inet4Address&&a.isSiteLocalAddress()&&!a.isLoopbackAddress())s.append(n.getName()).append(": ").append(a.getHostAddress()).append("\n");
        return s.toString();
    }
    private JSONObject snapshot()throws Exception{
        TelephonyManager tm=getSystemService(TelephonyManager.class);
        JSONObject j=new JSONObject();j.put("protocol",PROTOCOL);j.put("model",android.os.Build.MODEL);j.put("network",localNetwork());j.put("sms",sms.status());j.put("call",calls.status());j.put("audioRoute",audio.status());
        j.put("operator",tm==null?"":tm.getNetworkOperatorName());j.put("simState",tm==null?-1:tm.getSimState());
        j.put("voiceCapable",tm!=null&&tm.isVoiceCapable());j.put("smsCapable",tm!=null&&tm.isSmsCapable());
        try{android.telephony.SignalStrength sig=tm==null?null:tm.getSignalStrength();j.put("signalLevel",sig==null?-1:sig.getLevel());}catch(RuntimeException e){j.put("signalLevel",-1);}
        return j;
    }
    private synchronized void startHost(){
        if(running)return;disconnect();sms.start();audio.start();hostMode=true;pairUntil=0;token=hex(randomBytes(16));final String session=token;running=true;
        new Thread(()->{
            try(ServerSocket server=new ServerSocket()){
                synchronized(this){if(!running||!session.equals(token))return;server.setReuseAddress(true);server.bind(new InetSocketAddress(PORT));listener=server;}
                final String info="Phone discoverable • "+android.os.Build.MODEL;
                runOnUiThread(()->{if(running&&session.equals(token)){hostInfo=info;advertise();show("Phone host active","Waiting for paired tablet");}});
                while(running&&session.equals(token)){
                    try(Socket peer=server.accept()){
                        if(!peer.getInetAddress().isSiteLocalAddress())continue;
                        peer.setSoTimeout(3000);
                        DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                        int mode=in.readInt();
                        if(mode==Pairing.MAGIC){
                            if(SystemClock.elapsedRealtime()>pairUntil||pairUntil==0){out.writeByte(0);out.flush();continue;}
                            pairUntil=0;peer.setSoTimeout(75000);pairingSocket=peer;
                            try{
                                out.writeByte(1);out.flush();
                                String secret=Pairing.exchange(in,out,true,this::confirm);
                                if(!running||!session.equals(token))continue;
                                Pairing.save(this,"host",secret);
                                sms.clearMessages();
                                getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_enabled",false).putBoolean("calls_enabled",false).apply();
                                runOnUiThread(()->{if(running&&session.equals(token))show("Phone host active","Tablet paired");});
                            }catch(Exception e){runOnUiThread(()->{if(running&&session.equals(token))show("Phone host active","Pairing rejected or timed out");});}
                            finally{pairingSocket=null;}
                            continue;
                        }
                        if(mode!=PROTOCOL)continue;
                        String secret=Pairing.load(this,"host");if(secret==null)continue;
                        byte[] client=new byte[16],host=randomBytes(16);
                        in.readFully(client);out.write(host);out.flush();
                        byte[] req=receive(in,secret,aad((byte)1,client,host));
                        if(!running||!session.equals(token))continue;
                        JSONObject response;
                        if(req.length==1&&req[0]==1)response=snapshot();
                        else {JSONObject command=new JSONObject(new String(req,"UTF-8"));response=command.optString("op").startsWith("call_")?calls.execute(command):sms.execute(command);}
                        send(out,secret,aad((byte)2,client,host),response.toString().getBytes("UTF-8"));
                    }catch(Exception e){if(server.isClosed())break;}
                }
            }catch(Exception e){runOnUiThread(()->{if(session.equals(token))show("Host error",String.valueOf(e.getMessage()));});}
            finally{synchronized(this){if(session.equals(token)){running=false;listener=null;}}}
        },"link-host").start();
    }
    private synchronized void stopHost(){if(sms!=null)sms.stop();if(audio!=null)audio.stop();running=false;hostMode=false;pairUntil=0;cancelPair();unadvertise();token=null;hostInfo="";try{if(listener!=null)listener.close();}catch(IOException ignored){}listener=null;}
    private void disconnect(){remoteAudio="Unavailable";callInfo="{}";callResult="";callBusy=false;ringingEpoch="";try{getSystemService(NotificationManager.class).cancel(33);}catch(RuntimeException ignored){}smsAllowed=false;smsInbox="[]";smsBusy=false;smsState="";pendingId="";notifiedId="";stopClientDiscovery();connectedIp="";cancelPair();pairingClient=false;watching=false;generation.incrementAndGet();handler.removeCallbacksAndMessages(null);show("Disconnected","");}
    private void stopClientDiscovery(){
        NsdManager.DiscoveryListener l=clientDiscovery;clientDiscovery=null;resolveQueue.clear();seenServices.clear();discovered.clear();
        if(l!=null)try{getSystemService(NsdManager.class).stopServiceDiscovery(l);}catch(RuntimeException ignored){}
    }
    private void resolveClient(){
        if(resolvingClient||clientDiscovery==null||resolveQueue.isEmpty())return;
        NsdManager.DiscoveryListener current=clientDiscovery;
        NsdServiceInfo next=resolveQueue.remove();resolvingClient=true;
        try{getSystemService(NsdManager.class).resolveService(next,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo n,int e){handler.post(()->{resolvingClient=false;resolveClient();});}
            public void onServiceResolved(NsdServiceInfo n){handler.post(()->{
                resolvingClient=false;
                if(clientDiscovery==current&&n.getHost() instanceof Inet4Address&&n.getHost().isSiteLocalAddress()&&n.getPort()==PORT){
                    if(discovered.size()<8)discovered.add(n.getHost().getHostAddress());
                }resolveClient();
            });}
        });}catch(RuntimeException e){resolvingClient=false;resolveClient();}
    }
    private void discoverClient(int g){
        if(clientDiscovery!=null)return;
        NsdManager.DiscoveryListener l=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){}
            public void onDiscoveryStopped(String t){}
            public void onStartDiscoveryFailed(String t,int e){handler.post(()->{if(clientDiscovery==this)stopClientDiscovery();});}
            public void onStopDiscoveryFailed(String t,int e){}
            public void onServiceLost(NsdServiceInfo n){}
            public void onServiceFound(NsdServiceInfo n){handler.post(()->{if(clientDiscovery==this&&seenServices.size()<16&&seenServices.add(n.getServiceName())){resolveQueue.add(n);resolveClient();}});}
        };clientDiscovery=l;
        try{getSystemService(NsdManager.class).discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,l);}catch(RuntimeException e){stopClientDiscovery();}
        handler.postDelayed(()->{if(generation.get()==g&&clientDiscovery==l)stopClientDiscovery();},20000);
    }
    private String wifiGateway(){
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
                LinkProperties lp=cm.getLinkProperties(n);if(lp==null)continue;
                for(RouteInfo route:lp.getRoutes())if(route.isDefaultRoute()&&route.getGateway() instanceof Inet4Address&&route.getGateway().isSiteLocalAddress())return route.getGateway().getHostAddress();
            }
        }catch(RuntimeException ignored){}
        return "";
    }
    private String localNetwork(){
        try{ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
            if(caps==null)return "No default network";
            return (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Cellular":"Other")+
                (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?" / internet validated":" / internet not validated");
        }catch(RuntimeException e){return "Unavailable";}
    }
    private void connect(String ip,String secret){
        if(secret==null||!secret.matches("[0-9a-f]{32}")){stopSelf();return;}
        stopHost();disconnect();watching=true;failures=0;connectedIp=ip==null?"":ip;int g=generation.get();
        show("Connecting","Authenticating saved phone...");poll(secret,g);
    }
    private JSONObject requestStatus(String ip,String secret,int timeout)throws Exception{return exchange(ip,secret,timeout,new byte[]{1});}
    private JSONObject exchange(String ip,String secret,int timeout,byte[] request)throws Exception{
        if(!ip.matches("[0-9.]{7,15}"))throw new IOException("Invalid address");
        try(Socket s=new Socket()){
            InetAddress target=InetAddress.getByName(ip);if(!target.isSiteLocalAddress())throw new IOException("Private LAN only");
            s.connect(new InetSocketAddress(target,PORT),timeout);s.setSoTimeout(timeout);
            DataOutputStream out=new DataOutputStream(s.getOutputStream());DataInputStream in=new DataInputStream(s.getInputStream());
            byte[] client=randomBytes(16),host=new byte[16];
            out.writeInt(PROTOCOL);out.write(client);out.flush();in.readFully(host);
            send(out,secret,aad((byte)1,client,host),request);
            JSONObject j=new JSONObject(new String(receive(in,secret,aad((byte)2,client,host)),"UTF-8"));
            if(request.length==1&&j.getInt("protocol")!=PROTOCOL)throw new IOException("Protocol mismatch");
            return j;
        }
    }
    private void poll(String secret,int g){
        if(!watching||destroyed||generation.get()!=g)return;
        if(failures>0||connectedIp.isEmpty())discoverClient(g);
        LinkedHashSet<String> candidates=new LinkedHashSet<>();
        String gateway=wifiGateway();
        if(failures>0||connectedIp.isEmpty()){if(!gateway.isEmpty())candidates.add(gateway);candidates.addAll(discovered);}
        if(!connectedIp.isEmpty())candidates.add(connectedIp);
        new Thread(()->{
            String heading="Connection unavailable",description="Searching for saved phone...";String found="";JSONObject smsData=null,callData=null;String audioData="Unavailable";
            for(String ip:candidates){
                if(destroyed||generation.get()!=g)return;
                try{
                    JSONObject j=requestStatus(ip,secret,2500);
                    int signal=j.optInt("signalLevel",-1);
                    heading=j.optString("operator","");if(heading.isEmpty())heading="Operator unavailable";
                    description="Connected / encrypted"+(ip.equals(gateway)?" / Direct gateway":" / Local network")+"\nRemote modem: "+j.optString("model")+"\nSIM: "+(j.optInt("simState")==5?"READY":"State "+j.optInt("simState"))+
                        "\nSignal: "+(signal>=0&&signal<=4?signal+" / 4":"Unavailable")+"\nVoice: "+j.optBoolean("voiceCapable")+"\nSMS: "+j.optBoolean("smsCapable")+
                        "\nPhone network: "+j.optString("network","Unknown")+"\nTablet network: "+localNetwork()+"\nUpdated: "+java.text.DateFormat.getTimeInstance().format(new Date());
                    smsData=j.optJSONObject("sms");callData=j.optJSONObject("call");audioData=j.optString("audioRoute","Unavailable");found=ip;break;
                }catch(Exception ignored){}
            }
            final String a=heading,b=description,successIp=found;final JSONObject remoteSms=smsData,remoteCall=callData;final String route=audioData;
            runOnUiThread(()->{if(destroyed||!watching||generation.get()!=g)return;show(a,b);
                if(!successIp.isEmpty()){updateSms(remoteSms);updateCall(remoteCall);remoteAudio=route;connectedIp=successIp;failures=0;stopClientDiscovery();getSharedPreferences("link",MODE_PRIVATE).edit().putString("last_ip",successIp).apply();}
                else {failures++;smsAllowed=false;remoteAudio="Unavailable";updateCall(null);}
                handler.postDelayed(()->poll(secret,g),successIp.isEmpty()?3000:3000);
            });
        },"link-client").start();
    }
    private void updateCall(JSONObject data){
        callInfo=data==null?"{}":data.toString();
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(data==null||data.optInt("state",-1)!=1){nm.cancel(33);return;}
        String epoch=data.optString("epoch");
        if(epoch.isEmpty()||epoch.equals(ringingEpoch))return;
        ringingEpoch=epoch;
        PendingIntent open=PendingIntent.getActivity(this,24,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"nabu_calls").setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Nabu Link").setContentText("Incoming call on paired phone").setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build();
        try{nm.notify(33,n);}catch(RuntimeException ignored){}
    }
    private void sendCall(String op,String number,String epoch){
        if(!watching||callBusy||connectedIp.isEmpty()||op==null||epoch==null)return;
        try{
            JSONObject current=new JSONObject(callInfo);
            if(!epoch.equals(current.optString("epoch"))){callResult="Call state changed";return;}
            String permission="call_dial".equals(op)?"dial":"call_answer".equals(op)?"answer":"call_end".equals(op)?"end":"";
            if(permission.isEmpty()||!current.optBoolean(permission)){callResult="Action unavailable";return;}
        }catch(Exception e){return;}
        String secret=Pairing.load(this,"client");if(secret==null)return;
        final int g=generation.get();final String ip=connectedIp,id=UUID.randomUUID().toString();
        callBusy=true;callResult="Requesting...";
        new Thread(()->{
            String result="Unknown — check phone before retrying";
            try{
                JSONObject req=new JSONObject().put("op",op).put("id",id).put("epoch",epoch);
                if(number!=null)req.put("number",number);
                result=exchange(ip,secret,4000,req.toString().getBytes("UTF-8")).optString("state","unknown");
            }catch(Exception ignored){}
            final String response=result;
            runOnUiThread(()->{if(!destroyed&&generation.get()==g){callBusy=false;callResult=response;}});
        },"link-call").start();
    }
    private void updateSms(JSONObject data){
        smsAllowed=data!=null&&data.optBoolean("sendAllowed");
        org.json.JSONArray list=data==null?null:data.optJSONArray("messages");
        smsInbox=list==null?"[]":list.toString();
        if(list!=null&&list.length()>0){
            JSONObject newest=list.optJSONObject(0);String id=newest==null?"":newest.optString("id");
            if(!id.isEmpty()&&!id.equals(notifiedId)){
                notifiedId=id;
                PendingIntent open=PendingIntent.getActivity(this,23,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                Notification n=new Notification.Builder(this,"nabu_sms").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Nabu Link").setContentText("New remote SMS").setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_SECRET).build();
                try{getSystemService(NotificationManager.class).notify(32,n);}catch(RuntimeException ignored){}
            }
        }
    }
    private void sendSms(String to,String text){
        if(!watching||!smsAllowed||smsBusy||connectedIp.isEmpty())return;
        if(to==null||text==null||!to.matches("\\+?[0-9]{7,15}")||text.trim().isEmpty()||text.length()>320){smsState="Invalid number or message";return;}
        String secret=Pairing.load(this,"client");if(secret==null)return;
        final int g=generation.get();final String ip=connectedIp,id=UUID.randomUUID().toString();
        pendingId=id;smsBusy=true;smsState="Submitting SMS...";
        new Thread(()->{
            String state="unknown — check phone; do not resend blindly";
            try{
                JSONObject req=new JSONObject().put("op","send").put("id",id).put("to",to).put("text",text);
                state=exchange(ip,secret,4000,req.toString().getBytes("UTF-8")).optString("state","unknown");
                if("submitted".equals(state)){
                    for(int i=0;i<12&&!destroyed&&generation.get()==g;i++){
                        Thread.sleep(2500);
                        JSONObject q=new JSONObject().put("op","result").put("id",id);
                        String next=exchange(ip,secret,4000,q.toString().getBytes("UTF-8")).optString("state","unknown");
                        state=next;if(!"submitted".equals(next))break;
                    }
                }
            }catch(Exception e){state="unknown — check phone; do not resend blindly";}
            final String result=state;
            runOnUiThread(()->{if(!destroyed&&generation.get()==g&&id.equals(pendingId)){smsBusy=false;smsState=result;}});
        },"link-sms").start();
    }
    @Override public void onDestroy(){destroyed=true;active=false;disconnect();stopHost();title="Disconnected";body="";stopForeground(true);super.onDestroy();}
}
