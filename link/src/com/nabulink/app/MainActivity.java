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
    private TextView smsResult,smsMessages;
    private Button sendButton,dialButton,answerButton,endButton;
    private TextView callStatus,callOutcome;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving, auto, visible;
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private final Set<String> seen=new HashSet<>();
    private final Runnable refresh=new Runnable(){public void run(){
        status.setText(LinkService.title);details.setText(LinkService.body);host.setText(LinkService.hostInfo);
        String v=LinkService.verification;
        approve.setVisibility(v.isEmpty()?8:0);reject.setVisibility(v.isEmpty()?8:0);
        approve.setText("Codes match — "+v);
        smsResult.setText(LinkService.smsState);sendButton.setEnabled(LinkService.smsAllowed&&!LinkService.smsBusy);renderMessages();renderCall();
        handler.postDelayed(this,300);
    }};
    private TextView text(LinearLayout l,String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,10,0,10);l.addView(t);return t;
    }
    private Button button(LinearLayout l,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());l.addView(b);return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);int p=(int)(24*getResources().getDisplayMetrics().density);l.setPadding(p,p,p,p);l.setBackgroundColor(0xff101722);
        text(l,"Nabu Link",32,Color.WHITE);text(l,"CALL BRIDGE / 0.7",13,0xff8ebdf3);
        status=text(l,"Disconnected",24,0xff73dba7);details=text(l,"",17,0xffcfdeef);host=text(l,"",16,0xffcfdeef);
        approve=button(l,"Codes match",()->action("APPROVE",null));reject=button(l,"Reject pairing",()->action("REJECT",null));approve.setVisibility(8);reject.setVisibility(8);
        button(l,"Start phone host",()->{stopScan();remember("host");action("HOST",null);});
        button(l,"Allow new pairing (60 seconds)",()->{if(LinkService.active&&!LinkService.hostInfo.isEmpty())action("ALLOW",null);else Toast.makeText(this,"Start phone host first",0).show();});
        button(l,"Find nearby phone",()->{remember("client");scan(false);});
        button(l,"Reconnect saved phone",()->{stopScan();remember("client");if(Pairing.load(this,"client")!=null)action("AUTO",null);else scan(false);});
        button(l,"Open hotspot settings (phone)",()->{try{startActivity(new Intent("android.settings.TETHER_SETTINGS"));}catch(Exception e){startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));}});
        button(l,"Open Wi-Fi settings (tablet)",()->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)));
        scanStatus=text(l,"",14,0xff8ebdf3);devices=new LinearLayout(this);devices.setOrientation(1);l.addView(devices);
        button(l,"Disconnect / stop host",()->{stopScan();remember("");stopService(new Intent(this,LinkService.class));});
        button(l,"Forget paired device",()->new AlertDialog.Builder(this).setTitle("Forget pairing?").setMessage("The other device must also forget the old pairing.").setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->{stopScan();remember("");stopService(new Intent(this,LinkService.class));Pairing.forget(this);}).show());
        text(l,"Call Bridge",24,Color.WHITE);
        button(l,"Enable call sharing on phone",()->new AlertDialog.Builder(this).setTitle("Allow paired tablet to control calls?").setMessage("The paired tablet can place, answer and end carrier calls. Audio stays on this phone. Carrier charges may apply.").setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->{getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("calls_enabled",true).apply();requestPermissions(new String[]{"android.permission.READ_PHONE_STATE","android.permission.CALL_PHONE","android.permission.ANSWER_PHONE_CALLS"},8);}).show());
        button(l,"Disable call sharing",()->getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("calls_enabled",false).apply());
        callStatus=text(l,"Call status unavailable",18,0xffcfdeef);
        EditText dialNumber=new EditText(this);dialNumber.setHint("Phone number");dialNumber.setInputType(android.text.InputType.TYPE_CLASS_PHONE);dialNumber.setTextColor(Color.WHITE);dialNumber.setHintTextColor(0xffa0adbd);l.addView(dialNumber);
        dialButton=button(l,"Call via phone",()->{
            String number=dialNumber.getText().toString().replace(" ","").trim();
            if(!number.matches("\\+?[0-9]{10,15}")){Toast.makeText(this,"Enter full phone number",0).show();return;}
            String epoch=currentEpoch();
            new AlertDialog.Builder(this).setTitle("Call "+number+"?").setMessage("Audio stays on the phone. Carrier charges may apply. Emergency calls are not supported.").setNegativeButton("Cancel",null).setPositiveButton("Call",(d,w)->callAction("call_dial",number,epoch)).show();
        });
        answerButton=button(l,"Answer incoming call",()->callAction("call_answer",null,currentEpoch()));
        endButton=button(l,"Decline / end call",()->callAction("call_end",null,currentEpoch()));
        dialButton.setEnabled(false);answerButton.setEnabled(false);endButton.setEnabled(false);
        callOutcome=text(l,"",14,0xff8ebdf3);
        text(l,"Call audio remains on Redmi Note 8.",13,0xffa0adbd);
        text(l,"SMS Bridge",24,Color.WHITE);
        button(l,"Enable SMS sharing on phone",()->new AlertDialog.Builder(this).setTitle("Share SMS with paired tablet?").setMessage("New incoming SMS, including potentially sensitive messages, will be visible on the paired tablet. Sending may incur carrier charges.").setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->{getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_enabled",true).apply();requestPermissions(new String[]{"android.permission.SEND_SMS","android.permission.RECEIVE_SMS"},7);}).show());
        button(l,"Disable SMS sharing",()->getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_enabled",false).apply());
        EditText recipient=new EditText(this);recipient.setHint("Recipient number");recipient.setInputType(android.text.InputType.TYPE_CLASS_PHONE);recipient.setTextColor(Color.WHITE);recipient.setHintTextColor(0xffa0adbd);l.addView(recipient);
        EditText message=new EditText(this);message.setHint("SMS body");message.setTextColor(Color.WHITE);message.setHintTextColor(0xffa0adbd);message.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(320)});l.addView(message);
        sendButton=button(l,"Send SMS via phone",()->{String to=recipient.getText().toString().replace(" ","").trim(),body=message.getText().toString();if(!to.matches("\\+?[0-9]{7,15}")||body.trim().isEmpty()){Toast.makeText(this,"Enter a valid number and message",0).show();return;}new AlertDialog.Builder(this).setTitle("Send SMS to "+to+"?").setMessage(body+"\n\nCarrier charges may apply.").setNegativeButton("Cancel",null).setPositiveButton("Send",(d,w)->{if(!LinkService.smsAllowed||LinkService.smsBusy)return;startService(new Intent(this,LinkService.class).setAction("SMS_SEND").putExtra("to",to).putExtra("text",body));}).show();});
        sendButton.setEnabled(false);smsResult=text(l,"",15,0xff8ebdf3);text(l,"New incoming SMS",18,Color.WHITE);smsMessages=text(l,"No messages",16,0xffcfdeef);
        text(l,"Local Wi-Fi or phone hotspot • Saved pairing follows network changes. Compare codes only for first pairing.",13,0xffa0adbd);
        ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},3);
    }
    private String currentEpoch(){try{return new org.json.JSONObject(LinkService.callInfo).optString("epoch");}catch(Exception e){return "";}}
    private void callAction(String op,String number,String epoch){
        if(epoch.isEmpty()||LinkService.callBusy||!LinkService.active)return;
        startService(new Intent(this,LinkService.class).setAction("CALL_CONTROL").putExtra("op",op).putExtra("number",number).putExtra("epoch",epoch));
    }
    private void renderCall(){
        try{
            org.json.JSONObject j=new org.json.JSONObject(LinkService.callInfo);
            int state=j.optInt("state",-1);
            callStatus.setText(state==0?"Idle":state==1?"Incoming call":state==2?"Call active / dialing":"Call status unavailable");
            dialButton.setEnabled(j.optBoolean("dial")&&!LinkService.callBusy);
            answerButton.setEnabled(j.optBoolean("answer")&&!LinkService.callBusy);
            endButton.setEnabled(j.optBoolean("end")&&!LinkService.callBusy);
            callOutcome.setText(LinkService.callResult);
        }catch(Exception e){dialButton.setEnabled(false);answerButton.setEnabled(false);endButton.setEnabled(false);}
    }
    private String lastInbox="";
    private void renderMessages(){
        String raw=LinkService.smsInbox;if(raw.equals(lastInbox))return;lastInbox=raw;
        try{org.json.JSONArray a=new org.json.JSONArray(raw);StringBuilder s=new StringBuilder();
            for(int i=0;i<a.length();i++){org.json.JSONObject m=a.getJSONObject(i);s.append(m.optString("from")).append(" • ").append(java.text.DateFormat.getDateTimeInstance().format(new Date(m.optLong("time")))).append("\n").append(m.optString("text")).append("\n\n");}
            smsMessages.setText(s.length()==0?"No messages":s.toString());
        }catch(Exception e){smsMessages.setText("Unable to read messages");}
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
            if("host".equals(role))action("HOST",null);else if("client".equals(role)&&Pairing.load(this,"client")!=null)action("AUTO",null);}
    }
    @Override protected void onStop(){visible=false;stopScan();handler.removeCallbacks(refresh);super.onStop();}
}
