package com.nabulink.app;

import android.app.Activity;
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

public class MainActivity extends Activity {
    private static final int PORT=37851, PROTOCOL=2;
    private final SecureRandom random=new SecureRandom();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private volatile ServerSocket listener;
    private volatile boolean running, destroyed;
    private volatile String token;
    private boolean watching;
    private TextView status, details;
    private EditText address, code;

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
    private TextView text(LinearLayout l,String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,10,0,10);l.addView(t);return t;
    }
    private void button(LinearLayout l,String name,Runnable action){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setOnClickListener(v->action.run());l.addView(b);
    }
    private void show(String s,String d){if(!destroyed){status.setText(s);details.setText(d);}}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);int p=(int)(24*getResources().getDisplayMetrics().density);
        l.setPadding(p,p,p,p);l.setBackgroundColor(Color.rgb(16,23,34));
        text(l,"Nabu Link",32,Color.WHITE);text(l,"CELLULAR BRIDGE  /  0.2",13,0xff8ebdf3);
        status=text(l,"Not connected",24,0xff73dba7);details=text(l,"Choose phone host or tablet client.",17,0xffcfdeef);
        button(l,"Start phone host",()->startHost());button(l,"Stop phone host",()->{stopHost();show("Host stopped","");});
        address=new EditText(this);address.setTextColor(Color.WHITE);address.setHintTextColor(0xffa0adbd);address.setHint("Phone local IPv4");address.setSingleLine(true);
        address.setText(getPreferences(MODE_PRIVATE).getString("last_ip",""));l.addView(address);
        code=new EditText(this);code.setTextColor(Color.WHITE);code.setHintTextColor(0xffa0adbd);code.setHint("32-character session code");code.setSingleLine(true);code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);l.addView(code);
        button(l,"Connect / live status",()->connect());button(l,"Disconnect",()->disconnect());
        text(l,"Encrypted status only. Keep both apps open. No calls or SMS yet.",13,0xffa0adbd);
        ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
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
                final String info=addresses()+"Session code: "+session+"\nKeep this screen open.";
                runOnUiThread(()->{if(running&&session.equals(token))show("Phone host active",info);});
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
    private synchronized void stopHost(){running=false;token=null;try{if(listener!=null)listener.close();}catch(IOException ignored){}listener=null;}
    private void disconnect(){watching=false;generation.incrementAndGet();handler.removeCallbacksAndMessages(null);show("Disconnected","");}
    private void connect(){
        String ip=address.getText().toString().trim(),secret=code.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(!ip.matches("[0-9.]{7,15}")||!secret.matches("[0-9a-f]{32}")){show("Check pairing","Enter host IPv4 and session code.");return;}
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
                if(success)getPreferences(MODE_PRIVATE).edit().putString("last_ip",ip).apply();
                handler.postDelayed(()->poll(ip,secret,g),success?3000:5000);
            });
        },"link-client").start();
    }
    @Override protected void onStop(){disconnect();stopHost();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;disconnect();stopHost();super.onDestroy();}
}
