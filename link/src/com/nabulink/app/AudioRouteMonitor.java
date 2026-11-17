package com.nabulink.app;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

final class AudioRouteMonitor {
    private final Context context;
    private BluetoothAdapter adapter;
    private volatile BluetoothHeadset headset;
    private boolean requested;
    private volatile boolean running;
    AudioRouteMonitor(Context c){context=c;}
    private boolean permitted(){return Build.VERSION.SDK_INT<31||context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    void start(){running=true;ensureProxy();}
    void ensureProxy(){
        if(!running||requested||!permitted())return;
        try{
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled())return;
            requested=adapter.getProfileProxy(context,new BluetoothProfile.ServiceListener(){
                public void onServiceConnected(int profile,BluetoothProfile proxy){
                    if(!running){try{adapter.closeProfileProxy(profile,proxy);}catch(RuntimeException ignored){}return;}
                    if(proxy instanceof BluetoothHeadset)headset=(BluetoothHeadset)proxy;
                }
                public void onServiceDisconnected(int profile){headset=null;}
            },BluetoothProfile.HEADSET);
        }catch(RuntimeException ignored){}
    }
    String status(){
        if(!permitted())return "Bluetooth permission not granted";
        BluetoothHeadset h=headset;
        if(h==null)return "HFP gateway not available";
        try{
            for(BluetoothDevice d:h.getConnectedDevices())if(h.isAudioConnected(d))return "Bluetooth SCO active (peer not verified)";
            return h.getConnectedDevices().isEmpty()?"No HFP headset connected":"HFP headset connected / SCO idle";
        }catch(RuntimeException e){return "Bluetooth route unavailable";}
    }
    void stop(){
        running=false;BluetoothHeadset h=headset;headset=null;requested=false;
        if(adapter!=null&&h!=null)try{adapter.closeProfileProxy(BluetoothProfile.HEADSET,h);}catch(RuntimeException ignored){}
    }
}
