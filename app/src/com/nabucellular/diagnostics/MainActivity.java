package com.nabucellular.diagnostics;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.telephony.euicc.EuiccManager;
import android.se.omapi.SEService;
import android.se.omapi.Reader;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private SEService se;
    private TextView result;
    private boolean destroyed;

    private void line(String text) {
        result.append(text + "\n\n");
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad,pad,pad,pad);
        layout.setBackgroundColor(Color.rgb(16,23,34));
        TextView title = new TextView(this);
        title.setText("Nabu eSIM");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        layout.addView(title);
        result = new TextView(this);
        result.setTextSize(16);
        result.setTextColor(Color.rgb(207,222,239));
        result.setPadding(0,pad,0,0);
        layout.addView(result);
        scroll.addView(layout);
        setContentView(scroll);
        line("Developer diagnostics • v0.1");
        line("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        PackageManager pm = getPackageManager();
        for (String feature : new String[]{"android.hardware.telephony","android.hardware.telephony.euicc","android.hardware.telephony.ims","android.hardware.se.omapi.ese","android.hardware.se.omapi.uicc"}) {
            line(feature + ": " + pm.hasSystemFeature(feature));
        }
        try {
            pm.getPackageInfo("com.qualcomm.qti.lpa",0);
            line("Qualcomm LPA: detected");
        } catch (PackageManager.NameNotFoundException e) {
            line("Qualcomm LPA: not visible");
        }
        try {
            EuiccManager manager = getSystemService(EuiccManager.class);
            line("EuiccManager: " + (manager == null ? "unavailable" : "enabled=" + manager.isEnabled()));
        } catch (RuntimeException e) {
            line("EuiccManager: " + e.getClass().getSimpleName());
        }
        line("OMAPI: connecting...");
        try {
            se = new SEService(this, command -> new android.os.Handler(getMainLooper()).post(command), () -> {
                if (destroyed || se == null) return;
                try {
                    Reader[] readers = se.getReaders();
                    line("OMAPI connected; readers: " + readers.length);
                    for (Reader reader : readers) line("Reader: " + reader.getName());
                } catch (RuntimeException e) {
                    line("Reader query: " + e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            line("OMAPI: " + e.getClass().getSimpleName());
        }
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (se != null) {
            try { se.shutdown(); } catch (RuntimeException ignored) {}
        }
        super.onDestroy();
    }
}
