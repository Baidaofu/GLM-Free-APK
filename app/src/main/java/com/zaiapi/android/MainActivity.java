package com.zaiapi.android;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity implements LogStore.Listener {

    private static final int REQ_IMPORT = 42;

    private TextView statusText;
    private TextView tokenCountText;
    private TextView urlText;
    private TextView keyText;
    private Button startBtn;
    private Button stopBtn;
    private Button importBtn;
    private TextView logText;
    private ScrollView logScroll;

    private LinearLayout settingsBox;
    private EditText etPort;
    private EditText etAuth;
    private EditText etProxy;
    private EditText etZaiToken;
    private Button agentBtn;
    private boolean sectionVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        askNotificationPermission();
        LogStore.get().addListener(this);
        handler.post(ticker);
        refreshLogs();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(10));
        root.setBackgroundColor(Color.WHITE);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        tokenCountText = new TextView(this);
        tokenCountText.setTextSize(13);
        tokenCountText.setTextColor(Color.parseColor("#0F766E"));
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(-1, -2);
        tcLp.topMargin = dp(2);
        root.addView(tokenCountText, tcLp);

        urlText = infoRow(root, "API 地址", "http://127.0.0.1:" + ServerService.port(this) + "/v1");
        keyText = infoRow(root, "Auth Token", ServerService.authToken(this));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(8);
        root.addView(btnRow, rowLp);

        startBtn = makeButton("启动服务", v -> {
            saveSettingsQuiet();
            ServerService.startServer(this);
        });
        stopBtn = makeButton("停止服务", v -> ServerService.stopServer(this));
        importBtn = makeButton("导入token库", v -> openTokenPicker());
        btnRow.addView(startBtn, weightLp());
        btnRow.addView(stopBtn, weightLp());
        btnRow.addView(importBtn, weightLp());

        // 设置折叠头
        LinearLayout setHead = new LinearLayout(this);
        setHead.setOrientation(LinearLayout.HORIZONTAL);
        setHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(-1, -2);
        setLp.topMargin = dp(8);
        root.addView(setHead, setLp);

        TextView setTitle = new TextView(this);
        setTitle.setText("设置（端口 / 密钥 / 出站代理 / 账号）");
        setTitle.setTextSize(13);
        setTitle.setTypeface(Typeface.DEFAULT_BOLD);
        setTitle.setTextColor(Color.parseColor("#475569"));
        setHead.addView(setTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        final Button toggleBtn = makeButton("展开", v -> {
            sectionVisible = !sectionVisible;
            ((Button) v).setText(sectionVisible ? "收起" : "展开");
            settingsBox.setVisibility(sectionVisible ? View.VISIBLE : View.GONE);
            if (sectionVisible) {
                loadSettings();
            } else {
                saveSettingsQuiet();
                urlText.setText("http://127.0.0.1:" + ServerService.port(this) + "/v1");
                keyText.setText(ServerService.authToken(this));
            }
        });
        setHead.addView(toggleBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setVisibility(View.GONE);
        root.addView(settingsBox, new LinearLayout.LayoutParams(-1, -2));

        etPort = addField(settingsBox, "端口（默认 3001）", String.valueOf(ServerService.port(this)),
                InputType.TYPE_CLASS_NUMBER);
        etAuth = addField(settingsBox, "API Key（AUTH_TOKEN，默认 Waguri）",
                ServerService.authToken(this), InputType.TYPE_CLASS_TEXT);
        etProxy = addField(settingsBox,
                "出站代理（须与 token 采集出口 IP 一致；留空=直连）",
                ServerService.proxyUrl(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etZaiToken = addField(settingsBox,
                "ZAI_TOKEN（可选，解锁全模型/图片，留空=游客）",
                ServerService.zaiToken(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout agentRow = new LinearLayout(this);
        agentRow.setOrientation(LinearLayout.HORIZONTAL);
        agentRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(-1, -2);
        arLp.topMargin = dp(6);
        settingsBox.addView(agentRow, arLp);

        TextView agentLabel = new TextView(this);
        agentLabel.setText("Agent Mode（system/工具调用，推荐开）");
        agentLabel.setTextSize(13);
        agentRow.addView(agentLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        agentBtn = makeButton(ServerService.agentMode(this) ? "开" : "关", v -> {
            boolean now = !ServerService.agentMode(this);
            ServerService.prefs(this).edit().putBoolean("agent_mode", now).apply();
            agentBtn.setText(now ? "开" : "关");
        });
        agentBtn.setPadding(dp(10), 0, dp(10), 0);
        agentRow.addView(agentBtn, new LinearLayout.LayoutParams(-2, dp(34)));

        TextView hint = new TextView(this);
        hint.setText("提示：captcha 风控校验出口 IP 与 token 采集环境一致。\n"
                + "手机独立使用时，先开 SagerNet/Clash 连与采集时相同的节点，\n"
                + "再把「出站代理」填为其本地端口（如 http://127.0.0.1:7890）。");
        hint.setTextSize(11);
        hint.setTextColor(Color.parseColor("#64748B"));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.topMargin = dp(6);
        settingsBox.addView(hint, hLp);

        // 日志标题 + 复制/清空
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.topMargin = dp(8);
        root.addView(logHead, headLp);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        Button copyBtn = makeButton("复制日志", v -> copyLogs());
        Button clearBtn = makeButton("清空", v -> LogStore.get().clear());
        logHead.addView(copyBtn, new LinearLayout.LayoutParams(-2, dp(36)));
        logHead.addView(clearBtn, new LinearLayout.LayoutParams(-2, dp(36)));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#0F172A"));
        logScroll.setPadding(dp(8), dp(6), dp(8), dp(6));
        logText = new TextView(this);
        logText.setTextColor(Color.parseColor("#D1FAE5"));
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(ScrollingMovementMethod.getInstance());
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        logLp.topMargin = dp(6);
        root.addView(logScroll, logLp);

        setContentView(root);
    }

    private TextView infoRow(LinearLayout root, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        root.addView(row, lp);

        TextView labelTv = new TextView(this);
        labelTv.setText(label + "：");
        labelTv.setTextSize(13);
        labelTv.setTextColor(Color.parseColor("#475569"));
        row.addView(labelTv, new LinearLayout.LayoutParams(-2, -2));

        TextView valueTv = new TextView(this);
        valueTv.setText(value);
        valueTv.setTextSize(13);
        valueTv.setTextColor(Color.parseColor("#1D4ED8"));
        valueTv.setTypeface(Typeface.MONOSPACE);
        valueTv.setOnClickListener(v -> copyText(label, valueTv.getText().toString()));
        row.addView(valueTv, new LinearLayout.LayoutParams(0, -2, 1f));
        return valueTv;
    }

    private EditText addField(LinearLayout parent, String label, String value, int inputType) {
        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(12);
        labelTv.setTextColor(Color.parseColor("#475569"));
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-1, -2);
        lLp.topMargin = dp(6);
        parent.addView(labelTv, lLp);

        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(13);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(dp(6), dp(6), dp(6), dp(6));
        et.setTextColor(Color.parseColor("#0F172A"));
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(-1, -2);
        eLp.topMargin = dp(2);
        parent.addView(et, eLp);
        return et;
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
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

    // ---------- 设置 ----------

    private void loadSettings() {
        etPort.setText(String.valueOf(ServerService.port(this)));
        etAuth.setText(ServerService.authToken(this));
        etProxy.setText(ServerService.proxyUrl(this));
        etZaiToken.setText(ServerService.zaiToken(this));
        agentBtn.setText(ServerService.agentMode(this) ? "开" : "关");
    }

    private void saveSettingsQuiet() {
        try {
            String port = etPort.getText().toString().trim();
            if (port.isEmpty()) port = "3001";
            ServerService.prefs(this).edit()
                    .putString("port", port)
                    .putString("auth_token", etAuth.getText().toString().trim())
                    .putString("proxy_url", etProxy.getText().toString().trim())
                    .putString("zai_token", etZaiToken.getText().toString().trim())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------- token 库导入 ----------

    private void openTokenPicker() {
        if (ServerService.isRunning()) {
            Toast.makeText(this, "请先停止服务再导入（避免文件被占用）", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器: " + t.getMessage(), Toast.LENGTH_LONG).show();
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
                runOnUiThread(() -> Toast.makeText(this, "导入成功，可启动服务", Toast.LENGTH_SHORT).show());
            } catch (final Throwable t) {
                LogStore.get().log("APP", "导入失败: " + t);
                runOnUiThread(() -> Toast.makeText(this, "导入失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "zaiapi-import").start();
    }

    // ---------- 通用 ----------

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
    }

    private void copyLogs() {
        String logs = LogStore.get().snapshot();
        if (logs.isEmpty()) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
            return;
        }
        copyText("日志", logs);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void refreshStatus() {
        ServerService.State st = ServerService.getState();
        boolean running = ServerService.isRunning();
        switch (st) {
            case RUNNING:
                if (running) {
                    statusText.setText("● 服务运行中  ·  端口 " + ServerService.port(this)
                            + "  ·  已运行 " + uptime());
                    statusText.setTextColor(Color.parseColor("#15803D"));
                } else {
                    statusText.setText("● 服务状态异常（进程已退出）");
                    statusText.setTextColor(Color.parseColor("#B45309"));
                }
                break;
            case STARTING:
                statusText.setText("● 正在启动…");
                statusText.setTextColor(Color.parseColor("#B45309"));
                break;
            default:
                statusText.setText("● 服务已停止");
                statusText.setTextColor(Color.parseColor("#64748B"));
                String err = ServerService.getLastError();
                if (!err.isEmpty()) {
                    statusText.append("\n最近错误: " + err);
                }
        }
        startBtn.setEnabled(st == ServerService.State.STOPPED);
        stopBtn.setEnabled(st == ServerService.State.RUNNING);

        Integer tc = ServerService.getTokenCount();
        boolean hasDb = ServerService.tokenDbFile(this).isFile();
        if (tc != null) {
            String warn = tc <= 5 ? "  ⚠ 余量不足，请重新导入" : "";
            tokenCountText.setText("Token 余量: " + tc + warn);
        } else {
            tokenCountText.setText(hasDb ? "Token 余量: 服务启动后显示" : "⚠ 尚未导入 token 库（tokens.sqlite）");
        }
        tokenCountText.setTextColor(tc != null && tc <= 5
                ? Color.parseColor("#B91C1C") : Color.parseColor("#0F766E"));
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

    private void refreshLogs() {
        logText.setText(LogStore.get().snapshot());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onAppended() {
        View child = logScroll.getChildAt(0);
        boolean atBottom = child == null
                || logScroll.getScrollY() + logScroll.getHeight() >= child.getHeight() - dp(40);
        logText.setText(LogStore.get().snapshot());
        if (atBottom) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onDestroy() {
        LogStore.get().removeListener(this);
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}
