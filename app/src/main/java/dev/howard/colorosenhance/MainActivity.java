package dev.howard.colorosenhance;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/** Activity boundaries retain the platform's predictive back and each page's saved scroll state. */
public final class MainActivity extends AppCompatActivity {
    private EnhanceApplication app;
    private String selected;
    private Runnable redraw=()->{};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        app=(EnhanceApplication)getApplication();
        selected=state==null?getIntent().getStringExtra("group"):state.getString("group");
        selected=normalizeRoute(selected);
        redraw=MiuixSettings.install(this,selected);
    }
    @Override public void onStart(){super.onStart();app.observe(redraw);app.migration().foreground(this);app.settings().refresh();redraw.run();}
    @Override public void onStop(){app.migration().background(this);app.removeObserver(redraw);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("group",selected);super.onSaveInstanceState(state);}
    public static String normalizeRoute(String route){return SettingsLayout.known(route)?route:"home";}
}
