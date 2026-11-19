package dev.tvwidgets.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Accept only Spotify's image CDN. Bound network bytes, decoded dimensions and Binder payload. */
final class SpotifyCover {
    static Bitmap load(String address) {
        HttpURLConnection connection = null;
        try {
            if (address.matches("spotify:image:[a-fA-F0-9]{40}"))
                address = "https://i.scdn.co/image/" + address.substring("spotify:image:".length());
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol()) || !"i.scdn.co".equalsIgnoreCase(url.getHost())
                    || url.getUserInfo()!=null || url.getPort()!=-1) return null;
            connection=(HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            if (connection.getResponseCode()!=200) return null;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try (InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[8192];
                int count;
                while ((count=input.read(buffer))!=-1) {
                    if (bytes.size()+count>2097152) return null;
                    bytes.write(buffer,0,count);
                }
            }
            byte[] encoded=bytes.toByteArray();
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
            if (options.outWidth<=0 || options.outHeight<=0) return null;
            options.inJustDecodeBounds=false;
            options.inSampleSize=1;
            while (Math.max(options.outWidth,options.outHeight)/options.inSampleSize>192) options.inSampleSize*=2;
            return BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        } catch (Exception unavailable) { return null; }
        finally { if (connection!=null) connection.disconnect(); }
    }
}
