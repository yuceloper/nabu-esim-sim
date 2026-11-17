package com.nabulink.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.*;
import org.json.*;
import java.util.*;

final class SmsBridge {
    private final Context context;
    private final ArrayDeque<JSONObject> inbox=new ArrayDeque<>();
    private final LinkedHashMap<String,JSONObject> results=new LinkedHashMap<>();
    private boolean registered;
    private final String sentAction="com.nabulink.app.SMS_SENT."+UUID.randomUUID().toString();
    SmsBridge(Context c){context=c;}
    boolean allowed(String permission){return context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
    boolean enabled(){return context.getSharedPreferences("link",0).getBoolean("sms_enabled",false);}
    private final BroadcastReceiver incoming=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(!enabled()||!allowed(Manifest.permission.RECEIVE_SMS)||!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction()))return;
            try{
                SmsMessage[] parts=Telephony.Sms.Intents.getMessagesFromIntent(i);
                if(parts==null||parts.length==0)return;
                StringBuilder b=new StringBuilder();for(SmsMessage part:parts)if(part!=null)b.append(part.getMessageBody());
                JSONObject msg=new JSONObject().put("id",UUID.randomUUID().toString()).put("from",parts[0].getDisplayOriginatingAddress()).put("text",b.length()>1000?b.substring(0,1000):b.toString()).put("time",parts[0].getTimestampMillis());
                synchronized(SmsBridge.this){inbox.addLast(msg);while(inbox.size()>20)inbox.removeFirst();}
            }catch(Exception ignored){}
        }
    };
    private final BroadcastReceiver sent=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String id=i.getStringExtra("id");int result=getResultCode();
            synchronized(SmsBridge.this){
                JSONObject item=results.get(id);if(item==null)return;
                try{
                    int remaining=item.getInt("remaining")-1;
                    item.put("remaining",remaining);
                    if(result!=Activity.RESULT_OK)item.put("failed",true);
                    if(remaining<=0)item.put("state",item.optBoolean("failed")?"send_failed":"sent");
                }catch(JSONException ignored){}
            }
        }
    };
    void start(){
        if(registered)return;
        IntentFilter input=new IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        IntentFilter output=new IntentFilter(sentAction);output.addDataScheme("nabulink");
        if(Build.VERSION.SDK_INT>=33){
            context.registerReceiver(incoming,input,Manifest.permission.BROADCAST_SMS,null,Context.RECEIVER_EXPORTED);
            context.registerReceiver(sent,output,Context.RECEIVER_NOT_EXPORTED);
        }else{
            context.registerReceiver(incoming,input,Manifest.permission.BROADCAST_SMS,null);
            context.registerReceiver(sent,output);
        }
        registered=true;
    }
    void stop(){
        if(registered){try{context.unregisterReceiver(incoming);}catch(Exception ignored){}try{context.unregisterReceiver(sent);}catch(Exception ignored){}registered=false;}
        synchronized(this){inbox.clear();results.clear();}
    }
    synchronized void clearMessages(){inbox.clear();}
    private boolean historyAllowed(){return enabled()&&context.getSharedPreferences("link",0).getBoolean("sms_history_enabled",false)&&allowed(Manifest.permission.READ_SMS);}
    synchronized JSONObject history()throws Exception{
        if(!historyAllowed())return reply("history_permission_denied");
        JSONArray list=new JSONArray();
        String[] columns={"_id","address","body","date","type"};
        try(android.database.Cursor cursor=context.getContentResolver().query(Telephony.Sms.CONTENT_URI,columns,"type IN (1,2)",null,"date DESC, _id DESC")){
            if(cursor==null)return reply("history_unavailable");
            while(list.length()<8&&cursor.moveToNext()){
                String address=cursor.getString(1),body=cursor.getString(2);
                if(address==null)address="";if(body==null)body="";
                list.put(new JSONObject().put("from",address.substring(0,Math.min(100,address.length()))).put("text",body.substring(0,Math.min(400,body.length()))).put("truncated",body.length()>400).put("time",cursor.getLong(3)).put("direction",cursor.getInt(4)==2?"sent":"received"));
            }
        }catch(SecurityException e){return reply("history_permission_denied");}catch(RuntimeException e){return reply("history_unavailable");}
        return new JSONObject().put("state","ok").put("messages",list);
    }
    synchronized JSONObject status()throws JSONException{
        JSONArray messages=new JSONArray();
        if(enabled()&&allowed(Manifest.permission.RECEIVE_SMS)){
            int count=0;Iterator<JSONObject> it=inbox.descendingIterator();
            while(it.hasNext()&&count++<3)messages.put(it.next());
        }
        return new JSONObject().put("sendAllowed",enabled()&&allowed(Manifest.permission.SEND_SMS)).put("receiveAllowed",enabled()&&allowed(Manifest.permission.RECEIVE_SMS)).put("historyAllowed",historyAllowed()).put("messages",messages);
    }
    synchronized JSONObject execute(JSONObject req)throws Exception{
        String op=req.optString("op"),id=req.optString("id");
        if("sms_history".equals(op))return history();
        if(!id.matches("[0-9a-fA-F-]{36}"))return reply("invalid_id");
        if(!enabled()||!allowed(Manifest.permission.SEND_SMS))return reply("permission_denied");
        JSONObject previous=results.get(id);
        if(previous!=null)return reply(previous.optString("state"));
        if("result".equals(op))return reply("unknown");
        if(!"send".equals(op))return reply("invalid_operation");
        String to=req.optString("to"),body=req.optString("text");
        if(!to.matches("\\+?[0-9]{7,15}")||body.trim().isEmpty()||body.length()>320)return reply("invalid_message");
        android.content.SharedPreferences ledger=context.getSharedPreferences("sms_ledger",0);
        if(ledger.contains(id))return reply("unknown_previous_attempt");
        if(ledger.getAll().size()>=1000)return reply("ledger_full");
        if(!ledger.edit().putBoolean(id,true).commit())return reply("storage_error");
        try{
            SmsManager manager=Build.VERSION.SDK_INT>=31?context.getSystemService(SmsManager.class):SmsManager.getDefault();
            if(manager==null)return reply("sms_unavailable");
            // Use the configured default SMS subscription; no silent SIM selection.
            ArrayList<String> parts=manager.divideMessage(body);
            JSONObject item=new JSONObject().put("state","submitted").put("remaining",parts.size()).put("failed",false);
            results.put(id,item);
            ArrayList<PendingIntent> callbacks=new ArrayList<>();
            for(int p=0;p<parts.size();p++){
                Intent intent=new Intent(sentAction).setPackage(context.getPackageName()).setData(android.net.Uri.parse("nabulink://sms/"+id+"/"+p)).putExtra("id",id);
                callbacks.add(PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_ONE_SHOT));
            }
            if(parts.size()==1)manager.sendTextMessage(to,null,parts.get(0),callbacks.get(0),null);
            else manager.sendMultipartTextMessage(to,null,parts,callbacks,null);
            return reply("submitted");
        }catch(Exception e){results.put(id,new JSONObject().put("state","send_failed"));return reply("send_failed");}
    }
    static JSONObject reply(String state)throws JSONException{return new JSONObject().put("state",state);}
}
