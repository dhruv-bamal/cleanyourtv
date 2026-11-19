package dev.tvwidgets.companion;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One foreground widget surface at a time. Neither notification text nor media data goes to the network. */
public final class WidgetService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = ignored -> publish();
    private MediaSessionManager sessions;
    private Messenger client;
    private ComponentName access;
    private boolean listening;
    private MediaController watched;
    private String requestedArt = "";
    private android.graphics.Bitmap cover;
    private final MediaController.Callback tracks = new MediaController.Callback() {
        @Override public void onMetadataChanged(android.media.MediaMetadata metadata) { publish(); }
        @Override public void onSessionDestroyed() { publish(); }
    };

    private void watch(MediaController current) {
        if (watched != null && current != null && watched.getSessionToken().equals(current.getSessionToken())) return;
        if (watched != null) watched.unregisterCallback(tracks);
        watched = current;
        if (watched != null) watched.registerCallback(tracks,main);
    }

    private void artwork(android.media.MediaMetadata metadata) {
        String uri = metadata == null ? null : metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        String next = uri == null ? "" : uri;
        if (next.equals(requestedArt)) return;
        requestedArt = next;
        cover = null;
        if (next.isEmpty() || !new File(getExternalFilesDir(null),"spotify-artwork.enabled").isFile()) return;
        worker.execute(() -> {
            android.graphics.Bitmap image = SpotifyCover.load(next);
            main.post(() -> {
                if (next.equals(requestedArt) && client != null && image != null) {
                    cover = image;
                    publish();
                }
            });
        });
    }
    private String forecast = "Choose a weather location";
    private final Messenger requests = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        client = message.replyTo;
        publish();
        return true;
    }));

    @Override public void onCreate() {
        super.onCreate();
        sessions = getSystemService(MediaSessionManager.class);
        access = new ComponentName(this, Access.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, access, main);
            listening = true;
        } catch (SecurityException ignored) { /* User can enable access in Android Settings. */ }
        worker.execute(this::loadForecast);
    }

    private void publish() {
        if (client == null) return;
        Bundle data = new Bundle();
        data.putString("weather", forecast);
        try {
            List<MediaController> active = sessions.getActiveSessions(access);
            MediaController spotify = null;
            for (MediaController player : active) {
                if ("com.spotify.tv.android".equals(player.getPackageName())) {
                    data.putParcelable("session", player.getSessionToken());
                    spotify = player;
                    break;
                }
            }
            watch(spotify);
            artwork(spotify == null ? null : spotify.getMetadata());
            if (cover != null) data.putParcelable("cover",cover);
        } catch (SecurityException denied) { data.putBoolean("accessNeeded", true); }
        Message reply = Message.obtain();
        reply.setData(data);
        try { client.send(reply); } catch (RemoteException disconnected) { client = null; }
    }

    private void loadForecast() {
        File config = new File(getExternalFilesDir(null), "weather.json");
        if (!config.isFile()) return;
        String result = "Weather unavailable";
        HttpURLConnection connection = null;
        try {
            JSONObject location = new JSONObject(read(new FileInputStream(config)));
            double lat = location.getDouble("latitude"), lon = location.getDouble("longitude");
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
            String name = location.getString("city");
            String key = lat + "," + lon + "," + name;
            android.content.SharedPreferences cache = getSharedPreferences("forecast", MODE_PRIVATE);
            if (key.equals(cache.getString("place",""))) result = cache.getString("summary",result);
            if (!key.equals(cache.getString("place","")) || System.currentTimeMillis()-cache.getLong("updated",0)>1800000) {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,weather_code");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                JSONObject current = new JSONObject(read(connection.getInputStream())).getJSONObject("current");
                result = name + "\n" + Math.round(current.getDouble("temperature_2m")) + "°C · " + condition(current.getInt("weather_code"));
                cache.edit().putString("place",key).putString("summary",result).putLong("updated",System.currentTimeMillis()).apply();
            }
        } catch (Exception unavailable) { if (result.contains("\n")) result += " (cached)"; }
        finally { if (connection != null) connection.disconnect(); }
        String summary = result;
        main.post(() -> { forecast = summary; publish(); });
    }

    private static String condition(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Cloudy";
        if (code <= 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77 || code == 85 || code == 86) return "Snow";
        return code < 95 ? "Showers" : "Thunderstorms";
    }

    private static String read(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line=reader.readLine())!=null && text.length()<65536) text.append(line);
            return text.toString();
        }
    }

    @Override public IBinder onBind(Intent intent) { return requests.getBinder(); }
    @Override public void onDestroy() {
        client = null;
        watch(null);
        if (listening) sessions.removeOnActiveSessionsChangedListener(changed);
        worker.shutdownNow();
        super.onDestroy();
    }
}
