package com.goodwill.m80doctor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String js = "(function(){"+
                        "var c=document.querySelector('.card.camera');if(c){c.removeAttribute('onclick');c.className='card';c.innerHTML='<div class=\"card-head\"><span>◆ MACHINE HEALTH</span><small>READ ONLY</small></div><div class=\"mini-grid\"><div><small>AXIS SOURCE</small><b>R4500+</b></div><div><small>PLC UNIT</small><b>0.001 mm</b></div><div><small>PROGRAM</small><b id=\"healthProgram\">PROBE</b></div><div><small>STATUS</small><b id=\"healthAxis\">VERIFY XYZ</b></div></div>';}" +
                        "var sc=document.getElementById('setCamera');if(sc&&sc.parentElement)sc.parentElement.remove();" +
                        "var hint=document.querySelector('.position .hint');if(hint)hint.textContent='LIVE MACHINE POSITION • R4500/R4504/R4508 • VERIFY ONCE AGAINST M80 SCREEN';" +
                        "var rb=document.querySelectorAll('.position .rawbox');if(rb.length>1){rb[0].innerHTML='<small>COMMAND SOURCE</small><b>R4500 / 4504 / 4508</b>';rb[1].innerHTML='<small>PLC SCALE</small><b>B = 0.001 mm</b>';}" +
                        "})();";
                view.evaluateJavascript(js, null);
            }
        });
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
                SlmpClient.AxisData axis = client().readAxisData();
                SlmpClient.WindowProbe probe = client().readPlcWindowProbe();
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
                o.put("xPos", axis.xMm); o.put("yPos", axis.yMm); o.put("zPos", axis.zMm);
                o.put("xRawAxis", axis.xRaw); o.put("yRawAxis", axis.yRaw); o.put("zRawAxis", axis.zRaw);
                o.put("xFb", axis.xFbMm); o.put("yFb", axis.yFbMm); o.put("zFb", axis.zFbMm);
                o.put("xFbRaw", axis.xFbRaw); o.put("yFbRaw", axis.yFbRaw); o.put("zFbRaw", axis.zFbRaw);
                o.put("axisScale", "B / 0.001 mm"); o.put("axisMap", "R4500/R4504/R4508");
                JSONArray wc = new JSONArray(); for (int v : probe.config) wc.put(v); o.put("plcWindowConfig", wc);
                JSONArray wins = new JSONArray();
                for (SlmpClient.WindowEntry w : probe.windows) {
                    JSONObject q=new JSONObject();q.put("address",w.address);q.put("section",w.section);q.put("subId",w.subId);q.put("subSection",w.subSection);q.put("dataNo",w.dataNo);q.put("method",w.method);q.put("number",w.number);q.put("result",w.result);wins.put(q);
                }
                o.put("plcWindows", wins);
                if (probe.mainO != null) o.put("programO", probe.mainO);
                if (probe.mainN != null) o.put("programN", probe.mainN);
                if (probe.mainB != null) o.put("programB", probe.mainB);
                if (probe.subO != null) o.put("subO", probe.subO);
                if (probe.subN != null) o.put("subN", probe.subN);
                if (probe.subB != null) o.put("subB", probe.subB);
                JSONArray alarms = new JSONArray();
                for (Integer n : snap.activeFAlarms) alarms.put(n);
                o.put("fAlarms", alarms);
                send("window.onMachineData && window.onMachineData(" + JSONObject.quote(o.toString()) + ");");
                String ax = String.format(Locale.US,
                        "(function(){var s=function(i,v){var e=document.getElementById(i);if(e)e.textContent=v};"+
                        "s('xPos','%.3f');s('yPos','%.3f');s('zPos','%.3f');"+
                        "s('programNo',%s);s('blockNo',%s);s('healthProgram',%s);s('healthAxis','XYZ LIVE');"+
                        "var t=document.getElementById('diagTable');if(t){t.insertAdjacentHTML('beforeend',"+
                        "'<tr><th>XYZ command R4500/04/08</th><td>X %.3f • Y %.3f • Z %.3f mm</td></tr>'+"+
                        "'<tr><th>XYZ feedback R4628/32/36</th><td>X %.3f • Y %.3f • Z %.3f mm</td></tr>'+"+
                        "'<tr><th>Axis raw signed32</th><td>X %d • Y %d • Z %d</td></tr>'+"+
                        "'<tr><th>PLC Window R424-R435</th><td>%s</td></tr>'+"+
                        "'<tr><th>Program execution status</th><td>%s</td></tr>');}})();",
                        axis.xMm,axis.yMm,axis.zMm,
                        probe.mainO!=null?JSONObject.quote("O"+probe.mainO):JSONObject.quote("—"),
                        probe.mainN!=null?JSONObject.quote("N"+probe.mainN):JSONObject.quote("—"),
                        probe.mainO!=null?JSONObject.quote("O"+probe.mainO):JSONObject.quote("PROBE"),
                        axis.xMm,axis.yMm,axis.zMm,axis.xFbMm,axis.yFbMm,axis.zFbMm,
                        axis.xRaw,axis.yRaw,axis.zRaw,
                        JSONObject.quote(windowConfigText(probe.config)),
                        JSONObject.quote(probe.mainO!=null?("Section 45 LIVE: O"+probe.mainO+" N"+probe.mainN+" B"+probe.mainB):"No existing Section 45 window detected"));
                send(ax);
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

    private static String windowConfigText(int[] c) {
        if (c == null || c.length < 12) return "unavailable";
        return "Read1 "+c[0]+"/"+c[1]+" • Read2 "+c[4]+"/"+c[5]+" • Read3 "+c[8]+"/"+c[9];
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
                        .apply();
                return "OK";
            } catch (Exception e) { return "ERROR"; }
        }

        @JavascriptInterface public void openWifi() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        }

        @JavascriptInterface public String readDevice(String family, int address) {
            try { return String.valueOf(client().readSingle(family, address)); }
            catch (Exception e) { return "ERR: " + (e.getMessage()==null?"read failed":e.getMessage()); }
        }
    }
}