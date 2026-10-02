# ------------------------------------------------------------------
# GLM-Free-APK R8 规则
#
# 目标：压缩体积（dex 10MB -> ~2MB / resources.arsc 1.3MB -> ~1MB），
#       同时保证功能不变。AndroidX / Material 自带 consumer rules，
#       这里只需处理本工程自身的反射与 JS 桥接点。
# ------------------------------------------------------------------

# 崩溃日志可读性：保留源码文件名与行号（不影响体积多少）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- WebView 采集器的 JS 桥 ----
# TokenHarvestActivity 通过 addJavascriptInterface("AndroidBridge", Bridge)
# 注入页面，Android 运行时用反射逐个查找 @JavascriptInterface 方法。
# 一旦被混淆改名，chat.z.ai 的 token 采集会静默失效，因此整类保留。
-keep class com.zaiapi.android.TokenHarvestActivity$Bridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 服务进程读取的环境变量 / 端口等均为字符串常量，无反射依赖 ----
# Manifest 声明的 Activity / Service 由 AGP 自动生成 keep 规则，无需重复。

# 调试期可临时注释下一行以保留全部类名（仅本地排查用，会明显增大体积）
#-keep class com.zaiapi.android.** { *; }