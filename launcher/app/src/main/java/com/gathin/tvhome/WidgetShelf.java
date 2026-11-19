package com.gathin.tvhome;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** A visible surface binds to the companion; Spotify owns the actual playback. */
final class WidgetShelf {
    private final Activity host;
    private final LinearLayout shelf, controls;
    private final TextView title, artist, weather, condition, times;
    private final ImageView artwork;
    private final SeekBar timeline;
    private final Button previous, play, next, open;
    private MediaController player;
    private PlaybackState state;
    private long duration;
    private Bitmap companionCover;
    private boolean bound, visible;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            long position = state == null ? 0 : state.getPosition();
            if (playing() && state.getLastPositionUpdateTime()>0)
                position += (long)((SystemClock.elapsedRealtime()-state.getLastPositionUpdateTime())*state.getPlaybackSpeed());
            position = Math.max(0,Math.min(duration,position));
            timeline.setProgress(duration>0 ? (int)(position*1000/duration) : 0);
            times.setText(time(position)+"  /  "+time(duration));
            if (playing() && visible) ui.postDelayed(this,1000);
        }
    };
    private final MediaController.Callback events = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { render(); }
        @Override public void onPlaybackStateChanged(PlaybackState playback) { render(); }
        @Override public void onSessionDestroyed() { select(null); }
    };
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (!visible) return true;
        Bundle data = message.getData();
        companionCover=data.getParcelable("cover");
        select(data.getParcelable("session"));
        updateLabels(data); return true;
    }));
    private void updateLabels(Bundle data) {
        String summary = data.getString("weather", "Weather unavailable");
        int newline = summary.indexOf('\n');
        String[] parts = summary.substring(newline + 1).split(" · ", 2);
        weather.setText(newline < 0 ? "—" : parts[0]);
        condition.setText(parts.length == 2 ? parts[1] : summary);
        if (data.getBoolean("accessNeeded")) artist.setText("Enable companion notification access");
    }
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            Message request = Message.obtain();
            request.replyTo = replies;
            try { new Messenger(service).send(request); } catch (RemoteException ignored) { select(null); }
        }
        public void onServiceDisconnected(ComponentName name) { select(null); }
    };

    WidgetShelf(Activity activity) {
        host = activity;
        shelf = row();
        LinearLayout music = row();
        panel(music, 0xf2223032, 0xf0161923);
        artwork = new ImageView(host);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(shape(0xff273136, 11));
        artwork.setClipToOutline(true);
        music.addView(artwork,dp(88),dp(88));
        LinearLayout info = new LinearLayout(host);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16),0,0,0);
        music.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        title = label(info,20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        artist = label(info,14);
        artist.setTextColor(0xffc2cbcc);
        controls = row();
        info.addView(controls);
        previous = button("❮","Previous track",v -> player.getTransportControls().skipToPrevious());
        play = button("▶","Play or pause",v -> {
            if (playing()) player.getTransportControls().pause(); else player.getTransportControls().play();
        });
        next = button("❯","Next track",v -> player.getTransportControls().skipToNext());
        open = button("Open Spotify ↗","Open in Spotify",v -> {
            Intent i=host.getPackageManager().getLeanbackLaunchIntentForPackage("com.spotify.tv.android");
            if (i!=null) host.startActivity(i);
        });
        LinearLayout track = row();
        info.addView(track);
        timeline = new SeekBar(new android.view.ContextThemeWrapper(host, android.R.style.Theme_Material));
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(0xff91e4b1);
        timeline.setProgressTintList(accent);
        timeline.setThumbTintList(accent);
        timeline.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff58636a));
        timeline.setPadding(dp(6),0,dp(10),0);
        timeline.setOnFocusChangeListener((v, focused) -> v.setBackground(focused ? shape(0xff465154,8) : null));
        timeline.setMax(1000);
        timeline.setContentDescription("Track position");
        track.addView(timeline,new LinearLayout.LayoutParams(0,dp(24),1));
        times = label(track,12);
        times.setTextColor(0xffc9d1d2);
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int progress,boolean user) {
                if (user && player!=null) player.getTransportControls().seekTo(duration*progress/1000);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        shelf.addView(music,new LinearLayout.LayoutParams(0,-1,3));
        LinearLayout forecast = new LinearLayout(host);
        forecast.setOrientation(LinearLayout.VERTICAL);
        forecast.setGravity(Gravity.CENTER_VERTICAL);
        panel(forecast, 0xf6373b53, 0xf31e2132);
        TextView location = label(forecast,12);
        location.setText("LOCAL WEATHER");
        location.setLetterSpacing(0.09f);
        location.setTextColor(0xffd0cde6);
        weather = label(forecast,32);
        weather.setText("—");
        condition = label(forecast,14);
        condition.setTextColor(0xffdcdbe8);
        condition.setText("Weather unavailable");
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,-1,1);
        w.leftMargin=dp(10);
        shelf.addView(forecast,w);
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(-1,dp(124),Gravity.BOTTOM);
        place.setMargins(dp(54),0,dp(54),dp(18));
        host.addContentView(shelf,place);
        render();
    }

    private int dp(int value) { return Math.round(value*host.getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout l=new LinearLayout(host); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }
    private void panel(LinearLayout view, int from, int to) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from,to});
        bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1),0x38ffffff);
        view.setBackground(bg);
        view.setPadding(dp(16),dp(5),dp(16),dp(5));
    }
    private TextView label(LinearLayout parent,int size) {
        TextView t=new TextView(host); t.setTextColor(Color.WHITE); t.setTextSize(size); t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END); parent.addView(t); return t;
    }
    private Button button(String text,String description,View.OnClickListener action) {
        Button b = new Button(host);
        boolean named = description.equals("Open in Spotify");
        b.setAllCaps(false);
        b.setTextSize(named ? 13 : 16);
        b.setText(text);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(named ? 12 : 0),0,dp(named ? 12 : 0),0);
        int[][] states = {{-android.R.attr.state_enabled},{android.R.attr.state_focused},{}};
        android.graphics.drawable.StateListDrawable backgrounds = new android.graphics.drawable.StateListDrawable();
        int[] fills = {0xff242d33,0xffe2ffe9,0xff394547};
        for (int i=0; i<states.length; i++) backgrounds.addState(states[i],shape(fills[i],16));
        b.setBackground(backgrounds);
        b.setTextColor(new android.content.res.ColorStateList(states,new int[]{0xff859091,0xff122c22,0xfff3f8f6}));
        b.setContentDescription(description);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(named ? -2 : dp(36),dp(29));
        lp.setMargins(0,dp(3),dp(6),dp(2));
        controls.addView(b,lp);
        return b;
    }
    void start() {
        visible=true;
        Intent intent=new Intent().setClassName("dev.tvwidgets.companion","dev.tvwidgets.companion.WidgetService");
        bound=host.bindService(intent,connection,Context.BIND_AUTO_CREATE);
    }
    void stop() {
        visible=false;
        if (bound) host.unbindService(connection);
        bound=false; select(null);
    }
    boolean focusControls() { return (play.isEnabled()?play:open).requestFocus(); }
    boolean hasFocus() { return shelf.hasFocus(); }
    private void select(MediaSession.Token token) {
        if (player!=null) player.unregisterCallback(events);
        player=token==null?null:new MediaController(host,token);
        if (player!=null) player.registerCallback(events,ui);
        render();
    }
    private boolean playing() { return state!=null && state.getState()==PlaybackState.STATE_PLAYING; }
    private void render() {
        MediaMetadata m=player==null?null:player.getMetadata();
        state=player==null?null:player.getPlaybackState();
        long actions=state==null?0:state.getActions();
        previous.setEnabled((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0);
        next.setEnabled((actions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0);
        play.setEnabled((actions&(PlaybackState.ACTION_PLAY_PAUSE|(playing()?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY)))!=0);
        play.setText(playing()?"Ⅱ":"▶");
        title.setText(m==null?"Spotify":m.getDescription().getTitle());
        artist.setText(m==null?"Open Spotify to start a track":m.getDescription().getSubtitle());
        Bitmap art=companionCover;
        if (art == null && m != null) art=m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null && m != null) art=m.getDescription().getIconBitmap();
        artwork.setImageBitmap(art);
        artwork.setVisibility(art==null?View.GONE:View.VISIBLE);
        duration=m==null?0:Math.max(0,m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        timeline.setEnabled(duration>0 && (actions&PlaybackState.ACTION_SEEK_TO)!=0);
        ui.removeCallbacks(tick); tick.run();
    }
    private static String time(long ms) { long s=ms/1000; return String.format(Locale.ROOT,"%d:%02d",s/60,s%60); }
}
