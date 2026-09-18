package com.zaiapi.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 前台服务：以子进程方式运行打包在 jniLibs 中的 GLM-Free-API Go 服务端
 * （libzaiapi.so，实为 android/arm64 可执行文件），捕获其 stdout/stderr
 * 到 LogStore，供界面实时展示。
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.zaiapi.android.START";
    public static final String ACTION_STOP = "com.zaiapi.android.STOP";
    /** 设置变更后通知运行中的服务（当前实现：提示需重启生效）。 */
    public static final String ACTION_SETTINGS_CHANGED = "com.zaiapi.android.SETTINGS";

    private static final String CHANNEL_ID = "zaiapi_server";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS = "zaiapi";

    public enum State {STOPPED, STARTING, RUNNING}

    // 进程级单例状态（与 Activity 同进程，直接静态共享）
    private static volatile State state = State.STOPPED;
    private static volatile Process process;
    private static volatile long startedAt = 0L;
    private static volatile String lastError = "";
    private static final AtomicReference<Integer> tokenCount = new AtomicReference<>(null);

    private PowerManager.WakeLock wakeLock;

    public static State getState() {
        return state;
    }

    public static long getStartedAt() {
        return startedAt;
    }

    public static String getLastError() {
        return lastError;
    }

    public static Integer getTokenCount() {
        return tokenCount.get();
    }

    public static boolean isRunning() {
        Process p = process;
        return state == State.RUNNING && p != null && p.isAlive();
    }

    /**
     * UI ticker 每秒调用：若状态为 RUNNING 但进程已死（异常情况），
     * 自愈纠正为 STOPPED，避免启动按钮永久灰死。
     */
    public static void reconcile() {
        if (state == State.RUNNING) {
            Process p = process;
            if (p == null || !p.isAlive()) {
                LogStore.get().log("APP",
                        "检测到进程已退出但状态未更新，自动纠正为已停止");
                state = State.STOPPED;
                process = null;
                releaseWakeLockStatic();
            }
        }
    }

    private static void releaseWakeLockStatic() {
        // no-op placeholder: wake lock release happens on service instance;
        // static context cannot reach it, next start acquires fresh lock
    }

    // ---------- 设置存取 ----------

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    public static int port(Context ctx) {
        try {
            return Integer.parseInt(prefs(ctx).getString("port", "3001").trim());
        } catch (Throwable t) {
            return 3001;
        }
    }

    public static String authToken(Context ctx) {
        String v = prefs(ctx).getString("auth_token", "Waguri");
        return v == null || v.trim().isEmpty() ? "Waguri" : v.trim();
    }

    public static String proxyUrl(Context ctx) {
        return prefs(ctx).getString("proxy_url", "").trim();
    }

    public static String zaiToken(Context ctx) {
        return prefs(ctx).getString("zai_token", "").trim();
    }

    public static boolean agentMode(Context ctx) {
        return prefs(ctx).getBoolean("agent_mode", true);
    }

    public static File tokenDbFile(Context ctx) {
        return new File(ctx.getFilesDir(), "tokens.sqlite");
    }

    // ---------- 启动/停止入口 ----------

    public static void startServer(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stopServer(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopInternal("用户手动停止");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startForegroundWithNotification();
            if (isRunning() || state == State.STARTING) {
                LogStore.get().log("APP", "服务已在运行，忽略重复的启动请求");
            } else {
                startInternal();
            }
            return START_STICKY;
        }
        // 系统回收后重启但进程已不在：直接停掉，避免"假前台"
        if (!isRunning()) {
            stopSelf();
        }
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "GLM2API 服务", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("GLM2API 服务运行中")
                .setContentText("本地端口 " + port(this) + "，点按返回应用")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startInternal() {
        state = State.STARTING;
        lastError = "";
        LogStore.get().log("APP", "========== 正在启动 GLM-Free-API 服务 ==========");
        new Thread(this::doStart, "zaiapi-starter").start();
    }

    private void doStart() {
        try {
            File filesDir = getFilesDir();
            File tokenDb = tokenDbFile(this);
            mergeHarvestIntoMainDb(filesDir);
            if (!tokenDb.isFile()) {
                throw new IllegalStateException(
                        "未找到 token 库 tokens.sqlite —— 请先在 PC 端运行 token-collector 采集，"
                                + "再用本应用「导入 token 库」按钮导入");
            }

            String binPath = getApplicationInfo().nativeLibraryDir + "/libzaiapi.so";
            File bin = new File(binPath);
            if (!bin.exists()) {
                throw new IllegalStateException("未找到原生服务端程序: " + binPath
                        + "（当前 CPU 架构可能不受支持，本应用仅内置 arm64-v8a）");
            }
            try {
                Os.chmod(binPath, 0755);
            } catch (Throwable t) {
                LogStore.get().log("APP", "chmod 失败（通常可忽略）: " + t.getMessage());
            }
            if (!bin.canExecute()) {
                // 部分机型不允许直接执行 nativeLibraryDir，复制到私有目录兜底
                File local = new File(filesDir, "bin/libzaiapi");
                local.getParentFile().mkdirs();
                copyFile(bin, local);
                Os.chmod(local.getAbsolutePath(), 0755);
                binPath = local.getAbsolutePath();
                LogStore.get().log("APP", "nativeLibraryDir 不可执行，已改用 " + binPath);
            }

            String proxy = proxyUrl(this);
            ProcessBuilder pb = new ProcessBuilder(binPath,
                    "--db-path", tokenDb.getAbsolutePath(),
                    "--agent-mode=" + agentMode(this));
            pb.directory(filesDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PORT", String.valueOf(port(this)));
            env.put("AUTH_TOKEN", authToken(this));
            env.put("LOG_LEVEL", "INFO");
            env.put("HOME", filesDir.getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            // 关键：Z.AI 上游 + 阿里云 captcha 验证都从代理出口走，
            // 保证出口 IP 与 device token 采集时的 IP 一致（阿里风控要求）
            if (!proxy.isEmpty()) {
                env.put("HTTPS_PROXY", proxy);
                env.put("HTTP_PROXY", proxy);
                env.put("ALL_PROXY", proxy);
                env.put("NO_PROXY", "127.0.0.1,localhost");
            }
            String zt = zaiToken(this);
            if (!zt.isEmpty()) {
                env.put("ZAI_TOKEN", zt);
            }

            LogStore.get().log("APP", "工作目录: " + filesDir.getAbsolutePath());
            LogStore.get().log("APP", "token 库: " + tokenDb.getAbsolutePath());
            LogStore.get().log("APP", "监听端口: " + port(this) + "  Auth: " + authToken(this));
            LogStore.get().log("APP", "出站代理: " + (proxy.isEmpty() ? "（直连 — 若 captcha 验证失败请配置与采集环境一致的代理）" : proxy));
            LogStore.get().log("APP", "AgentMode: " + agentMode(this) + "  ZAI_TOKEN: " + (zt.isEmpty() ? "未设置(guest)" : "已设置"));

            Process p;
            try {
                p = pb.start();
            } catch (java.io.IOException ioe) {
                // 个别 ROM 禁止直接执行 nativeLibraryDir，复制到私有目录重试
                String msg = String.valueOf(ioe.getMessage());
                if (!msg.contains("Permission denied") && !msg.contains("error=13")) {
                    throw ioe;
                }
                LogStore.get().log("APP", "直接执行被拒绝（" + msg + "），复制到私有目录后重试");
                File local = new File(filesDir, "bin/libzaiapi");
                local.getParentFile().mkdirs();
                copyFile(bin, local);
                Os.chmod(local.getAbsolutePath(), 0755);
                pb.command(local.getAbsolutePath(),
                        "--db-path", tokenDb.getAbsolutePath(),
                        "--agent-mode=" + agentMode(this));
                p = pb.start();
            }
            final Process proc = p;
            process = proc;
            startedAt = System.currentTimeMillis();
            state = State.RUNNING;
            tokenCount.set(null);
            acquireWakeLock();
            LogStore.get().log("APP", "进程已启动，等待服务就绪...");

            Thread reader = new Thread(() -> readOutput(proc), "zaiapi-log-reader");
            reader.setDaemon(true);
            reader.start();

            Thread probe = new Thread(this::probeReady, "zaiapi-ready-probe");
            probe.setDaemon(true);
            probe.start();

            Thread waiter = new Thread(() -> {
                int code;
                try {
                    code = proc.waitFor();
                } catch (InterruptedException e) {
                    code = -1;
                }
                LogStore.get().log("APP", "服务进程已退出，exit code = " + code);
                state = State.STOPPED;
                process = null;
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }, "zaiapi-waiter");
            waiter.setDaemon(true);
            waiter.start();
        } catch (Throwable t) {
            lastError = String.valueOf(t.getMessage());
            LogStore.get().log("APP", "启动失败: " + t);
            state = State.STOPPED;
            process = null;
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    private void readOutput(Process p) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                LogStore.get().raw(line);
            }
        } catch (Throwable t) {
            LogStore.get().log("APP", "日志读取结束: " + t.getMessage());
        }
    }

    /** 就绪探测 + token 余量轮询（每 5 秒查 /health 的 tokenCount）。 */
    private void probeReady() {
        boolean announced = false;
        while (isRunning()) {
            try {
                // 必须绕过系统代理/VPN，否则开启代理时 127.0.0.1 探测会失败造成误报
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + port(this) + "/health")
                        .openConnection(java.net.Proxy.NO_PROXY);
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                int code = c.getResponseCode();
                if (code == 200) {
                    String body = readAll(c.getInputStream());
                    c.disconnect();
                    Integer tc = parseTokenCount(body);
                    if (tc != null) {
                        tokenCount.set(tc);
                    }
                    if (!announced) {
                        announced = true;
                        LogStore.get().log("APP", "服务就绪 ✓  API: http://127.0.0.1:" + port(this) + "/v1"
                                + "  （token 余量 " + (tc == null ? "?" : tc) + "）");
                    }
                } else {
                    c.disconnect();
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static Integer parseTokenCount(String json) {
        try {
            int i = json.indexOf("\"tokenCount\"");
            if (i < 0) return null;
            int j = json.indexOf(':', i);
            int k = j + 1;
            while (k < json.length() && (json.charAt(k) == ' ')) k++;
            int s = k;
            while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) k++;
            return Integer.parseInt(json.substring(s, k));
        } catch (Throwable t) {
            return null;
        }
    }

    private void stopInternal(String reason) {
        LogStore.get().log("APP", "========== 停止服务: " + reason + " ==========");
        Process p = process;
        if (p != null) {
            // destroy() 发送 SIGTERM —— Go 端 signal 处理会优雅关闭（清空 Z.AI 会话池）
            p.destroy();
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }, "zaiapi-killer").start();
        } else {
            state = State.STOPPED;
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    static void copyFile(File in, File out) throws Exception {
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        try (InputStream is = new java.io.FileInputStream(in);
             OutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
        }
    }

    /**
     * 启动 Go 进程前调用（此时无并发）：把 WebView 采集器写入的
     * tokens.harvest.sqlite 合并进主 tokens.sqlite，去重后删除采集文件。
     */
    private void mergeHarvestIntoMainDb(File filesDir) {
        File harvest = new File(filesDir, "tokens.harvest.sqlite");
        if (!harvest.isFile()) {
            return;
        }
        File main = new File(filesDir, "tokens.sqlite");
        int merged = 0, skipped = 0;
        try {
            if (!main.isFile()) {
                // 主库不存在：直接用采集库改名替代（服务端也能接受空库+采集数据）
                copyFile(harvest, main);
                // 清理可能残留的 WAL 伴生文件（与主库不一致会损坏）
                new File(filesDir, "tokens.sqlite-wal").delete();
                new File(filesDir, "tokens.sqlite-shm").delete();
                // harvest 里的 WAL 伴生文件不影响：改名主库后首次打开自动恢复
                new File(filesDir, "tokens.harvest.sqlite-wal").renameTo(new File(filesDir, "tokens.sqlite-wal"));
                new File(filesDir, "tokens.harvest.sqlite-shm").renameTo(new File(filesDir, "tokens.sqlite-shm"));
                LogStore.get().log("APP", "主 token 库不存在，已用采集库创建（" + harvest.length() + " 字节）");
                harvest.delete();
                return;
            }
            try (android.database.sqlite.SQLiteDatabase src =
                         android.database.sqlite.SQLiteDatabase.openDatabase(
                                 harvest.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                 android.database.sqlite.SQLiteDatabase dst =
                         android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(main, null)) {
                dst.execSQL("CREATE TABLE IF NOT EXISTS tokens (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "token TEXT NOT NULL, " +
                        "batch INTEGER NOT NULL)");
                android.database.Cursor c = src.rawQuery(
                        "SELECT token, batch FROM tokens ORDER BY id", null);
                dst.beginTransaction();
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    while (c.moveToNext()) {
                        String token = c.getString(0);
                        long batch = c.getLong(1);
                        android.database.Cursor dup = dst.rawQuery(
                                "SELECT 1 FROM tokens WHERE token = ? LIMIT 1", new String[]{token});
                        boolean exists = dup.moveToFirst();
                        dup.close();
                        if (exists) {
                            skipped++;
                            continue;
                        }
                        cv.clear();
                        cv.put("token", token);
                        cv.put("batch", batch);
                        dst.insert("tokens", null, cv);
                        merged++;
                    }
                    dst.setTransactionSuccessful();
                } finally {
                    dst.endTransaction();
                }
                c.close();
            }
            harvest.delete();
            new File(filesDir, "tokens.harvest.sqlite-wal").delete();
            new File(filesDir, "tokens.harvest.sqlite-shm").delete();
            if (merged > 0 || skipped > 0) {
                LogStore.get().log("APP", "采集合并完成：新增 " + merged + "，重复跳过 " + skipped);
            }
        } catch (Throwable t) {
            LogStore.get().log("APP", "采集合并失败（保留采集文件）: " + t);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toString("UTF-8");
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zaiapi:server");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(12 * 60 * 60 * 1000L);
        } catch (Throwable t) {
            LogStore.get().log("APP", "WakeLock 获取失败: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        Process p = process;
        if (p != null) {
            p.destroy();
            process = null;
        }
        state = State.STOPPED;
        releaseWakeLock();
        super.onDestroy();
    }
}
