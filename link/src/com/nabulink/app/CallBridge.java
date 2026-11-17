package com.nabulink.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import org.json.JSONObject;
import java.util.*;

final class CallBridge {
    private final Context context;
    private final LinkedHashMap<String,String> outcomes=new LinkedHashMap<>();
    private int lastState=-1;
    private String epoch=UUID.randomUUID().toString();
    private long lastCommand=-5000;
    CallBridge(Context c){context=c;}
    private boolean granted(String p){return context.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    private boolean enabled(){return context.getSharedPreferences("link",0).getBoolean("calls_enabled",false);}
    private int state(){
        if(!enabled()||!granted(Manifest.permission.READ_PHONE_STATE))return -1;
        try{
            TelephonyManager t=context.getSystemService(TelephonyManager.class);
            return t==null?-1:t.getCallState();
        }catch(RuntimeException e){return -1;}
    }
    synchronized JSONObject status()throws Exception{
        int s=state();if(s!=lastState){lastState=s;epoch=UUID.randomUUID().toString();}
        return new JSONObject().put("state",s).put("epoch",epoch)
            .put("dial",s==0&&granted(Manifest.permission.CALL_PHONE))
            .put("answer",s==1&&granted(Manifest.permission.ANSWER_PHONE_CALLS))
            .put("end",(s==1||s==2)&&granted(Manifest.permission.ANSWER_PHONE_CALLS));
    }
    synchronized JSONObject execute(JSONObject q)throws Exception{
        String id=q.optString("id"),op=q.optString("op");
        if(!id.matches("[0-9a-fA-F-]{36}"))return reply("invalid_id");
        if(!enabled())return reply("disabled");
        if(outcomes.containsKey(id))return reply(outcomes.get(id));
        JSONObject current=status();
        if(!epoch.equals(q.optString("epoch")))return reply("call_state_changed");
        if(SystemClock.elapsedRealtime()-lastCommand<3000)return reply("please_wait");
        TelecomManager telecom=context.getSystemService(TelecomManager.class);
        if(telecom==null)return reply("telecom_unavailable");
        String result;
        try{
            if("call_dial".equals(op)){
                if(!current.optBoolean("dial"))return reply("dial_not_allowed");
                String number=q.optString("number");
                if(!number.matches("\\+?[0-9]{10,15}"))return reply("invalid_number");
                if(Build.VERSION.SDK_INT<29)return reply("dial_requires_android_10");
                TelephonyManager tm=context.getSystemService(TelephonyManager.class);
                if(tm==null||tm.isEmergencyNumber(number))return reply("emergency_not_supported");
                lastCommand=SystemClock.elapsedRealtime();outcomes.put(id,"unknown");
                telecom.placeCall(Uri.fromParts("tel",number,null),new Bundle());
                result="dial_requested";
            }else if("call_answer".equals(op)){
                if(!current.optBoolean("answer"))return reply("answer_not_allowed");
                lastCommand=SystemClock.elapsedRealtime();outcomes.put(id,"unknown");
                telecom.acceptRingingCall();result="answer_requested";
            }else if("call_end".equals(op)){
                if(!current.optBoolean("end"))return reply("end_not_allowed");
                lastCommand=SystemClock.elapsedRealtime();outcomes.put(id,"unknown");
                result=telecom.endCall()?"end_requested":"end_failed";
            }else return reply("invalid_operation");
        }catch(SecurityException e){result="permission_denied";}
        catch(RuntimeException e){result="operation_failed";}
        outcomes.put(id,result);
        while(outcomes.size()>128)outcomes.remove(outcomes.keySet().iterator().next());
        return reply(result);
    }
    private JSONObject reply(String result)throws Exception{return new JSONObject().put("state",result);}
}
