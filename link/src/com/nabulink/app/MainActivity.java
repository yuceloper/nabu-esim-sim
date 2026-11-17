package com.nabulink.app;
import android.app.Activity;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.widget.*;
import android.view.View;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PORT=37851;
    private volatile ServerSocket listener;
    private volatile boolean running;
    private TextView status;
    private EditText address, code;
    private String token;
    private volatile boolean destroyed;
    private void show(String s){runOnUiThread(()->{if(!destroyed) status.setText(s);});}
    private Button button(LinearLayout l,String name,Runnable action){
        Button b=new Button(this); b.setText(name); b.setOnClickListener(v->action.run()); l.addView(b); return b;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout l=new LinearLayout(this); l.setOrientation(1);
        int p=(int)(24*getResources().getDisplayMetrics().density); l.setPadding(p,p,p,p);
        TextView title=new TextView(this);title.setText("Nabu Link / 0.1");title.setTextSize(30);l.addView(title);
        status=new TextView(this);status.setText("Connect both devices to a trusted local network.");status.setTextSize(17);l.addView(status);
        button(l,"Start phone host",()->startHost());
        button(l,"Stop host",()->{stop();show("Host stopped");});
        address=new EditText(this);address.setHint("Phone local IP address");address.setSingleLine(true);l.addView(address);
        code=new EditText(this);code.setHint("Session code");code.setSingleLine(true);l.addView(code);
        button(l,"Read phone status",()->query());
        ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
    }
    private String addresses() throws Exception{
        StringBuilder s=new StringBuilder();
        for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))
            for(InetAddress a:Collections.list(n.getInetAddresses()))
                if(n.isUp() && a instanceof Inet4Address && a.isSiteLocalAddress() && !a.isLoopbackAddress())
                    s.append(n.getName()).append(": ").append(a.getHostAddress()).append("\n");
        return s.toString();
    }
    private String snapshot() throws Exception{
        TelephonyManager tm=getSystemService(TelephonyManager.class);
        JSONObject j=new JSONObject();j.put("protocol",1);
        j.put("operator",tm==null?"unavailable":tm.getNetworkOperatorName());
        j.put("simState",tm==null?-1:tm.getSimState());
        j.put("voiceCapable",tm!=null&&tm.isVoiceCapable());
        j.put("smsCapable",tm!=null&&tm.isSmsCapable());
        try {android.telephony.SignalStrength sig=tm==null?null:tm.getSignalStrength();j.put("signalLevel",sig==null?-1:sig.getLevel());}catch(RuntimeException e){j.put("signalLevel",-1);}
        return j.toString();
    }
    private synchronized void startHost(){
        if(running)return;
        byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);
        StringBuilder hex=new StringBuilder();for(byte x:bytes)hex.append(String.format(Locale.ROOT,"%02x",x&255));
        token=hex.toString();final String session=token;running=true;
        new Thread(()->{
            try(ServerSocket server=new ServerSocket()){
                synchronized(this){if(!running||!session.equals(token))return;server.bind(new InetSocketAddress(PORT));listener=server;}
                show("Host active\n"+addresses()+"Session code: "+session+"\nKeep this screen open. Status only.");
                while(running&&session.equals(token)){
                    try(Socket peer=server.accept()){
                        peer.setSoTimeout(3000);
                        DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                        String request=in.readUTF();
                        if(request.equals(session)){out.writeUTF(snapshot());out.flush();}
                    }catch(Exception e){if(server.isClosed())break;}
                }
            }catch(Exception e){if(running&&session.equals(token))show("Host error: "+e.getMessage());}
            finally{synchronized(this){if(session.equals(token)){running=false;listener=null;}}}
        },"link-host").start();
    }
    private synchronized void stop(){running=false;token=null;try{if(listener!=null)listener.close();}catch(IOException ignored){}listener=null;}
    private void query(){
        String ip=address.getText().toString().trim(),session=code.getText().toString().trim();
        if(!ip.matches("[0-9.]{7,15}")||!session.matches("[0-9a-f]{32}")){show("Enter host IPv4 and session code.");return;}
        new Thread(()->{
            try(Socket s=new Socket()){
                InetAddress target=InetAddress.getByName(ip);
                if(!target.isSiteLocalAddress())throw new IOException("Private LAN only");
                s.connect(new InetSocketAddress(target,PORT),4000);s.setSoTimeout(4000);
                DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeUTF(session);out.flush();
                JSONObject j=new JSONObject(new DataInputStream(s.getInputStream()).readUTF());
                show("Connected to phone\n"+j.toString(2));
            }catch(Exception e){show("Connection failed: "+e.getMessage());}
        },"link-client").start();
    }
    @Override protected void onDestroy(){destroyed=true;stop();super.onDestroy();}
}
