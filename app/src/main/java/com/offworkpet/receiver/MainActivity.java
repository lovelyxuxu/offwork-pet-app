package com.offworkpet.receiver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextWatcher;
import android.text.Editable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int INK = 0xFF302D3D, GREEN = 0xFF7560B5, MUTED = 0xFF78717F;
    private com.offworkpet.receiver.Settings settings;
    private Notifications notifications;
    private EditText code;
    private TextView status, badge, last;
    private Button toggle;
    private boolean pendingStart;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new com.offworkpet.receiver.Settings(this); notifications = new Notifications(this);
        boolean compact = getResources().getConfiguration().screenHeightDp < 700;
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFFAF9F6); page.setFocusableInTouchMode(true);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            page.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(18) + insets.getSystemWindowInsetTop(),
                    dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.addView(page); setContentView(scroll);

        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_launcher_foreground);
        mark.setBackground(shape(0xFFF0EBFB, 14)); header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL); brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(text("搭子提醒", 18, INK));
        TextView wordmark = text("OFFWORK PAL", 9, MUTED); wordmark.setLetterSpacing(.14f); brand.addView(wordmark);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        badge = text("手机接收端", 11, GREEN); badge.setPadding(dp(10), dp(7), dp(10), dp(7));
        badge.setBackground(shape(0xFFEEE9F8, 20));
        badge.setOnClickListener(v -> openDiagnostics()); badge.setContentDescription("查看连接诊断"); header.addView(badge); add(page, header, 0);

        LinearLayout hero = row(); hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
        TextView headline = text("集会提醒，\n手机接收。", compact ? 26 : 28, INK); headline.setLineSpacing(dp(6), 1); headline.setTypeface(null, Typeface.BOLD);
        copy.addView(headline); add(copy, text("接收电脑端信号，\n通过通知和震动提示。", 13, MUTED), 12);
        hero.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        hero.addView(new SignalArtwork(this), new LinearLayout.LayoutParams(dp(compact ? 128 : 138), dp(compact ? 126 : 146))); add(page, hero, compact ? 20 : 30);

        LinearLayout stateRow = row(); stateRow.setGravity(Gravity.CENTER_VERTICAL); stateRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        stateRow.setBackground(shape(0xFFF0EEE8, 14));
        TextView dot = text("●", 10, GREEN); stateRow.addView(dot, new LinearLayout.LayoutParams(dp(22), -2));
        status = text("未连接", 12, MUTED); stateRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1)); add(page, stateRow, compact ? 14 : 22);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(20), dp(compact ? 18 : 22), dp(20), dp(compact ? 18 : 22));
        GradientDrawable cardShape = shape(Color.WHITE, 24); cardShape.setStroke(dp(1), 0xFFEEEAE4); card.setBackground(cardShape); add(page, card, 14);
        add(card, text("加入集会", 21, INK), 0);
        add(card, text("输入电脑端的 8 位集会码", 12, MUTED), 6);
        code = new EditText(this); code.setSingleLine(true); code.setTextColor(INK);
        code.setHint("8 位集会码"); code.setHintTextColor(0xFFABA5B5); code.setGravity(Gravity.CENTER);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(8)});
        code.setPadding(dp(12), dp(compact ? 14 : 17), dp(12), dp(compact ? 14 : 17));
        GradientDrawable input = shape(0xFFF7F5FB, 15); input.setStroke(dp(1), 0xFFE7E0F2); code.setBackground(input);
        code.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                code.setTextSize(value.length() == 0 ? 15 : 26); code.setLetterSpacing(value.length() == 0 ? .02f : .18f);
                code.setTypeface(value.length() == 0 ? Typeface.DEFAULT : Typeface.create("monospace", Typeface.BOLD));
            }
            public void afterTextChanged(Editable value) { }
        }); code.setText(settings.code()); add(card, code, 20);
        TextView help = text("如何获取集会码", 12, GREEN); help.setPadding(0, dp(10), 0, dp(10)); help.setGravity(Gravity.END);
        help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("获取集会码")
                .setMessage("打开电脑端的“集会警报”面板，创建或加入集会后，复制其中的 8 位集会码。")
                .setPositiveButton("确定", null).show()); add(card, help, 0);
        toggle = button("开始接收", true); toggle.setOnClickListener(v -> {
            if (settings.enabled()) {
                settings.enabled(false); settings.status("已停止接收");
                stopService(new Intent(this, ReceiverService.class)); notifications.clearAlert(); refresh();
            } else begin();
        }); add(card, toggle, 2);
        TextView quiet = text("通知与震动提醒 · 默认无声", 11, MUTED); quiet.setGravity(Gravity.CENTER); add(card, quiet, 12);

        LinearLayout shortcuts = row();
        LinearLayout test = shortcut("测试通知", "检查通知和震动", 0xFFEEE9F8);
        test.setOnClickListener(v -> { if (notifications.allowed()) notifications.test(); else requestNotifications(false); });
        LinearLayout options = shortcut("通知设置", "管理声音与震动", 0xFFEAF0E9); options.setOnClickListener(v -> openNotificationSettings());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.rightMargin = dp(10); shortcuts.addView(test, left);
        shortcuts.addView(options, new LinearLayout.LayoutParams(0, -2, 1)); add(page, shortcuts, 16);
        last = text("", 11, MUTED); last.setGravity(Gravity.CENTER); add(page, last, 20);
        refresh();
    }
    private void begin() {
        String value = AlertProtocol.normalizeCode(code.getText().toString());
        if (!AlertProtocol.validCode(value)) { code.setError("请输入电脑端的 8 位集会码"); return; }
        code.setText(value);
        if (!notifications.allowed()) { requestNotifications(true); return; }
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(code.getWindowToken(), 0);
        code.clearFocus();
        ensureBackgroundAuthorization();
        settings.group(value, "手机"); settings.enabled(true); settings.status("正在连接…"); startReceiver(); refresh();
    }
    private void startReceiver() {
        Intent service = new Intent(this, ReceiverService.class);
        if (BuildConfig.DEBUG && getIntent().hasExtra("test_endpoint")) service.putExtra("test_endpoint", getIntent().getStringExtra("test_endpoint"));
        try { startForegroundService(service); }
        catch (RuntimeException e) { settings.enabled(false); settings.status("系统未允许接收，请再点一次开始接收"); }
    }
    private void requestNotifications(boolean startAfter) {
        pendingStart = startAfter;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !settings.prefs.getBoolean("permission_requested", false)) {
            settings.prefs.edit().putBoolean("permission_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        } else {
            new AlertDialog.Builder(this).setTitle("允许通知后才能提醒")
                    .setMessage("请在系统设置中允许“搭子提醒”的通知，并开启“集会提醒”的震动和横幅。")
                    .setPositiveButton("打开设置", (dialog, which) -> openNotificationSettings()).setNegativeButton("稍后", null).show();
        }
    }
    private void ensureBackgroundAuthorization() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (!power.isIgnoringBatteryOptimizations(getPackageName()) && !settings.prefs.getBoolean("background_requested", false)) {
            settings.prefs.edit().putBoolean("background_requested", true).apply();
            requestBackground();
        }
    }
    private void requestBackground() {
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + getPackageName())));
    }
    private void openDiagnostics() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        long lastData = settings.prefs.getLong("last_data_at", 0);
        android.net.ConnectivityManager connectivity = getSystemService(android.net.ConnectivityManager.class);
        String report = "版本：" + BuildConfig.VERSION_NAME + "\n设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\nAndroid：" + android.os.Build.VERSION.RELEASE + "\n省电豁免：" + power.isIgnoringBatteryOptimizations(getPackageName())
                + "\n联网阻止记录：" + (settings.prefs.contains("network_blocked") ? settings.prefs.getBoolean("network_blocked", false) : "暂无回调")
                + "\n后台流量限制：" + connectivity.getRestrictBackgroundStatus()
                + "\n计费网络：" + connectivity.isActiveNetworkMetered()
                + "\n服务运行：" + ReceiverService.active + "\n状态：" + settings.status()
                + "\n距上次连接数据：" + (lastData == 0 ? "暂无" : ((System.currentTimeMillis() - lastData) / 1000) + " 秒")
                + "\n\n" + settings.prefs.getString("connection_log", "暂无记录");
        TextView content = text(report, 13, INK); content.setTextIsSelectable(true); content.setPadding(dp(18), dp(12), dp(18), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("连接诊断").setView(scroll)
                .setPositiveButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("连接诊断", report));
                }).setNegativeButton("关闭", null).show();
    }
    private void openNotificationSettings() {
        boolean vibrate = settings.prefs.getBoolean("vibrate", true);
        new AlertDialog.Builder(this).setTitle("提醒设置")
                .setMultiChoiceItems(new String[]{"每条提醒震动"}, new boolean[]{vibrate},
                    (dialog, which, checked) -> settings.prefs.edit().putBoolean("vibrate", checked).apply())
                .setPositiveButton("系统通知设置", (dialog, which) -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
                .setNeutralButton("允许后台运行", (dialog, which) -> requestBackground())
                .setNegativeButton("连接诊断", (dialog, which) -> openDiagnostics()).show();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingStart) begin(); else notifications.test();
        } else if (requestCode == 7) { status.setText("未开启通知，暂不能提醒"); }
        pendingStart = false;
    }
    @Override protected void onResume() {
        super.onResume(); settings.prefs.registerOnSharedPreferenceChangeListener(changes);
        if (settings.enabled() && !ReceiverService.active) {
            if (notifications.allowed()) startReceiver();
            else { settings.enabled(false); settings.status("通知权限已关闭"); }
        }
        refresh();
        if (settings.enabled()) ensureBackgroundAuthorization();
    }
    @Override protected void onPause() { settings.prefs.unregisterOnSharedPreferenceChangeListener(changes); super.onPause(); }
    private void refresh() {
        if (code == null) return;
        boolean enabled = settings.enabled(); code.setEnabled(!enabled);
        toggle.setText(enabled ? "停止接收" : "开始接收");
        status.setText(settings.status().equals("尚未开始接收") ? "未连接" : settings.status());
        status.setTextColor(enabled && settings.status().startsWith("正在接收") ? GREEN : MUTED);
        badge.setText(!enabled ? "未开启" : (settings.status().startsWith("正在接收") ? "接收中" : "连接中"));
        String recent = settings.prefs.getString("last_alert", "");
        long at = settings.prefs.getLong("last_alert_at", 0);
        last.setText(recent.isEmpty() ? "" : "最近提醒：" + recent + " · " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(at)));
        last.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout shortcut(String title, String description, int color) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(16), dp(12), dp(16));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x187560B5), shape(color, 18), null));
        TextView heading = text(title + "  ↗", 14, INK); heading.setTypeface(null, Typeface.BOLD); card.addView(heading);
        add(card, text(description, 11, MUTED), 5); return card;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(4), 1); if (size >= 21) view.setTypeface(null, Typeface.BOLD); return view;
    }
    private Button button(String value, boolean primary) {
        Button button = new Button(this); button.setText(value); button.setTextSize(16); button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setTypeface(null, Typeface.BOLD); button.setLetterSpacing(.04f);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), shape(primary ? GREEN : 0xFFEEE9F8, 16), null));
        button.setMinHeight(dp(56)); button.setPadding(dp(16), dp(12), dp(16), dp(12)); return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(view, params);
    }
}
