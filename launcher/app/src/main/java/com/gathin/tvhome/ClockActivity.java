package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * The clock screensaver shown by Home itself after home_idle_clock_minutes without input. Some TVs' own
 * screens (CVTE's live TV) do not keep the screen on, so the system screensaver would cut into programmes;
 * this one only ever starts from Home. Any key returns to Home.
 */
public class ClockActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    private ClockScene scene;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        ClockFormat.apply(getWindow().getDecorView(), Config.load(this).clockFormat);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
        if (Config.load(this).widgetShelfEnabled) {
            widgetShelf = new WidgetShelf(this);
            scene.reserveBottom(Math.round(140 * getResources().getDisplayMetrics().density));
        }
    }

    private WidgetShelf widgetShelf;

    @Override
    protected void onResume() {
        super.onResume();
        if (widgetShelf != null) widgetShelf.start();
        scene.start();
    }

    @Override
    protected void onPause() {
        scene.stop();
        if (widgetShelf != null) widgetShelf.stop();
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if (key==KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (key==KeyEvent.KEYCODE_DPAD_DOWN && widgetShelf != null && !widgetShelf.hasFocus()) {
            if (event.getAction()==KeyEvent.ACTION_DOWN) widgetShelf.focusControls();
            return true;
        }
        if (widgetShelf != null && widgetShelf.hasFocus()) return super.dispatchKeyEvent(event);
        // The key that wakes the clock should not also act on Home, so it is consumed here.
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
