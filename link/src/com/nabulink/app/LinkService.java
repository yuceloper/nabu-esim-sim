package com.nabulink.app;

import android.app.*;
import android.content.Intent;
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
        if(n<16||n>8192)throw new IOException("Invalid frame");
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
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Nabu Link • "+title).setContentText(body.split("\\n",2)[0])
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null,"Disconnect",stop).build()).build();
    }
    private void runOnUiThread(Runnable r){handler.post(r);}
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Nabu Link connection",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null||"STOP".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        active=true;title="Starting";body="";startForeground(NOTIFICATION,notification());
        if("HOST".equals(i.getAction())){stopHost();startHost();}
        else if("CLIENT".equals(i.getAction()))connect(i.getStringExtra("ip"),i.getStringExtra("secret"));
        else stopSelf();
        return START_NOT_STICKY;
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
        JSONObject j=new JSONObject();j.put("protocol",PROTOCOL);j.put("model",android.os.Build.MODEL);
        j.put("operator",tm==null?"":tm.getNetworkOperatorName());j.put("simState",tm==null?-1:tm.getSimState());
        j.put("voiceCapable",tm!=null&&tm.isVoiceCapable());j.put("smsCapable",tm!=null&&tm.isSmsCapable());
        try{android.telephony.SignalStrength sig=tm==null?null:tm.getSignalStrength();j.put("signalLevel",sig==null?-1:sig.getLevel());}catch(RuntimeException e){j.put("signalLevel",-1);}
        return j;
    }
    private synchronized void startHost(){
        if(running)return;disconnect();token=hex(randomBytes(16));final String session=token;running=true;
        new Thread(()->{
            try(ServerSocket server=new ServerSocket()){
                synchronized(this){if(!running||!session.equals(token))return;server.setReuseAddress(true);server.bind(new InetSocketAddress(PORT));listener=server;}
                final String info=addresses()+"Session code: "+session;
                runOnUiThread(()->{if(running&&session.equals(token)){hostInfo=info;show("Phone host active","Waiting for paired tablet");}});
                while(running&&session.equals(token)){
                    try(Socket peer=server.accept()){
                        if(!peer.getInetAddress().isSiteLocalAddress())continue;
                        peer.setSoTimeout(3000);
                        DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                        byte[] client=new byte[16],host=randomBytes(16);
                        if(in.readInt()!=PROTOCOL)continue;in.readFully(client);out.write(host);out.flush();
                        byte[] req=receive(in,session,aad((byte)1,client,host));
                        if(req.length!=1||req[0]!=1)continue;
                        if(!running||!session.equals(token))continue;
                        send(out,session,aad((byte)2,client,host),snapshot().toString().getBytes("UTF-8"));
                    }catch(Exception e){if(server.isClosed())break;}
                }
            }catch(Exception e){runOnUiThread(()->{if(session.equals(token))show("Host error",String.valueOf(e.getMessage()));});}
            finally{synchronized(this){if(session.equals(token)){running=false;listener=null;}}}
        },"link-host").start();
    }
    private synchronized void stopHost(){running=false;token=null;hostInfo="";try{if(listener!=null)listener.close();}catch(IOException ignored){}listener=null;}
    private void disconnect(){watching=false;generation.incrementAndGet();handler.removeCallbacksAndMessages(null);show("Disconnected","");}
    private void connect(String ip,String secret){
        if(ip==null||secret==null){stopSelf();return;}
        if(!ip.matches("[0-9.]{7,15}")||!secret.matches("[0-9a-f]{32}")){stopSelf();return;}
        stopHost();disconnect();watching=true;int g=generation.get();show("Connecting","Authenticating phone...");
        poll(ip,secret,g);
    }
    private void poll(String ip,String secret,int g){
        if(!watching||destroyed||generation.get()!=g)return;
        new Thread(()->{
            String title,body;boolean ok=false;
            try(Socket s=new Socket()){
                InetAddress target=InetAddress.getByName(ip);if(!target.isSiteLocalAddress())throw new IOException("Private LAN only");
                s.connect(new InetSocketAddress(target,PORT),3500);s.setSoTimeout(3500);
                DataOutputStream out=new DataOutputStream(s.getOutputStream());DataInputStream in=new DataInputStream(s.getInputStream());
                byte[] client=randomBytes(16),host=new byte[16];
                out.writeInt(PROTOCOL);out.write(client);out.flush();in.readFully(host);
                send(out,secret,aad((byte)1,client,host),new byte[]{1});
                JSONObject j=new JSONObject(new String(receive(in,secret,aad((byte)2,client,host)),"UTF-8"));
                if(j.getInt("protocol")!=PROTOCOL)throw new IOException("Protocol mismatch");
                int signal=j.optInt("signalLevel",-1);
                title=j.optString("operator","");if(title.isEmpty())title="Operator unavailable";
                body="Connected / encrypted\nRemote modem: "+j.optString("model")+"\nSIM: "+(j.optInt("simState")==5?"READY":"State "+j.optInt("simState"))+
                    "\nSignal: "+(signal>=0&&signal<=4?signal+" / 4":"Unavailable")+"\nVoice: "+j.optBoolean("voiceCapable")+"\nSMS: "+j.optBoolean("smsCapable")+"\nUpdated: "+java.text.DateFormat.getTimeInstance().format(new Date());
                ok=true;
            }catch(Exception e){title="Connection unavailable";body="Check host, Wi-Fi and session code. Retrying...";}
            final String a=title,b=body;final boolean success=ok;
            runOnUiThread(()->{if(destroyed||!watching||generation.get()!=g)return;show(a,b);
                if(success)getSharedPreferences("link",MODE_PRIVATE).edit().putString("last_ip",ip).apply();
                handler.postDelayed(()->poll(ip,secret,g),success?3000:5000);
            });
        },"link-client").start();
    }
    @Override public void onDestroy(){destroyed=true;active=false;disconnect();stopHost();title="Disconnected";body="";stopForeground(true);super.onDestroy();}
}
