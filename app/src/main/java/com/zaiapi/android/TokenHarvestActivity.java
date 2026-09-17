package com.zaiapi.android;

import android.app.Activity;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置 WebView 采集器：加载 chat.z.ai，等页面自带的 z_um SDK 就绪后，
 * 批量调用 window.z_um.getToken() 采集 device token，直接写入
 * files/tokens.sqlite（Android 内置 SQLite，与 Go 服务端的 modernc/sqlite
 * WAL 模式跨进程兼容）。
 *
 * 身份策略：保留手机真实环境（真机即合法用户），仅去除 WebView UA 标记（; wv）
 * 并将 Engine 名称对齐 Chrome，降低指纹风控检出率。
 */
public class TokenHarvestActivity extends Activity {

    private static final String TARGET_URL = "https://chat.z.ai";
    private static final int MAX_SDK_WAIT_MS = 45000;

    private WebView webView;
    private TextView statusText;
    private TextView resultText;
    private ProgressBar progress;
    private Button harvestBtn;
    private Button btn50;
    private Button btn200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean harvesting = false;
    private final List<String> collected = new ArrayList<>();
    private int failCount = 0;
    private long batchId;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void L(String m) {
        Log.i("zh-harvest", m);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        setupWebView();
        webView.loadUrl(TARGET_URL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setKeepScreenOn(true); // 采集期间保持亮屏，避免 WebView 节流

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setText("页面加载中…");
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(14)));

        resultText = new TextView(this);
        resultText.setTextSize(12);
        resultText.setTextColor(Color.parseColor("#0F766E"));
        resultText.setTypeface(Typeface.MONOSPACE);
        root.addView(resultText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(4);
        root.addView(btnRow, rowLp);

        harvestBtn = makeButton("重新加载页面", v -> {
            setHarvesting(false);
            webView.loadUrl(TARGET_URL);
        });
        btn50 = makeButton("采集 50", v -> startHarvest(50));
        btn200 = makeButton("采集 200", v -> startHarvest(200));
        btnRow.addView(harvestBtn, weightLp());
        btnRow.addView(btn50, weightLp());
        btnRow.addView(btn200, weightLp());

        WebView wv = new WebView(this);
        webView = wv;
        LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        wvLp.topMargin = dp(6);
        root.addView(wv, wvLp);

        setContentView(root);
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        return lp;
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        // UA 伪装：真 Chrome Mobile 格式。WebView UA 的特征是
        // "; wv) ... Version/4.0 Chrome/xxx" —— 去掉 "; wv" 与 "Version/4.0"
        // 后即与真 Chrome 无异（内核版本号用设备 WebView 真实值，避免与 TLS/JS
        // 引擎特征矛盾）
        String ua = s.getUserAgentString();
        if (ua != null) {
            String fixed = ua.replace("; wv", "")
                             .replaceFirst("Version/[0-9.]+\\s", "");
            s.setUserAgentString(fixed);
            LogStore.get().log("APP", "WebView UA 伪装: " + fixed);
        }

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // 尽早注入最小伪装：webdriver=false（反自动化标记）
                evaluate("Object.defineProperty(Navigator.prototype,'webdriver',{get:function(){return false},configurable:true});");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                L("onPageFinished: " + url);
                statusText.setText("页面已加载，准备触发 token 端点初始化…");
                injectHarvesterDefinition();
                // 先直接查一次 z_um（部分版本页面加载即暴露）
                handler.postDelayed(() -> checkSdkOrTrigger(0), 2000);
            }
        });
    }

    private void evaluate(String js) {
        webView.evaluateJavascript(js, null);
    }

    private void evaluate(String js, android.webkit.ValueCallback<String> cb) {
        webView.evaluateJavascript(js, cb);
    }

    /** 注入采集函数定义（幂等）。 */
    private void injectHarvesterDefinition() {
        evaluate(
            "(function(){" +
            " if (window.__zh_harvest) return;" +
            " window.__zh_harvest = function(n){" +
            "   if (!window.z_um || typeof window.z_um.getToken !== 'function') {" +
            "     window.AndroidBridge.onDone(0, 1, 'z_um unavailable'); return;" +
            "   }" +
            "   var out = 0, failed = 0, i = 0;" +
            "   function loop(){" +
            "     if (i >= n) { window.AndroidBridge.onDone(out, failed, ''); return; }" +
            "     try {" +
            "       var tok = window.z_um.getToken();" +
            "       Promise.resolve(tok).then(function(t){" +
            "         if (t && typeof t === 'string' && t.length > 0) { window.AndroidBridge.onToken(t); out++; }" +
            "         else { failed++; }" +
            "         i++;" +
            "         window.AndroidBridge.onProgress(out + failed, n, 'ok=' + out);" +
            "         setTimeout(loop, 0);" +
            "       }, function(e){" +
            "         failed++; i++;" +
            "         window.AndroidBridge.onProgress(out + failed, n, 'rej');" +
            "         setTimeout(loop, 0);" +
            "       });" +
            "     } catch (e) {" +
            "       failed++; i++;" +
            "       window.AndroidBridge.onProgress(out + failed, n, 'err');" +
            "       setTimeout(loop, 0);" +
            "     }" +
            "   }" +
            "   loop();" +
            " };" +
            "})();");
    }

    /**
     * 采集前置流程（对齐 token-collector 的关键发现）：
     * z_um SDK 是发送首条消息后才暴露到 window 的 —— 页面加载后若不可用，
     * 需模拟输入 "__" 并点击发送，等待 token 端点初始化（≈12s）后再采集。
     */
    private void checkSdkOrTrigger(int tries) {
        if (tries > 60) {
            statusText.setText("✗ 60 秒内 z_um SDK 未就绪，请重新加载重试");
            statusText.setTextColor(Color.parseColor("#B91C1C"));
            return;
        }
        evaluate(
            "(function(){try{return (window.z_um && typeof window.z_um.getToken==='function')?'1':'0'}catch(e){return '0'}})()",
            v -> {
                String r = v == null ? "" : v.replace("\"", "").trim();
                L("checkSdk tries=" + tries + " z_um=" + r);
                if ("1".equals(r)) {
                    onSdkReady();
                } else if (tries == 0) {
                    // 首次不可用：模拟发送 "__" 触发初始化（同 token-collector）
                    statusText.setText("z_um 未就绪，发送消息触发初始化…");
                    evaluate(
                        "(function(){" +
                        " var input = document.querySelector('#chat-input');" +
                        " var btn = document.querySelector('#send-message-button');" +
                        " if (!input || !btn) return 'elements_missing';" +
                        " try {" +
                        "  var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;" +
                        "  setter.call(input, '__');" +
                        "  input.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "  setTimeout(function(){ btn.click(); }, 300);" +
                        "  return 'sent';" +
                        " } catch(e) { return 'err:' + e; }" +
                        "})()",
                        v2 -> {
                            String s = v2 == null ? "" : v2.replace("\"", "").trim();
                            L("trigger-send: " + s);
                            LogStore.get().log("APP", "WebView 发消息触发: " + s);
                            statusText.setText("已发送触发消息，等待 token 端点初始化（~12s）…");
                        });
                    handler.postDelayed(() -> checkSdkOrTrigger(tries + 1), 2000);
                } else {
                    if (tries % 5 == 0) {
                        statusText.setText("等待 z_um SDK…(" + tries + "s)");
                    }
                    handler.postDelayed(() -> checkSdkOrTrigger(tries + 1), 1000);
                }
            });
    }

    private void onSdkReady() {
        statusText.setText("✓ z_um SDK 就绪，可开始采集");
        statusText.setTextColor(Color.parseColor("#15803D"));
        btn50.setEnabled(true);
        btn200.setEnabled(true);
        if (!harvesting && collected.isEmpty()) {
            // SDK 就绪后自动开始一轮 50
            startHarvest(50);
        }
    }

    private void startHarvest(int n) {
        if (harvesting) {
            Toast.makeText(this, "采集进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ServerService.tokenDbFile(this).getParentFile().exists()
                && !getFilesDir().exists()) {
            getFilesDir().mkdirs();
        }
        harvesting = true;
        collected.clear();
        failCount = 0;
        batchId = System.currentTimeMillis() / 1000;
        progress.setProgress(0);
        btn50.setEnabled(false);
        btn200.setEnabled(false);
        statusText.setText("采集中（目标 " + n + "）…");
        statusText.setTextColor(Color.parseColor("#1D4ED8"));
        // 每次采集注入新的 batch id，入库时区分轮次
        evaluate("window.__zh_batch = " + batchId + ";");
        evaluate("window.__zh_harvest(" + n + ");");
    }

    private void setHarvesting(boolean v) {
        harvesting = v;
        btn50.setEnabled(!v);
        btn200.setEnabled(!v);
    }

    /** JS 桥：采集进度与结果回传。 */
    private class Bridge {

        @JavascriptInterface
        public void onToken(final String token) {
            L("onToken #" + (collected.size() + 1) + " len=" + token.length());
            handler.post(() -> {
                collected.add(token);
                resultText.setText("已采集 " + collected.size() + " 个 token");
            });
        }

        @JavascriptInterface
        public void onProgress(final int done, final int total, final String msg) {
            handler.post(() -> {
                if (total > 0) {
                    progress.setProgress(done * 100 / total);
                }
                if (msg != null && !msg.isEmpty()) {
                    statusText.setText("采集中… " + done + "/" + total + (msg.startsWith("ok=") ? "" : "  [" + msg + "]"));
                }
            });
        }

        @JavascriptInterface
        public void onDone(final int okCount, final int failCountArg, final String error) {
            L("onDone ok=" + okCount + " fail=" + failCountArg + " err=" + error);
            handler.post(() -> {
                setHarvesting(false);
                if (error != null && !error.isEmpty()) {
                    statusText.setText("✗ 采集失败: " + error);
                    statusText.setTextColor(Color.parseColor("#B91C1C"));
                    return;
                }
                if (collected.isEmpty()) {
                    statusText.setText("✗ 0 个 token（全部失败）— 页面可能未完全初始化，尝试重新加载或手动在页面发一条消息后再采");
                    statusText.setTextColor(Color.parseColor("#B91C1C"));
                    return;
                }
                int saved = saveToDb(collected);
                statusText.setText("✓ 采集完成：成功 " + collected.size() + " / 失败 " + failCountArg
                        + "，入库 " + saved + "（batch " + batchId + "）");
                statusText.setTextColor(Color.parseColor("#15803D"));
                resultText.append("\n入库 " + saved + " 个，累计库中 token 请回到主界面查看余量。");
                Toast.makeText(TokenHarvestActivity.this,
                        "入库 " + saved + " 个 token ✓", Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * 采集结果写入独立文件 tokens.harvest.sqlite（避开与 Go 服务进程的
     * WAL 跨进程并发写——实测并发写会导致主库损坏）。主库在服务启动前
     * 由 ServerService.doStart() 的 mergeHarvestIntoMainDb() 合并。
     */
    private int saveToDb(List<String> tokens) {
        File dbFile = new File(getFilesDir(), "tokens.harvest.sqlite");
        int saved = 0;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS tokens (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "token TEXT NOT NULL, " +
                    "batch INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_harvest_token ON tokens(token)");
            long batch = batchId;
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                for (String t : tokens) {
                    cv.clear();
                    cv.put("token", t);
                    cv.put("batch", batch);
                    if (db.insertWithOnConflict("tokens", null, cv,
                            SQLiteDatabase.CONFLICT_IGNORE) != -1) {
                        saved++;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            LogStore.get().log("APP", "token 入库失败: " + t);
            runOnUiThread(() -> Toast.makeText(this,
                    "入库失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return saved;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            (new Handler()).postDelayed(() -> {
                try {
                    ((ViewGroup) webView.getParent()).removeView(webView);
                    webView.destroy();
                } catch (Throwable ignored) {
                }
            }, 200);
        }
        super.onDestroy();
    }
}
