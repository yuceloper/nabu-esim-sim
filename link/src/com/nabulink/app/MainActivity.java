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
    private EditText dialNumber,recipient,message;
    private View callSection,smsSection;
    private TextView audioStatus,historyText;
    private Button historyButton;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving, auto, visible;
    private final ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();
    private final Set<String> seen=new HashSet<>();
    private final Runnable refresh=new Runnable(){public void run(){
        status.setText(LinkService.title);details.setText(LinkService.body);host.setText(LinkService.hostInfo);
        String v=LinkService.verification;
        approve.setVisibility(v.isEmpty()?8:0);reject.setVisibility(v.isEmpty()?8:0);
        approve.setText("Codes match — "+v);
        smsResult.setText(LinkService.smsState);sendButton.setEnabled(LinkService.smsAllowed&&!LinkService.smsBusy);renderMessages();renderCall();historyButton.setEnabled(LinkService.historyAllowed&&!LinkService.historyBusy);historyText.setText(LinkService.historyResult+"\n"+LinkService.historyView);
        handler.postDelayed(this,300);
    }};
    private TextView text(LinearLayout l,String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,10,0,10);l.addView(t);return t;
    }
    private Button button(LinearLayout l,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());l.addView(b);return b;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);int p=(int)(24*getResources().getDisplayMetrics().density);l.setPadding(p,p,p,p);l.setBackgroundColor(0xff101722);
        text(l,"Nabu Link",32,Color.WHITE);text(l,"LOCAL EDITION / 1.0 RC1",13,0xff8ebdf3);
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
        LinearLayout callPanel=new LinearLayout(this);callPanel.setOrientation(1);l.addView(callPanel);callSection=callPanel;
        text(callPanel,"Call Bridge",24,Color.WHITE);
        button(callPanel,"Enable call sharing on phone",()->new AlertDialog.Builder(this).setTitle("Allow paired tablet to control calls?").setMessage("The paired tablet can place, answer and end carrier calls. Audio stays on this phone. Carrier charges may apply.").setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->{getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("calls_enabled",true).apply();requestPermissions(new String[]{"android.permission.READ_PHONE_STATE","android.permission.CALL_PHONE","android.permission.ANSWER_PHONE_CALLS"},8);}).show());
        button(callPanel,"Disable call sharing",()->getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("calls_enabled",false).apply());
        callStatus=text(callPanel,"Call status unavailable",18,0xffcfdeef);
        dialNumber=new EditText(this);dialNumber.setHint("Phone number");dialNumber.setInputType(android.text.InputType.TYPE_CLASS_PHONE);dialNumber.setTextColor(Color.WHITE);dialNumber.setHintTextColor(0xffa0adbd);callPanel.addView(dialNumber);
        dialButton=button(callPanel,"Call via phone",()->{
            String number=dialNumber.getText().toString().replace(" ","").trim();
            if(!number.matches("\\+?[0-9]{10,15}")){Toast.makeText(this,"Enter full phone number",0).show();return;}
            String epoch=currentEpoch();
            new AlertDialog.Builder(this).setTitle("Call "+number+"?").setMessage("Audio stays on the phone. Carrier charges may apply. Emergency calls are not supported.").setNegativeButton("Cancel",null).setPositiveButton("Call",(d,w)->callAction("call_dial",number,epoch)).show();
        });
        answerButton=button(callPanel,"Answer incoming call",()->callAction("call_answer",null,currentEpoch()));
        endButton=button(callPanel,"Decline / end call",()->callAction("call_end",null,currentEpoch()));
        dialButton.setEnabled(false);answerButton.setEnabled(false);endButton.setEnabled(false);
        callOutcome=text(callPanel,"",14,0xff8ebdf3);
        text(callPanel,"Audio stays on phone unless a working Bluetooth call route is established.",13,0xffa0adbd);
        audioStatus=text(callPanel,"Call audio: phone (tablet audio not verified)",14,0xffa0adbd);
        button(callPanel,"Enable phone Bluetooth route monitor",()->{if(Build.VERSION.SDK_INT>=31&&checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},9);else if(LinkService.active)startService(new Intent(this,LinkService.class).setAction("AUDIO_REFRESH"));});
        button(callPanel,"Bluetooth audio settings",()->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        LinearLayout smsPanel=new LinearLayout(this);smsPanel.setOrientation(1);l.addView(smsPanel);smsSection=smsPanel;
        text(smsPanel,"SMS Bridge",24,Color.WHITE);
        button(smsPanel,"Enable SMS sharing on phone",()->new AlertDialog.Builder(this).setTitle("Share SMS with paired tablet?").setMessage("New incoming SMS, including potentially sensitive messages, will be visible on the paired tablet. Sending may incur carrier charges.").setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->{getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_enabled",true).apply();requestPermissions(new String[]{"android.permission.SEND_SMS","android.permission.RECEIVE_SMS"},7);}).show());
        button(smsPanel,"Disable SMS sharing",()->getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_enabled",false).apply());
        recipient=new EditText(this);recipient.setHint("Recipient number");recipient.setInputType(android.text.InputType.TYPE_CLASS_PHONE);recipient.setTextColor(Color.WHITE);recipient.setHintTextColor(0xffa0adbd);smsPanel.addView(recipient);
        message=new EditText(this);message.setHint("SMS body");message.setTextColor(Color.WHITE);message.setHintTextColor(0xffa0adbd);message.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(320)});smsPanel.addView(message);
        sendButton=button(smsPanel,"Send SMS via phone",()->{String to=recipient.getText().toString().replace(" ","").trim(),body=message.getText().toString();if(!to.matches("\\+?[0-9]{7,15}")||body.trim().isEmpty()){Toast.makeText(this,"Enter a valid number and message",0).show();return;}new AlertDialog.Builder(this).setTitle("Send SMS to "+to+"?").setMessage(body+"\n\nCarrier charges may apply.").setNegativeButton("Cancel",null).setPositiveButton("Send",(d,w)->{if(!LinkService.smsAllowed||LinkService.smsBusy)return;startService(new Intent(this,LinkService.class).setAction("SMS_SEND").putExtra("to",to).putExtra("text",body));}).show();});
        sendButton.setEnabled(false);smsResult=text(smsPanel,"",15,0xff8ebdf3);text(smsPanel,"New incoming SMS",18,Color.WHITE);smsMessages=text(smsPanel,"No messages",16,0xffcfdeef);
        button(smsPanel,"Enable recent SMS history on phone",()->new AlertDialog.Builder(this).setTitle("Share recent SMS history?").setMessage("This separately authorizes the paired tablet to request up to eight recent sent and received SMS. Requires SMS sharing and read permission.").setNegativeButton("Cancel",null).setPositiveButton("Enable",(d,w)->{getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_history_enabled",true).apply();requestPermissions(new String[]{"android.permission.READ_SMS"},10);}).show());
        button(smsPanel,"Disable SMS history",()->getSharedPreferences("link",MODE_PRIVATE).edit().putBoolean("sms_history_enabled",false).apply());
        historyButton=button(smsPanel,"Load recent phone SMS",()->{if(LinkService.active&&LinkService.historyAllowed)startService(new Intent(this,LinkService.class).setAction("SMS_HISTORY"));});historyButton.setEnabled(false);
        button(smsPanel,"Clear history view",()->{if(LinkService.active)startService(new Intent(this,LinkService.class).setAction("SMS_HISTORY_CLEAR"));});
        historyText=text(smsPanel,"",15,0xffcfdeef);
        text(l,"Local Wi-Fi or phone hotspot • Saved pairing follows network changes. Compare codes only for first pairing.",13,0xffa0adbd);
        ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
        handleComposeIntent(getIntent());
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},3);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleComposeIntent(intent);}
    private void handleComposeIntent(Intent intent){
        if(intent==null||intent.getData()==null)return;
        android.net.Uri uri=intent.getData();String scheme=uri.getScheme();String action=intent.getAction();
        String number=uri.getSchemeSpecificPart();if(number==null||number.length()>100)return;
        int q=number.indexOf('?');if(q>=0)number=number.substring(0,q);
        number=number.replace(" ","").replace("-","").replace("(","").replace(")","");
        if(!number.matches("\\+?[0-9]{7,15}"))return;
        if(Intent.ACTION_DIAL.equals(action)&&"tel".equals(scheme)){
            dialNumber.setText(number);dialNumber.requestFocus();
            Toast.makeText(this,"Number prepared. Confirm Call via phone to dial.",Toast.LENGTH_LONG).show();
        }else if(Intent.ACTION_SENDTO.equals(action)&&("sms".equals(scheme)||"smsto".equals(scheme))){
            recipient.setText(number);String draft=intent.getStringExtra("sms_body");
            message.setText(draft==null?"":draft.substring(0,Math.min(320,draft.length())));message.requestFocus();
            Toast.makeText(this,"Draft prepared. Confirm Send SMS via phone to send.",Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==9&&results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED&&LinkService.active)startService(new Intent(this,LinkService.class).setAction("AUDIO_REFRESH"));
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
            callOutcome.setText(LinkService.callResult);audioStatus.setText("Phone audio route: "+LinkService.remoteAudio);
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
