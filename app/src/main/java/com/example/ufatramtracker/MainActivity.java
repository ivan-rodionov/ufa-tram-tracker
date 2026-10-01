package com.example.ufatramtracker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {
    private static final String API = "https://transportrb.ru/api/rpc.php";
    private static final String DEFAULT_SID = "B76C13A4-F4A6-428D-9E88-7204152F71D4";
    private static final long REFRESH_MS = 15000;

    private WebView map;
    private EditText sidInput;
    private TextView status;
    private TextView arrivals;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            refreshData();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14, 14, 14, 14);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Трамваи Уфы — №5 и №16");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 8);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout sidRow = new LinearLayout(this);
        sidRow.setOrientation(LinearLayout.HORIZONTAL);

        sidInput = new EditText(this);
        sidInput.setSingleLine(true);
        sidInput.setText(getPreferences(MODE_PRIVATE).getString("sid", DEFAULT_SID));
        sidInput.setTextSize(12);
        sidInput.setInputType(InputType.TYPE_CLASS_TEXT);
        sidRow.addView(sidInput, new LinearLayout.LayoutParams(0, 54, 1));

        Button refresh = new Button(this);
        refresh.setText("Обновить");
        refresh.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().putString("sid", sidInput.getText().toString().trim()).apply();
            refreshData();
        });
        sidRow.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 54));

        root.addView(sidRow);

        status = new TextView(this);
        status.setText("Загрузка…");
        status.setTextSize(13);
        status.setPadding(0, 4, 0, 8);
        root.addView(status);

        map = new WebView(this);
        WebSettings ws = map.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        map.loadUrl("file:///android_asset/map.html");
        root.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f));

        ScrollView scroll = new ScrollView(this);
        arrivals = new TextView(this);
        arrivals.setTextSize(16);
        arrivals.setTextColor(Color.BLACK);
        arrivals.setPadding(4, 12, 4, 24);
        scroll.addView(arrivals);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f));

        setContentView(root);

        handler.postDelayed(autoRefresh, 1200);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(autoRefresh);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void refreshData() {
        final String sid = sidInput.getText().toString().trim();
        if (sid.isEmpty()) {
            Toast.makeText(this, "Нужен SID", Toast.LENGTH_SHORT).show();
            return;
        }

        status.setText("Обновляю…");

        executor.submit(() -> {
            try {
                JSONArray units = rpc("getUnits", new JSONObject()
                        .put("sid", sid)
                        .put("marshList", new JSONArray().put("118").put("52")));

                JSONArray kommun = rpc("getStopArrive", new JSONObject()
                        .put("sid", sid).put("st_id", "1176"));

                JSONArray revol = rpc("getStopArrive", new JSONObject()
                        .put("sid", sid).put("st_id", "1186"));

                String arrivalsText = buildArrivalsText(kommun, revol);

                runOnUiThread(() -> {
                    status.setText("Обновлено. Автообновление каждые 15 сек.");
                    arrivals.setText(arrivalsText);
                    String js = "setVehicles(" + JSONObject.quote(units.toString()) + ")";
                    // JSONObject.quote gives a string; parse it in JS.
                    map.evaluateJavascript(
                            "setVehicles(JSON.parse(" + JSONObject.quote(units.toString()) + "))",
                            null
                    );
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Ошибка: " + e.getMessage());
                    arrivals.setText(
                            "Если SID протух, открой transportrb.ru в браузере, " +
                            "возьми новый sid из Network → rpc.php и вставь его сверху."
                    );
                });
            }
        });
    }

    private String buildArrivalsText(JSONArray kommun, JSONArray revol) throws Exception {
        StringBuilder sb = new StringBuilder();

        sb.append("Коммунистическая → Госцирк\n");
        sb.append("────────────────────────\n");
        int count1 = 0;
        for (int i = 0; i < kommun.length(); i++) {
            JSONObject x = kommun.getJSONObject(i);
            if (!"Госцирк".equals(x.optString("laststation_title"))) continue;
            sb.append(formatArrival(x)).append("\n");
            count1++;
        }
        if (count1 == 0) sb.append("Нет ближайших прибытий\n");

        sb.append("\nРеволюционная → ул. Пушкина\n");
        sb.append("──────────────────────────\n");
        int count2 = 0;
        for (int i = 0; i < revol.length(); i++) {
            JSONObject x = revol.getJSONObject(i);
            if (!"Ул. Пушкина".equals(x.optString("laststation_title"))) continue;
            String num = x.optString("mr_num");
            if (!"5".equals(num) && !"16".equals(num)) continue;
            sb.append(formatArrival(x)).append("\n");
            count2++;
        }
        if (count2 == 0) sb.append("Нет ближайших прибытий\n");

        return sb.toString();
    }

    private String formatArrival(JSONObject x) {
        String num = x.optString("mr_num");
        String time = x.optString("tc_arrivetime");
        String sys = x.optString("tc_systime");
        String mins = "?";
        try {
            LocalTime now = LocalTime.parse(sys);
            LocalTime at = LocalTime.parse(time);
            long m = ChronoUnit.MINUTES.between(now, at);
            if (m < 0) m += 24 * 60;
            mins = String.valueOf(m);
        } catch (Exception ignored) {}

        return "№" + num + "  •  " + time + "  •  ~" + mins + " мин  •  " +
                x.optString("firststation_title") + " → " +
                x.optString("laststation_title");
    }

    private JSONArray rpc(String method, JSONObject params) throws Exception {
        JSONObject request = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .put("id", 1);

        URL url = new URL(API);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

        // Только для личного прототипа: у transportrb.ru в curl была проблема с цепочкой сертификата.
        SSLContext ssl = SSLContext.getInstance("TLS");
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        ssl.init(null, trustAll, new SecureRandom());
        conn.setSSLSocketFactory(ssl.getSocketFactory());
        HostnameVerifier hv = (hostname, session) -> "transportrb.ru".equalsIgnoreCase(hostname);
        conn.setHostnameVerifier(hv);

        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300
                ? conn.getInputStream()
                : conn.getErrorStream();

        StringBuilder text = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) text.append(line);
        }

        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + text);
        }

        JSONObject response = new JSONObject(text.toString());
        if (response.has("error")) {
            throw new RuntimeException(response.get("error").toString());
        }

        Object result = response.get("result");
        if (result instanceof JSONArray) return (JSONArray) result;
        throw new RuntimeException("Неожиданный ответ API");
    }
}
