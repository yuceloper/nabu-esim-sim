package com.nabulink.app;
import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.graphics.Color;
import android.text.InputType;
import android.widget.*;
import android.view.WindowManager;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,details,host;
    private EditText address,code;
    private final Runnable refresh=new Runnable(){public void run(){
        status.setText(LinkService.title);details.setText(LinkService.body);host.setText(LinkService.hostInfo);
        handler.postDelayed(this,500);
    }};
    private TextView text(LinearLayout l,String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,10,0,10);l.addView(t);return t;
    }
    private void button(LinearLayout l,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());l.addView(b);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout l=new LinearLayout(this);l.setOrientation(1);int p=(int)(24*getResources().getDisplayMetrics().density);l.setPadding(p,p,p,p);l.setBackgroundColor(0xff101722);
        text(l,"Nabu Link",32,Color.WHITE);text(l,"ALWAYS CONNECTED / 0.3",13,0xff8ebdf3);
        status=text(l,"Disconnected",24,0xff73dba7);details=text(l,"",17,0xffcfdeef);host=text(l,"",16,0xffcfdeef);
        button(l,"Start phone host",()->launch(new Intent(this,LinkService.class).setAction("HOST")));
        address=new EditText(this);address.setSingleLine(true);address.setHint("Phone local IPv4");address.setTextColor(Color.WHITE);address.setHintTextColor(0xffa0adbd);
        address.setText(getSharedPreferences("link",MODE_PRIVATE).getString("last_ip",""));l.addView(address);
        code=new EditText(this);code.setSingleLine(true);code.setHint("Session code");code.setTextColor(Color.WHITE);code.setHintTextColor(0xffa0adbd);
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);l.addView(code);
        button(l,"Connect tablet",()->{
            String ip=address.getText().toString().trim(),secret=code.getText().toString().trim().toLowerCase(Locale.ROOT);
            if(!ip.matches("[0-9.]{7,15}")||!secret.matches("[0-9a-f]{32}")){Toast.makeText(this,"Check IP and session code",Toast.LENGTH_SHORT).show();return;}
            launch(new Intent(this,LinkService.class).setAction("CLIENT").putExtra("ip",ip).putExtra("secret",secret));code.setText("");
        });
        button(l,"Disconnect / stop host",()->stopService(new Intent(this,LinkService.class)));
        text(l,"Background service • encrypted status only. Session key stays in memory.",13,0xffa0adbd);
        ScrollView scroll=new ScrollView(this);scroll.addView(l);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},3);
    }
    private void launch(Intent i){startForegroundService(i);}
    @Override protected void onStart(){super.onStart();handler.post(refresh);}
    @Override protected void onStop(){handler.removeCallbacks(refresh);super.onStop();}
}
