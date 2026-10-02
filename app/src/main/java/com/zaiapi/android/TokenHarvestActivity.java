package com.zaiapi.android;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.zaiapi.android.databinding.ActivityTokenHarvestBinding;
import com.zaiapi.android.databinding.DialogHarvestSettingsBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内置 WebView 采集器：加载 chat.z.ai，等页面自带的 z_um SDK 就绪后，
 * 批量调用 window.z_um.getToken() 采集 device token，直接写入
 * files/tokens.sqlite（Android 内置 SQLite，与 Go 服务端的 modernc/sqlite
 * WAL 模式跨进程兼容）。
 *
 * 身份策略：保留手机真实环境（真机即合法用户），仅去除 WebView UA 标记（; wv）
 * 并将 Engine 名称对齐 Chrome，降低指纹风控检出率。
 *
 * UI：Material 3 Expressive 主题与组件。
 */
public class TokenHarvestActivity extends ThemedActivity {

    private static final String TARGET_URL = "https://chat.z.ai";

    private ActivityTokenHarvestBinding ui;
    private DialogHarvestSettingsBinding dialogUi;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean harvesting = false;
    private final List<String> collected = new ArrayList<>();
    private int failCount = 0;
    private long batchId;

    private static void L(String m) {
        Log.i("zh-harvest", m);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = ActivityTokenHarvestBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        setupEdgeToEdge(ui.getRoot());
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ui.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        // 返回键：优先在 WebView 内后退，其次退出页面（与原行为一致）
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (ui.webView != null && ui.webView.canGoBack()) {
                    ui.webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        ui.reloadBtn.setOnClickListener(v -> {
            setHarvesting(false);
            applyUa(ui.webView.getSettings());
            ui.webView.loadUrl(TARGET_URL);
        });
        ui.btn50.setOnClickListener(v -> startHarvest(50));
        ui.btn200.setOnClickListener(v -> startHarvest(200));
        ui.settingsBtn.setOnClickListener(v -> showHarvestSettings());

        setupWebView();
        ui.webView.loadUrl(TARGET_URL);
    }

    private void setupWebView() {
        WebView webView = ui.webView;
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        applyUa(s);

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
                setStatus(getString(R.string.harvest_page_loaded), statusColor(ATTR_ON_SURFACE));
                injectHarvesterDefinition();
                // 先直接查一次 z_um（部分版本页面加载即暴露）
                handler.postDelayed(() -> checkSdkOrTrigger(0), 2000);
            }
        });
    }

    // ---------- UA management ----------

    /** Custom UA wins; default strips WebView markers from real UA. */
    private void applyUa(WebSettings s) {
        String custom = getSharedPreferences("zaiapi", MODE_PRIVATE).getString("harvest_ua", "");
        if (custom != null && !custom.trim().isEmpty()) {
            s.setUserAgentString(custom.trim());
            L("UA: custom, " + custom.trim().length() + " chars");
            return;
        }
        String ua = s.getUserAgentString();
        if (ua != null) {
            String fixed = ua.replace("; wv", "")
                    .replaceFirst("Version/[0-9.]+\\s", "");
            s.setUserAgentString(fixed);
            L("UA: default, " + fixed.length() + " chars");
        }
    }

    private static final String[] RAND_MODELS = {
        "2210132C", "SM-S918B", "Pixel 8", "Pixel 7", "2201123G",
        "CPH2451", "SM-A546B", "V2243A", "Pixel 8 Pro", "M2012K11AC"
    };
    private static final String[] RAND_BUILDS = {
        "UKQ1.230917.001", "UP1A.231005.007", "UD1A.230803.041",
        "TQ3A.230901.001", "SKQ1.211006.001", "TP1A.220905.001",
        "AP2A.240905.003", "AP1A.240505.005"
    };

    /** Realistic random UA (mobile / desktop Chrome, alternating). */
    private String randomUa() {
        java.util.Random r = new java.util.Random();
        int major = 128 + r.nextInt(25);
        int b = 6000 + r.nextInt(1000);
        int p = r.nextInt(121);
        if (r.nextBoolean()) {
            String model = RAND_MODELS[r.nextInt(RAND_MODELS.length)];
            String build = RAND_BUILDS[r.nextInt(RAND_BUILDS.length)];
            int androidVer = 12 + r.nextInt(5);
            return "Mozilla/5.0 (Linux; Android " + androidVer + "; " + model
                    + " Build/" + build + ") AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/" + major + ".0." + b + "." + p + " Mobile Safari/537.36";
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + major + ".0." + b + "." + p
                + " Safari/537.36";
    }

    // ---------- harvest settings dialog ----------

    private void showHarvestSettings() {
        final SharedPreferences sp = getSharedPreferences("zaiapi", MODE_PRIVATE);
        dialogUi = DialogHarvestSettingsBinding.inflate(getLayoutInflater());
        dialogUi.etUa.setText(sp.getString("harvest_ua", ""));
        dialogUi.etMsg.setText(sp.getString("trigger_msg", ""));

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_harvest_settings_title)
                .setView(dialogUi.getRoot())
                .setPositiveButton(R.string.dialog_save, null)
                .setNeutralButton(R.string.dialog_random_ua, null)
                .setNegativeButton(R.string.dialog_reset_ua, null)
                .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> dialogUi.etUa.setText(randomUa()));
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(v -> dialogUi.etUa.setText(""));
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        sp.edit()
                                .putString("harvest_ua", str(dialogUi.etUa))
                                .putString("trigger_msg", str(dialogUi.etMsg))
                                .apply();
                        applyUa(ui.webView.getSettings());
                        snack(getString(R.string.harvest_settings_saved));
                        dlg.dismiss();
                    });
        });
        dlg.show();
    }

    private static String str(android.widget.EditText et) {
        CharSequence cs = et.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    private void evaluate(String js) {
        ui.webView.evaluateJavascript(js, null);
    }

    private void evaluate(String js, ValueCallback<String> cb) {
        ui.webView.evaluateJavascript(js, cb);
    }

    /** 注入采集函数定义（幂等）。 */
    private void injectHarvesterDefinition() {
        evaluate(
                "(function(){"
                        + " if (window.__zh_harvest) return;"
                        + " window.__zh_harvest = function(n){"
                        + "   if (!window.z_um || typeof window.z_um.getToken !== 'function') {"
                        + "     window.AndroidBridge.onDone(0, 1, 'z_um unavailable'); return;"
                        + "   }"
                        + "   var out = 0, failed = 0, i = 0;"
                        + "   function loop(){"
                        + "     if (i >= n) { window.AndroidBridge.onDone(out, failed, ''); return; }"
                        + "     try {"
                        + "       var tok = window.z_um.getToken();"
                        + "       Promise.resolve(tok).then(function(t){"
                        + "         if (t && typeof t === 'string' && t.length > 0) { window.AndroidBridge.onToken(t); out++; }"
                        + "         else { failed++; }"
                        + "         i++;"
                        + "         window.AndroidBridge.onProgress(out + failed, n, 'ok=' + out);"
                        + "         setTimeout(loop, 0);"
                        + "       }, function(e){"
                        + "         failed++; i++;"
                        + "         window.AndroidBridge.onProgress(out + failed, n, 'rej');"
                        + "         setTimeout(loop, 0);"
                        + "       });"
                        + "     } catch (e) {"
                        + "       failed++; i++;"
                        + "       window.AndroidBridge.onProgress(out + failed, n, 'err');"
                        + "       setTimeout(loop, 0);"
                        + "     }"
                        + "   }"
                        + "   loop();"
                        + " };"
                        + "})();");
    }

    /**
     * 采集前置流程（对齐 token-collector 的关键发现）：
     * z_um SDK 是发送首条消息后才暴露到 window 的 —— 页面加载后若不可用，
     * 需模拟输入 "__" 并点击发送，等待 token 端点初始化（≈12s）后再采集。
     */
    private void checkSdkOrTrigger(int tries) {
        if (tries > 60) {
            setStatus(getString(R.string.harvest_sdk_timeout),
                    statusColor(ATTR_ERROR));
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
                        // 首次不可用：模拟发送一条随机短消息触发初始化
                        // （任意内容均可 —— 触发的是"发送"这个动作，而非内容）
                        String prefMsg = getSharedPreferences("zaiapi", MODE_PRIVATE)
                                .getString("trigger_msg", "");
                        String rnd = (prefMsg != null && !prefMsg.trim().isEmpty())
                                ? prefMsg.trim()
                                : "__" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                        setStatus(getString(R.string.harvest_triggering, rnd),
                                statusColor(ATTR_TERTIARY));
                        evaluate(
                                "(function(){"
                                        + " var input = document.querySelector('#chat-input');"
                                        + " var btn = document.querySelector('#send-message-button');"
                                        + " if (!input || !btn) return 'elements_missing';"
                                        + " try {"
                                        + "  var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;"
                                        + "  setter.call(input, '" + rnd + "');"
                                        + "  input.dispatchEvent(new Event('input',{bubbles:true}));"
                                        + "  setTimeout(function(){ btn.click(); }, 300);"
                                        + "  return 'sent';"
                                        + " } catch(e) { return 'err:' + e; }"
                                        + "})()",
                                v2 -> {
                                    String s = v2 == null ? "" : v2.replace("\"", "").trim();
                                    L("trigger-send: " + s);
                                    LogStore.get().log("APP", "WebView 发消息触发: " + s);
                                    setStatus(getString(R.string.harvest_trigger_sent),
                                            statusColor(ATTR_TERTIARY));
                                });
                        handler.postDelayed(() -> checkSdkOrTrigger(tries + 1), 2000);
                    } else {
                        if (tries % 5 == 0) {
                            setStatus(getString(R.string.harvest_waiting_sdk, tries),
                                    statusColor(ATTR_ON_SURFACE_VARIANT));
                        }
                        handler.postDelayed(() -> checkSdkOrTrigger(tries + 1), 1000);
                    }
                });
    }

    private void onSdkReady() {
        setStatus(getString(R.string.harvest_sdk_ready),
                statusColor(ATTR_PRIMARY));
        ui.btn50.setEnabled(true);
        ui.btn200.setEnabled(true);
        if (!harvesting && collected.isEmpty()) {
            // SDK 就绪后自动开始一轮 50
            startHarvest(50);
        }
    }

    private void startHarvest(int n) {
        if (harvesting) {
            snack(getString(R.string.harvest_in_progress));
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
        ui.progress.setProgressCompat(0, true);
        ui.btn50.setEnabled(false);
        ui.btn200.setEnabled(false);
        setStatus(getString(R.string.harvest_running, n),
                statusColor(ATTR_PRIMARY));
        // 每次采集注入新的 batch id，入库时区分轮次
        evaluate("window.__zh_batch = " + batchId + ";");
        evaluate("window.__zh_harvest(" + n + ");");
    }

    private void setHarvesting(boolean v) {
        harvesting = v;
        ui.btn50.setEnabled(!v);
        ui.btn200.setEnabled(!v);
    }

    private int statusColor(int attr) {
        return attrColor(ui.getRoot(), attr);
    }

    private void setStatus(CharSequence text, int color) {
        ui.statusText.setText(text);
        ui.statusText.setTextColor(color);
    }

    /** JS 桥：采集进度与结果回传。 */
    private class Bridge {

        @JavascriptInterface
        public void onToken(final String token) {
            L("onToken #" + (collected.size() + 1) + " len=" + token.length());
            handler.post(() -> {
                collected.add(token);
                ui.resultText.setText(getString(R.string.harvest_collected, collected.size()));
            });
        }

        @JavascriptInterface
        public void onProgress(final int done, final int total, final String msg) {
            handler.post(() -> {
                if (total > 0) {
                    ui.progress.setProgressCompat(done * 100 / total, true);
                }
                if (msg != null && !msg.isEmpty()) {
                    setStatus(getString(R.string.harvest_progress, done, total,
                            msg.startsWith("ok=") ? "" : "  [" + msg + "]"),
                            statusColor(ATTR_PRIMARY));
                }
            });
        }

        @JavascriptInterface
        public void onDone(final int okCount, final int failCountArg, final String error) {
            L("onDone ok=" + okCount + " fail=" + failCountArg + " err=" + error);
            handler.post(() -> {
                setHarvesting(false);
                if (error != null && !error.isEmpty()) {
                    setStatus(getString(R.string.harvest_failed, error),
                            statusColor(ATTR_ERROR));
                    return;
                }
                if (collected.isEmpty()) {
                    setStatus(getString(R.string.harvest_all_failed),
                            statusColor(ATTR_ERROR));
                    return;
                }
                int saved = saveToDb(collected);
                setStatus(getString(R.string.harvest_done, collected.size(), failCountArg, saved, batchId),
                        statusColor(ATTR_PRIMARY));
                ui.resultText.append("\n");
                ui.resultText.append(getString(R.string.harvest_saved_hint, saved));
                snack(getString(R.string.harvest_saved_ok, saved));
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
            db.execSQL("CREATE TABLE IF NOT EXISTS tokens ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "token TEXT NOT NULL, "
                    + "batch INTEGER NOT NULL)");
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
            runOnUiThread(() -> snack(getString(R.string.harvest_db_failed, t.getMessage()),
                    Snackbar.LENGTH_LONG));
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
    protected void onDestroy() {
        WebView webView = ui == null ? null : ui.webView;
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
