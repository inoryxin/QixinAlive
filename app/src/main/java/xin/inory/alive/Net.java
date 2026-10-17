package xin.inory.alive;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 内网自签证书用的「先信任系统 CA、失败再放行」方案。
 *
 * 状态页由 1Panel 的 openresty 反代，证书是 inory.xin 的 Let's Encrypt 证书：
 * 用域名访问时校验能通过；用 IP 访问时主机名对不上，这时才退化成信任该证书。
 * 这是自己内网的自用 app，可以接受。
 */
final class Tls {

    private static final String TAG = "QixinAlive/Tls";

    private Tls() {
    }

    static final SSLSocketFactory TRUST_ALL = buildTrustAll();

    static final HostnameVerifier ACCEPT_ALL_HOSTS = (hostname, session) -> true;

    private static SSLSocketFactory buildTrustAll() {
        try {
            TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return ctx.getSocketFactory();
        } catch (Throwable t) {
            Log.e(TAG, "buildTrustAll failed", t);
            return null;
        }
    }

    /** 对一个 HTTPS 连接套用宽松策略。 */
    public static void relax(HttpsURLConnection conn) {
        if (TRUST_ALL != null) conn.setSSLSocketFactory(TRUST_ALL);
        conn.setHostnameVerifier(ACCEPT_ALL_HOSTS);
    }
}

/** 一次 HTTP GET 的结果。 */
final class HttpResult {

    final boolean ok;
    final int code;
    final String body;
    final String error;

    HttpResult(boolean ok, int code, String body, String error) {
        this.ok = ok;
        this.code = code;
        this.body = body == null ? "" : body;
        this.error = error;
    }

    String shortError() {
        if (error != null) return error;
        return "HTTP " + code;
    }
}

/** 极简 HTTP 客户端（只有 GET），不依赖任何第三方库。 */
final class Http {

    private static final String TAG = "QixinAlive/Http";

    private Http() {
    }

    static HttpResult get(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json,text/html,*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("User-Agent", "QixinAlive/1.0 (Android)");
            conn.setRequestProperty("Cache-Control", "no-cache");
            if (conn instanceof HttpsURLConnection) {
                Tls.relax((HttpsURLConnection) conn);
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = in == null ? "" : readAll(in);
            return new HttpResult(code >= 200 && code < 300, code, body, null);
        } catch (Throwable t) {
            Log.w(TAG, "GET " + url + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return new HttpResult(false, -1, "", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
