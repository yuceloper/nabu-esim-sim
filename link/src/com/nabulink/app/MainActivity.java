package com.nabulink.app;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.nsd.*;
import android.os.*;
import android.graphics.Color;
import android.widget.*;
import android.view.WindowManager;
import java.net.Inet4Address;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,details,host,scanStatus;
    private LinearLayout devices;
    private Button approve,reject;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving, auto, visible;
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private final Set<String> seen=new HashSet<>();
    private final Runnable refresh=new Runnable(){public void run(){
        status.setText(LinkService.title);details.setText(LinkService.body);host.setText(LinkService.hostInfo);
        String v=LinkService.verification;
        approve.setVisibility(v.isEmpty()?8:0);reject.setVisibility(v.isEmpty()?8:0);
        approve.setText("Codes match — "+v);
        handler.postDelayed(this,300);
    }};
    private TextView text(LinearLayout l,String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,10,0,10);l.addView(t);return t;
    }
    private Button button(LinearLayout l,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());l.addView(b);return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);int p=(int)(24*getResources().getDisplayMetrics().density);l.setPadding(p,p,p,p);l.setBackgroundColor(0xff101722);
        text(l,"Nabu Link",32,Color.WHITE);text(l,"AUTOMATIC PAIRING / 0.4",13,0xff8ebdf3);
        status=text(l,"Disconnected",24,0xff73dba7);details=text(l,"",17,0xffcfdeef);host=text(l,"",16,0xffcfdeef);
        approve=button(l,"Codes match",()->action("APPROVE",null));reject=button(l,"Reject pairing",()->action("REJECT",null));approve.setVisibility(8);reject.setVisibility(8);
        button(l,"Start phone host",()->{stopScan();remember("host");action("HOST",null);});
        button(l,"Allow new pairing (60 seconds)",()->{if(LinkService.active&&!LinkService.hostInfo.isEmpty())action("ALLOW",null);else Toast.makeText(this,"Start phone host first",0).show();});
        button(l,"Find nearby phone",()->{remember("client");scan(false);});
        button(l,"Reconnect saved phone",()->{remember("client");scan(true);});
        scanStatus=text(l,"",14,0xff8ebdf3);devices=new LinearLayout(this);devices.setOrientation(1);l.addView(devices);
        button(l,"Disconnect / stop host",()->{stopScan();remember("");stopService(new Intent(this,LinkService.class));});
        button(l,"Forget paired device",()->new AlertDialog.Builder(this).setTitle("Forget pairing?").setMessage("The other device must also forget the old pairing.").setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->{stopScan();remember("");stopService(new Intent(this,LinkService.class));Pairing.forget(this);}).show());
        text(l,"Same Wi-Fi • Compare the six-digit code on both screens. Never approve a mismatch.",13,0xffa0adbd);
        ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},3);
    }
    private void remember(String role){getSharedPreferences("link",MODE_PRIVATE).edit().putString("role",role).apply();}
    private void action(String a,String ip){Intent i=new Intent(this,LinkService.class).setAction(a);if(ip!=null)i.putExtra("ip",ip);
        if("ALLOW".equals(a)||"APPROVE".equals(a)||"REJECT".equals(a)){if(LinkService.active)startService(i);}else startForegroundService(i);}
    private void scan(boolean saved){
        stopScan();devices.removeAllViews();seen.clear();queue.clear();auto=saved&&Pairing.load(this,"client")!=null;scanStatus.setText("Searching local network...");
        NsdManager manager=getSystemService(NsdManager.class);
        NsdManager.DiscoveryListener l=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){}
            public void onDiscoveryStopped(String t){}
            public void onStartDiscoveryFailed(String t,int e){handler.post(()->{if(discovery==this){stopScan();scanStatus.setText("Discovery failed: "+e);}});}
            public void onStopDiscoveryFailed(String t,int e){}
            public void onServiceLost(NsdServiceInfo n){}
            public void onServiceFound(NsdServiceInfo n){handler.post(()->{if(discovery==this&&seen.add(n.getServiceName())){queue.add(n);resolveNext();}});}
        };
        discovery=l;try{manager.discoverServices(LinkService.TYPE,NsdManager.PROTOCOL_DNS_SD,l);}catch(RuntimeException e){stopScan();scanStatus.setText("Discovery unavailable");}
        handler.postDelayed(()->{if(discovery==l){stopScan();scanStatus.setText(devices.getChildCount()==0?"No phones found. Check Wi-Fi and host.":"Select phone");}},15000);
    }
    private void resolveNext(){
        if(resolving||queue.isEmpty()||discovery==null)return;
        NsdManager.DiscoveryListener current=discovery;NsdServiceInfo n=queue.remove();resolving=true;
        try{getSystemService(NsdManager.class).resolveService(n,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo info,int e){handler.post(()->{resolving=false;resolveNext();});}
            public void onServiceResolved(NsdServiceInfo info){handler.post(()->{
                resolving=false;
                if(discovery==current&&info.getHost() instanceof Inet4Address&&info.getHost().isSiteLocalAddress()&&info.getPort()==37851){
                    String ip=info.getHost().getHostAddress();scanStatus.setText("Phone found");
                    if(auto){String old=getSharedPreferences("link",MODE_PRIVATE).getString("last_ip","");if(ip.equals(old)){stopScan();action("CLIENT",ip);return;}}
                    button(devices,info.getServiceName(),()->{stopScan();action(Pairing.load(MainActivity.this,"client")==null?"PAIR":"CLIENT",ip);});
                }
                resolveNext();
            });}
        });}catch(RuntimeException e){resolving=false;resolveNext();}
    }
    private void stopScan(){NsdManager.DiscoveryListener l=discovery;discovery=null;queue.clear();if(l!=null)try{getSystemService(NsdManager.class).stopServiceDiscovery(l);}catch(RuntimeException ignored){}}
    @Override protected void onStart(){super.onStart();visible=true;handler.post(refresh);
        if(!LinkService.active){String role=getSharedPreferences("link",MODE_PRIVATE).getString("role","");
            if("host".equals(role))action("HOST",null);else if("client".equals(role)&&Pairing.load(this,"client")!=null)scan(true);}
    }
    @Override protected void onStop(){visible=false;stopScan();handler.removeCallbacks(refresh);super.onStop();}
}
