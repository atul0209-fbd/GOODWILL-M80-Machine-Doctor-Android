package com.goodwill.m80doctor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;
    private ScheduledExecutorService poller;
    private volatile boolean alive = true;
    private int prevM30 = 0;
    private int cycleCount = 0;
    private long cycleStartMs = 0;
    private int prevCycle = 0;
    private double runSec = 0, idleSec = 0, alarmSec = 0;
    private long lastTick = System.currentTimeMillis();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8, 43, 76));
        getWindow().setNavigationBarColor(Color.rgb(7, 36, 64));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("goodwill_m80", MODE_PRIVATE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new AppBridge(this), "MachineBridge");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        startPolling();
    }

    @Override protected void onDestroy() {
        alive = false;
        if (poller != null) poller.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private String host() { return prefs.getString("host", "192.168.250.1"); }
    private int port() { return prefs.getInt("port", 30000); }
    private SlmpClient client() { return new SlmpClient(host(), port()); }

    private void startPolling() {
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(() -> {
            if (!alive) return;
            try {
                SlmpClient.Snapshot snap = client().readSnapshot();
                SlmpClient.JobData job = client().readJobData();
                long now = System.currentTimeMillis();
                double dt = Math.max(0, Math.min(5, (now - lastTick) / 1000.0));
                lastTick = now;
                boolean anyAlarm = !snap.activeFAlarms.isEmpty() || snap.ncAlarm != 0 || snap.servoAlarm != 0 ||
                        snap.programError != 0 || snap.operationError != 0 || snap.plcAlarm != 0;
                if (snap.cycle != 0) runSec += dt;
                else if (snap.controllerReady != 0 && !anyAlarm) idleSec += dt;
                if (anyAlarm) alarmSec += dt;
                if (snap.cycle == 1 && prevCycle == 0) cycleStartMs = now;
                if (snap.cycle == 0 && prevCycle == 1) cycleStartMs = 0;
                prevCycle = snap.cycle;
                if (snap.m30 == 1 && prevM30 == 0) cycleCount++;
                prevM30 = snap.m30;

                JSONObject o = new JSONObject();
                o.put("connected", true);
                o.put("host", host());
                o.put("port", port());
                o.put("machine", prefs.getString("name", "VMC-01"));
                o.put("ssid", prefs.getString("ssid", "GOODWILL"));
                o.put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
                o.put("date", new SimpleDateFormat("EEE, dd MMM yyyy", Locale.US).format(new Date()));
                o.put("ready", snap.controllerReady);
                o.put("servo", snap.servoReady);
                o.put("auto", snap.autoMode);
                o.put("cycle", snap.cycle);
                o.put("hold", snap.feedHold);
                o.put("alarm", anyAlarm ? 1 : 0);
                o.put("latency", Math.round(snap.latencyMs));
                o.put("lubeLevel", snap.lubeLevelRaw);
                o.put("lubePress", snap.lubePressureRaw);
                o.put("lubeCmd", snap.lubeCommand);
                o.put("tn0", snap.tn0);
                o.put("tn1", snap.tn1);
                o.put("runSec", (long)runSec);
                o.put("idleSec", (long)idleSec);
                o.put("alarmSec", (long)alarmSec);
                o.put("cycles", cycleCount);
                o.put("cycleSec", cycleStartMs > 0 && snap.cycle != 0 ? (now-cycleStartMs)/1000 : 0);
                o.put("tool", job.spindleTool);
                o.put("tCode", job.tCode);
                o.put("sCode", job.sCode);
                o.put("mCode", job.mCode);
                o.put("spindle", job.spindleSpeed);
                o.put("feedOvr", job.feedOverride);
                o.put("rapidOvr", job.rapidOverride);
                o.put("spindleOvr", job.spindleOverride);
                o.put("xLoad", job.xLoad);
                o.put("zLoad", job.zLoad);
                o.put("curRaw", job.currentPositionRaw);
                o.put("targetRaw", job.targetWindowRaw);
                o.put("waitingPocket", job.waitingPocket);
                JSONArray alarms = new JSONArray();
                for (Integer n : snap.activeFAlarms) alarms.put(n);
                o.put("fAlarms", alarms);
                send("window.onMachineData && window.onMachineData(" + JSONObject.quote(o.toString()) + ");");
            } catch (Exception e) {
                JSONObject o = new JSONObject();
                try {
                    o.put("connected", false);
                    o.put("host", host());
                    o.put("port", port());
                    o.put("message", e.getMessage() == null ? "Connection error" : e.getMessage());
                    o.put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
                } catch (Exception ignored) {}
                send("window.onMachineOffline && window.onMachineOffline(" + JSONObject.quote(o.toString()) + ");");
            }
        }, 300, 1000, TimeUnit.MILLISECONDS);
    }

    private void send(String js) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    public class AppBridge {
        private final Context ctx;
        AppBridge(Context c) { ctx = c; }

        @JavascriptInterface public String getSettings() {
            try {
                JSONObject o = new JSONObject();
                o.put("name", prefs.getString("name", "VMC-01"));
                o.put("ssid", prefs.getString("ssid", "GOODWILL"));
                o.put("host", host());
                o.put("port", port());
                o.put("camera", prefs.getString("camera_url", ""));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String saveSettings(String json, String password) {
            if (!"0209".equals(password)) return "PASSWORD_ERROR";
            try {
                JSONObject o = new JSONObject(json);
                prefs.edit()
                        .putString("name", o.optString("name", "VMC-01"))
                        .putString("ssid", o.optString("ssid", "GOODWILL"))
                        .putString("host", o.optString("host", "192.168.250.1"))
                        .putInt("port", o.optInt("port", 30000))
                        .putString("camera_url", o.optString("camera", ""))
                        .apply();
                return "OK";
            } catch (Exception e) { return "ERROR"; }
        }

        @JavascriptInterface public void openWifi() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        }

        @JavascriptInterface public void openCamera() {
            String url = prefs.getString("camera_url", "");
            if (url.isEmpty()) return;
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public String readDevice(String family, int address) {
            try { return String.valueOf(client().readSingle(family, address)); }
            catch (Exception e) { return "ERR: " + (e.getMessage()==null?"read failed":e.getMessage()); }
        }
    }
}
