package com.zaiapi.android;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.zaiapi.android.databinding.ActivityMainBinding;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 主界面：服务状态、接入信息、服务控制、token 库管理、设置与运行日志。
 * UI 采用 Material 3 Expressive 主题与组件（XML 布局 + ViewBinding）。
 */
public class MainActivity extends ThemedActivity implements LogStore.Listener {

    private static final int REQ_IMPORT = 42;

    private ActivityMainBinding ui;
    private boolean sectionVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        setupEdgeToEdge(ui.getRoot());

        bindActions();
        loadSettings();
        refreshEndpointText();

        askNotificationPermission();
        LogStore.get().addListener(this);
        handler.post(ticker);
        refreshLogs();
    }

    private void bindActions() {
        ui.startBtn.setOnClickListener(v -> {
            saveSettingsQuiet();
            ServerService.startServer(this);
            snack(getString(R.string.toast_starting));
        });
        ui.stopBtn.setOnClickListener(v -> {
            ServerService.stopServer(this);
            snack(getString(R.string.toast_stopping));
        });
        ui.importBtn.setOnClickListener(v -> openTokenPicker());
        ui.harvestBtn.setOnClickListener(v -> openHarvest());
        ui.clearDbBtn.setOnClickListener(v -> clearTokenDb());

        ui.settingsToggleBtn.setOnClickListener(v -> toggleSettings());

        ui.copyUrlBtn.setOnClickListener(v ->
                copyText(getString(R.string.label_api_url), ui.urlText.getText().toString()));
        ui.copyKeyBtn.setOnClickListener(v ->
                copyText(getString(R.string.label_auth_token), ui.keyText.getText().toString()));
        ui.apiUrlRow.setOnClickListener(v ->
                copyText(getString(R.string.label_api_url), ui.urlText.getText().toString()));
        ui.authKeyRow.setOnClickListener(v ->
                copyText(getString(R.string.label_auth_token), ui.keyText.getText().toString()));
        ui.copyLogBtn.setOnClickListener(v -> copyLogs());
        ui.clearLogBtn.setOnClickListener(v -> LogStore.get().clear());

        // Agent Mode：开关直接落盘，无需展开设置区保存
        ui.agentSwitch.setChecked(ServerService.agentMode(this));
        ui.agentSwitch.setOnCheckedChangeListener((button, checked) ->
                ServerService.prefs(this).edit().putBoolean("agent_mode", checked).apply());

        // 动态取色（Android 12+），切换后重建以应用新主题
        ui.dynamicColorSwitch.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
        ui.dynamicColorSwitch.setChecked(useDynamicColor());
        ui.dynamicColorSwitch.setOnCheckedChangeListener((button, checked) -> {
            setDynamicColor(checked);
            recreate();
        });
    }

    private void toggleSettings() {
        sectionVisible = !sectionVisible;
        if (sectionVisible) {
            loadSettings();
        } else {
            saveSettingsQuiet();
            refreshEndpointText();
        }
        final boolean open = sectionVisible;
        animateSection(ui.settingsCard, () -> {
            ui.settingsBox.setVisibility(open ? View.VISIBLE : View.GONE);
            ui.settingsToggleBtn.setText(open ? R.string.action_collapse : R.string.action_expand);
            ui.settingsToggleBtn.setIcon(ContextCompat.getDrawable(this,
                    open ? R.drawable.ic_expand_less : R.drawable.ic_expand_more));
        });
    }

    private void openHarvest() {
        try {
            startActivity(new Intent(this, TokenHarvestActivity.class));
        } catch (Throwable t) {
            snack(getString(R.string.toast_harvest_open_failed, t.getMessage()), Snackbar.LENGTH_LONG);
        }
    }

    // ---------- 设置 ----------

    private void refreshEndpointText() {
        ui.urlText.setText("http://127.0.0.1:" + ServerService.port(this) + "/v1");
        ui.keyText.setText(ServerService.authToken(this));
    }

    private void loadSettings() {
        ui.etPort.setText(String.valueOf(ServerService.port(this)));
        ui.etAuth.setText(ServerService.authToken(this));
        ui.etProxy.setText(ServerService.proxyUrl(this));
        ui.etZaiToken.setText(ServerService.zaiToken(this));
        ui.agentSwitch.setChecked(ServerService.agentMode(this));
    }

    private void saveSettingsQuiet() {
        try {
            String port = text(ui.etPort);
            if (port.isEmpty()) {
                port = "3001";
            }
            ServerService.prefs(this).edit()
                    .putString("port", port)
                    .putString("auth_token", text(ui.etAuth))
                    .putString("proxy_url", text(ui.etProxy))
                    .putString("zai_token", text(ui.etZaiToken))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static String text(android.widget.EditText et) {
        CharSequence cs = et.getText();
        return cs == null ? "" : cs.toString().trim();
    }

    // ---------- token 库导入 ----------

    private void openTokenPicker() {
        if (ServerService.isRunning()) {
            snack(getString(R.string.toast_stop_before_import), Snackbar.LENGTH_LONG);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            snack(getString(R.string.toast_picker_failed, t.getMessage()), Snackbar.LENGTH_LONG);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                File dst = ServerService.tokenDbFile(this);
                File tmp = new File(getFilesDir(), "tokens.importing");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                // 基本校验：SQLite 文件头
                try (InputStream in = new java.io.FileInputStream(tmp)) {
                    byte[] head = new byte[16];
                    int n = in.read(head);
                    String s = n >= 16 ? new String(head, 0, 16, "UTF-8") : "";
                    if (!s.startsWith("SQLite format 3")) {
                        tmp.delete();
                        throw new IllegalStateException("所选文件不是 SQLite 数据库");
                    }
                }
                if (dst.exists()) {
                    File bak = new File(getFilesDir(), "tokens.sqlite.bak");
                    ServerService.copyFile(dst, bak);
                }
                if (!tmp.renameTo(dst)) {
                    ServerService.copyFile(tmp, dst);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                final long size = dst.length();
                LogStore.get().log("APP", "token 库已导入: " + dst.getAbsolutePath() + " (" + size + " 字节)，旧库备份为 tokens.sqlite.bak");
                runOnUiThread(() -> snack(getString(R.string.toast_import_ok)));
            } catch (final Throwable t) {
                LogStore.get().log("APP", "导入失败: " + t);
                runOnUiThread(() -> snack(getString(R.string.toast_import_failed, t.getMessage()),
                        Snackbar.LENGTH_LONG));
            }
        }, "zaiapi-import").start();
    }

    // ---------- 通用 ----------

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        snack(getString(R.string.toast_copied, label));
    }

    private void copyLogs() {
        String logs = LogStore.get().snapshot();
        if (logs.isEmpty()) {
            snack(getString(R.string.toast_no_logs));
            return;
        }
        copyText(getString(R.string.section_log), logs);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** 清空全部 device token（主库 + 采集暂存 + 备份）。 */
    private void clearTokenDb() {
        if (ServerService.isRunning()) {
            snack(getString(R.string.toast_clear_first), Snackbar.LENGTH_LONG);
            return;
        }
        long count = -1;
        try {
            File db = ServerService.tokenDbFile(this);
            if (db.isFile()) {
                SQLiteDatabase c = SQLiteDatabase.openDatabase(
                        db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor cur = c.rawQuery("SELECT COUNT(*) FROM tokens", null);
                if (cur.moveToFirst()) {
                    count = cur.getLong(0);
                }
                cur.close();
                c.close();
            }
        } catch (Throwable ignored) {
        }
        final long n = count;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_clear_db_title)
                .setMessage(getString(R.string.dialog_clear_db_message,
                        n >= 0 ? getString(R.string.dialog_clear_db_count, n) : ""))
                .setPositiveButton(R.string.dialog_clear_db_confirm, (d, w) -> {
                    File dir = getFilesDir();
                    String[] names = {"tokens.sqlite", "tokens.sqlite-wal",
                            "tokens.sqlite-shm", "tokens.sqlite.bak",
                            "tokens.harvest.sqlite", "tokens.harvest.sqlite-wal",
                            "tokens.harvest.sqlite-shm"};
                    int deleted = 0;
                    for (String name : names) {
                        File f = new File(dir, name);
                        if (f.exists() && f.delete()) {
                            deleted++;
                        }
                    }
                    LogStore.get().log("APP", "tokens cleared (" + deleted + " files removed)");
                    snack(getString(R.string.toast_cleared));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshStatus() {
        ServerService.reconcile();
        ServerService.State st = ServerService.getState();
        boolean running = ServerService.isRunning();
        View root = ui.getRoot();

        switch (st) {
            case RUNNING:
                if (running) {
                    ui.statusText.setText(getString(R.string.status_running,
                            ServerService.port(this), uptime()));
                    setStatusStyle(R.drawable.ic_play_circle, ATTR_PRIMARY);
                } else {
                    ui.statusText.setText(getString(R.string.status_abnormal));
                    setStatusStyle(R.drawable.ic_error, ATTR_ERROR);
                }
                break;
            case STARTING:
                ui.statusText.setText(getString(R.string.status_starting));
                setStatusStyle(R.drawable.ic_bolt, ATTR_TERTIARY);
                break;
            default:
                String err = ServerService.getLastError();
                String text = getString(R.string.status_stopped)
                        + (err.isEmpty() ? "" : "\n" + getString(R.string.status_last_error, err));
                ui.statusText.setText(text);
                setStatusStyle(R.drawable.ic_pause_circle,
                        ATTR_ON_SURFACE_VARIANT);
                break;
        }
        ui.startBtn.setEnabled(st == ServerService.State.STOPPED);
        ui.stopBtn.setEnabled(st == ServerService.State.RUNNING);

        Integer tc = ServerService.getTokenCount();
        boolean hasDb = ServerService.tokenDbFile(this).isFile();
        int normalColor = attrColor(root, ATTR_ON_SURFACE);
        int errorColor = attrColor(root, ATTR_ERROR);
        int mutedColor = attrColor(root, ATTR_ON_SURFACE_VARIANT);
        if (tc != null) {
            boolean low = tc <= 5;
            ui.tokenChip.setText(low
                    ? getString(R.string.token_chip_low, tc)
                    : getString(R.string.token_chip_count, tc));
            ui.tokenChip.setTextColor(low ? errorColor : normalColor);
            ui.tokenChip.setChipIconTint(ColorStateList.valueOf(low
                    ? errorColor : attrColor(root, ATTR_PRIMARY)));
        } else {
            ui.tokenChip.setText(getString(hasDb
                    ? R.string.token_chip_pending : R.string.token_chip_missing));
            ui.tokenChip.setTextColor(hasDb ? mutedColor : errorColor);
            ui.tokenChip.setChipIconTint(ColorStateList.valueOf(hasDb ? mutedColor : errorColor));
        }
    }

    /** 状态图标 + 文字配色（跟随主题语义色）。 */
    private void setStatusStyle(int iconRes, int colorAttr) {
        int color = attrColor(ui.getRoot(), colorAttr);
        ui.statusIcon.setImageResource(iconRes);
        ui.statusIcon.setImageTintList(ColorStateList.valueOf(color));
        ui.statusText.setTextColor(color);
    }

    private String uptime() {
        long ms = System.currentTimeMillis() - ServerService.getStartedAt();
        if (ms < 0) {
            return "-";
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /**
     * 日志滚到底。
     * 用 scrollTo 而不是 fullScroll(FOCUS_DOWN)：后者会触发
     * requestChildRectangleOnScreen，把外层 NestedScrollView 一起拽动。
     */
    private void scrollLogToBottom() {
        ui.logScroll.scrollTo(0, Integer.MAX_VALUE);
    }

    private void refreshLogs() {
        ui.logText.setText(LogStore.get().snapshot());
        scrollLogToBottom();
    }

    @Override
    public void onAppended() {
        View child = ui.logScroll.getChildAt(0);
        int bottomGuard = (int) (40 * getResources().getDisplayMetrics().density);
        boolean atBottom = child == null
                || ui.logScroll.getScrollY() + ui.logScroll.getHeight() >= child.getHeight() - bottomGuard;
        ui.logText.setText(LogStore.get().snapshot());
        if (atBottom) {
            scrollLogToBottom();
        }
    }

    @Override
    protected void onDestroy() {
        LogStore.get().removeListener(this);
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}
