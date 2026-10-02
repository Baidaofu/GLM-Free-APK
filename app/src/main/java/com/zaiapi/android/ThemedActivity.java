package com.zaiapi.android;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

/**
 * UI 基类：统一 Material 3 主题（品牌配色 / Android 12+ 跟随系统动态取色）、
 * 边到边（edge-to-edge）inset 处理，以及 Snackbar/颜色工具方法。
 */
public abstract class ThemedActivity extends AppCompatActivity {

    private static final String PREF_DYNAMIC_COLOR = "dynamic_color";

    // ---- 主题语义色 attr ----
    // colorPrimary / colorError / colorTertiary 在 material 库中不是 public API，
    // 无法通过库的 R 引用，故在应用侧声明（见 res/values/attrs.xml）；
    // 其余取库中 public 的 attr。这样 status 颜色能自动跟随深色模式与动态取色。
    protected static final int ATTR_PRIMARY = R.attr.colorPrimary;
    protected static final int ATTR_ERROR = R.attr.colorError;
    protected static final int ATTR_TERTIARY = R.attr.colorTertiary;
    protected static final int ATTR_ON_SURFACE =
            com.google.android.material.R.attr.colorOnSurface;
    protected static final int ATTR_ON_SURFACE_VARIANT =
            com.google.android.material.R.attr.colorOnSurfaceVariant;

    /** 是否启用跟随系统取色（Android 12+ 才支持）。 */
    protected boolean useDynamicColor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        return ServerService.prefs(this).getBoolean(PREF_DYNAMIC_COLOR, false);
    }

    protected void setDynamicColor(boolean enabled) {
        ServerService.prefs(this).edit().putBoolean(PREF_DYNAMIC_COLOR, enabled).apply();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(useDynamicColor() ? R.style.Theme_ZaiApi_DynamicColors : R.style.Theme_ZaiApi);
        super.onCreate(savedInstanceState);
    }

    /**
     * 允许内容绘制到系统栏之下（targetSdk 35 起 Android 强制边到边），
     * 并按 systemBars / IME inset 给根布局补内边距，保证不被状态栏与键盘遮挡。
     */
    protected void setupEdgeToEdge(View root) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** 带 M3 形变/淡入动效的展开-收起过渡。 */
    protected void animateSection(ViewGroup parent, Runnable change) {
        TransitionManager.beginDelayedTransition(parent, new AutoTransition().setDuration(240));
        change.run();
    }

    protected void snack(CharSequence text) {
        snack(text, Snackbar.LENGTH_SHORT);
    }

    protected void snack(CharSequence text, int duration) {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        Snackbar.make(root, text, duration).show();
    }

    /** 取当前主题的语义色（如 colorPrimary / colorError），自动适配深色模式与动态取色。 */
    protected int attrColor(View view, int attr) {
        return MaterialColors.getColor(view, attr);
    }
}
