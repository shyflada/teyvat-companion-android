package app.teyvatcompanion.probe;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import javax.net.ssl.HttpsURLConnection;

public final class OfficialMapHttp {
    private OfficialMapHttp() {}

    public static byte[] download(String url, int limit) {
        HttpsURLConnection connection = null;
        try {
            URI address = new URI(url);
            if (limit <= 0 || !official(address)) {
                return null;
            }
            long expiresAt = SystemClock.elapsedRealtime() + 20_000;
            connection = (HttpsURLConnection) address.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "TeyvatCompanion/0.1 (public map cache)");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                return null;
            }
            long length = connection.getContentLengthLong();
            if (length == 0 || length > limit) {
                return null;
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream result = new ByteArrayOutputStream(length < 0 ? 8192 : (int) length)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > limit - result.size() || SystemClock.elapsedRealtime() > expiresAt) {
                        return null;
                    }
                    result.write(buffer, 0, count);
                }
                return result.size() == 0 ? null : result.toByteArray();
            }
        } catch (IOException | URISyntaxException | SecurityException invalidResource) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean official(URI address) {
        if (!"https".equals(address.getScheme()) || address.getHost() == null || address.getRawUserInfo() != null
                || address.getPort() != -1 || address.getRawFragment() != null) {
            return false;
        }
        String path = address.getRawPath();
        if (path == null) {
            return false;
        }
        switch (address.getHost()) {
            case "sg-public-api.hoyolab.com":
            case "sg-public-api-static.hoyolab.com":
                return path.startsWith("/common/map_user/ys_obc/");
            case "act-webstatic.hoyoverse.com":
            case "act-upload.hoyoverse.com":
                return path.startsWith("/map_manage/map/")
                        || (address.getRawQuery() == null && path.matches("/map_manage/[A-Za-z0-9_/-]+\\.png"));
            default:
                return false;
        }
    }
}
