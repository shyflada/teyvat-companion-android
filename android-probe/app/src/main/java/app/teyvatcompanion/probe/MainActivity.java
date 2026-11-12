package app.teyvatcompanion.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 1;
    private static final int NOTIFICATION_REQUEST = 2;
    private static final int BACKGROUND = 0xff15232d;
    private static final int PANEL = 0xff20323e;
    private static final int OUTLINE = 0xff3e5b6b;
    private static final int INK = 0xffe7eef4;
    private static final int MUTED = 0xffb0c5d3;
    private static final int ACCENT = 0xff83d7ba;
    private static final int[] REGION_IDS = {0, 1, 1001, 2, 3, 4, 8, 11, 13, 16};
    private static final int[] REGION_LABELS = {R.string.region_auto, R.string.region_mondstadt, R.string.region_dragonspine,
            R.string.region_liyue, R.string.region_inazuma, R.string.region_sumeru, R.string.region_fontaine,
            R.string.region_natlan, R.string.region_nodkrai, R.string.region_snezhnaya};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean navigationMode = NativeNavigation.available();
    private boolean requesting;
    private boolean notificationAsked;
    private String message = "";
    private TextView report;
    private TextView target;
    private TextView distance;
    private Button allowOverlay;
    private Button start;
    private Button confirm;
    private Button region;
    private Button next;
    private Button map;
    private LinearLayout targetPanel;
    private String shownTargetId = "";
    private String shownMapUrl = "";

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(NavigationPreferences.localized(base));
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateView();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        if (savedState != null) {
            requesting = savedState.getBoolean("requesting");
            notificationAsked = savedState.getBoolean("notificationAsked");
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets padding = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(scroll);
        if (navigationMode && Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> moveTaskToBack(true));
        }
        buildScreen(content);
        updateView();
    }

    private void buildScreen(LinearLayout content) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.teyvat_companion);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = label("Teyvat Companion", 21, INK);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(10), 0, dp(8), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button gear = button("\u2699\ufe0e", false, null);
        gear.setContentDescription(getString(R.string.settings));
        gear.setTextSize(21);
        gear.setPadding(0, 0, 0, 0);
        gear.setOnClickListener(view -> showSettings(gear));
        header.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));

        if (navigationMode) {
            add(content, label(getString(R.string.region), 13, MUTED), 24);
            region = button("", false, this::chooseRegion);
            region.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            add(content, region, 6);
        }
        allowOverlay = button(getString(R.string.allow_overlay), false, this::openOverlayPermission);
        add(content, allowOverlay, 12);
        start = button(getString(R.string.start), true, () -> {
            message = "";
            if (CaptureService.isActive()) { dispatch(CaptureService.STOP, ""); }
            else { requestCapture(); }
        });
        add(content, start, navigationMode ? 12 : 24);
        report = label("", 15, MUTED);
        report.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, report, 14);

        if (navigationMode) {
            add(content, button(getString(R.string.sync_title), false, () -> startActivity(
                    new Intent(this, HoYoLabActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))), 10);
            targetPanel = new LinearLayout(this);
            targetPanel.setOrientation(LinearLayout.VERTICAL);
            targetPanel.setPadding(dp(12), dp(10), dp(12), dp(12));
            targetPanel.setBackground(surface(0xff1c2d39, 12, 0));
            LinearLayout details = new LinearLayout(this);
            details.setGravity(Gravity.CENTER_VERTICAL);
            targetPanel.addView(details);
            target = label("", 15, INK);
            target.setTypeface(null, Typeface.BOLD);
            details.addView(target, new LinearLayout.LayoutParams(0, -2, 1));
            distance = label("", 18, ACCENT);
            details.addView(distance);
            LinearLayout actions = new LinearLayout(this);
            next = button(getString(R.string.next_chest), false, () -> dispatch(CaptureService.NEXT, shownTargetId));
            map = button(getString(R.string.on_map), false, () -> openMap(shownMapUrl));
            actions.addView(next, new LinearLayout.LayoutParams(0, -2, 1.45f));
            LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(0, -2, 1);
            mapParams.setMarginStart(dp(8));
            actions.addView(map, mapParams);
            add(targetPanel, actions, 10);
            confirm = button(getString(R.string.confirm_chest), false, () -> dispatch(CaptureService.CONFIRM, shownTargetId));
            add(targetPanel, confirm, 8);
            add(content, targetPanel, 14);
        }
    }

    private void openOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void dispatch(String action, String targetId) {
        if (CaptureService.isActive()) {
            startService(new Intent(this, CaptureService.class).setAction(action).putExtra(CaptureService.TARGET, targetId));
        }
    }

    private void openMap(String address) {
        if (address == null || address.isEmpty()) { return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
        catch (RuntimeException error) { message = getString(R.string.browser_error); updateView(); }
    }

    private SharedPreferences preferences() { return NavigationPreferences.get(this); }

    private int selectedRegion() {
        int id = preferences().getInt(NavigationPreferences.REGION, 0);
        for (int i = 0; i < REGION_IDS.length; ++i) { if (REGION_IDS[i] == id) { return i; } }
        return 0;
    }

    private void chooseRegion() {
        new AlertDialog.Builder(this).setTitle(R.string.region).setSingleChoiceItems(labels(REGION_LABELS), selectedRegion(), (dialog, index) -> {
            dialog.dismiss();
            preferences().edit().putInt(NavigationPreferences.REGION, REGION_IDS[index]).apply();
            changedSettings();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void changedSettings() {
        dispatch(CaptureService.SETTINGS, "");
        updateView();
    }

    private void showSettings(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        addMenu(menu, R.string.language, this::chooseLanguage);
        addMenu(menu, R.string.compass, this::compassSettings);
        if (navigationMode) {
            addMenu(menu, R.string.near_distance, () -> chooseInt(R.string.near_distance, NavigationPreferences.NEAR, 20,
                    new int[]{5, 10, 15, 20, 30, 50, 100}, R.string.distance_option));
            boolean automatic = NavigationPreferences.automaticCollection(preferences());
            menu.getMenu().add(R.string.auto_collect).setCheckable(true).setChecked(automatic).setOnMenuItemClickListener(item -> {
                preferences().edit().putBoolean(NavigationPreferences.AUTO, !automatic).apply();
                changedSettings();
                return true;
            });
            menu.getMenu().add(R.string.undo).setEnabled(CaptureService.isActive()).setOnMenuItemClickListener(item -> {
                new AlertDialog.Builder(this).setMessage(R.string.undo)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> dispatch(CaptureService.UNDO, ""))
                        .setNegativeButton(android.R.string.cancel, null).show();
                return true;
            });
        }
        addMenu(menu, R.string.help, () -> showInfo(R.string.help, getString(R.string.capture_disclosure) + "\n\n" + getString(R.string.find_position)));
        addMenu(menu, R.string.diagnostics, () -> showInfo(R.string.diagnostics, CaptureService.summary()));
        menu.show();
    }

    private void addMenu(PopupMenu menu, int label, Runnable click) {
        menu.getMenu().add(label).setOnMenuItemClickListener(item -> { click.run(); return true; });
    }

    private void showInfo(int title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show();
    }

    private void chooseLanguage() {
        String[] codes = {"en", "uk", "ru"};
        String current = preferences().getString(NavigationPreferences.LANGUAGE, "en");
        int index = current.equals("uk") ? 1 : current.equals("ru") ? 2 : 0;
        new AlertDialog.Builder(this).setTitle(R.string.language).setSingleChoiceItems(new String[]{"English", "Українська", "Русский"}, index, (dialog, chosen) -> {
            dialog.dismiss();
            if (index != chosen) {
                preferences().edit().putString(NavigationPreferences.LANGUAGE, codes[chosen]).apply();
                changedSettings();
                recreate();
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void compassSettings() {
        String overlay = (preferences().getBoolean(NavigationPreferences.OVERLAY, true) ? "✓  " : "     ") + getString(R.string.compass);
        String[] options = {overlay, getString(R.string.size), getString(R.string.opacity), getString(R.string.placement), getString(R.string.permission_settings)};
        new AlertDialog.Builder(this).setTitle(R.string.compass).setItems(options, (dialog, index) -> {
            if (index == 0) {
                preferences().edit().putBoolean(NavigationPreferences.OVERLAY, !preferences().getBoolean(NavigationPreferences.OVERLAY, true)).apply();
                changedSettings();
            } else if (index == 1) { chooseInt(R.string.size, NavigationPreferences.SIZE, 160, new int[]{128, 160, 192}, R.string.pixels_option); }
            else if (index == 2) { chooseInt(R.string.opacity, NavigationPreferences.OPACITY, 65, new int[]{50, 65, 80}, 0); }
            else if (index == 3) { chooseInt(R.string.placement, NavigationPreferences.POSITION, 0, new int[]{0, 1, 2}, -1); }
            else { openOverlayPermission(); }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseInt(int title, String key, int fallback, int[] values, int format) {
        String[] names = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; ++i) {
            if (values[i] == preferences().getInt(key, fallback)) { checked = i; }
            names[i] = format == -1 ? getString(new int[]{R.string.center, R.string.left, R.string.right}[i])
                    : format == 0 ? values[i] + "%" : getString(format, values[i]);
        }
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names, checked, (dialog, index) -> {
            dialog.dismiss();
            preferences().edit().putInt(key, values[index]).apply();
            changedSettings();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private String[] labels(int[] ids) {
        String[] result = new String[ids.length];
        for (int i = 0; i < ids.length; ++i) { result[i] = getString(ids[i]); }
        return result;
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (!navigationMode || !moveTaskToBack(true)) {
            super.onBackPressed();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("requesting", requesting);
        state.putBoolean("notificationAsked", notificationAsked);
        super.onSaveInstanceState(state);
    }

    private void requestCapture() {
        if (!preferences().getBoolean("capture_disclosed", false)) {
            new AlertDialog.Builder(this).setTitle(R.string.capture_title).setMessage(R.string.capture_disclosure)
                    .setPositiveButton(R.string.capture_continue, (dialog, button) -> {
                        preferences().edit().putBoolean("capture_disclosed", true).apply();
                        requestCapturePermission();
                    }).setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        requestCapturePermission();
    }

    private void requestCapturePermission() {
        requesting = true;
        message = "";
        updateView();
        if (Build.VERSION.SDK_INT >= 33 && !notificationAsked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else {
            openCapturePicker();
        }
    }

    private void openCapturePicker() {
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
        } catch (RuntimeException error) {
            requesting = false;
            message = getString(R.string.sharing_error);
            updateView();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATION_REQUEST) {
            openCapturePicker();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE_REQUEST) {
            return;
        }
        requesting = false;
        if (result == RESULT_OK && data != null) {
            try {
                startForegroundService(new Intent(this, CaptureService.class)
                        .putExtra(CaptureService.CONSENT, data));
            } catch (RuntimeException error) {
                message = getString(R.string.start_error);
            }
        } else {
            message = getString(R.string.sharing_cancelled);
        }
        updateView();
    }

    private void updateView() {
        boolean active = CaptureService.isActive();
        boolean overlayAllowed = Settings.canDrawOverlays(this);
        boolean overlayEnabled = !navigationMode || preferences().getBoolean(NavigationPreferences.OVERLAY, true);
        allowOverlay.setVisibility(overlayEnabled && !overlayAllowed ? View.VISIBLE : View.GONE);
        start.setEnabled(!requesting);
        setText(start, navigationMode ? getString(active ? R.string.stop : R.string.start) : active ? "Stop test" : "Start capture test");
        if (!navigationMode) { setText(report, message.isEmpty() ? CaptureService.summary() : message); return; }
        setText(region, getString(REGION_LABELS[selectedRegion()]) + "  ▾");
        NativeNavigation.Guidance guide = CaptureService.currentGuidance();
        String status = active ? NavigationPreferences.status(this, guide) : getString(R.string.ready);
        if (!active && !"Ready to test".equals(CaptureService.currentState())) {
            status = "Navigation stopped".equals(CaptureService.currentState()) ? getString(R.string.stopped) : CaptureService.currentState();
        }
        setText(report, message.isEmpty() ? status : message);
        shownTargetId = guide == null ? "" : guide.targetId;
        shownMapUrl = guide == null ? "" : guide.mapUrl;
        targetPanel.setVisibility(active ? View.VISIBLE : View.GONE);
        setText(target, guide == null || guide.target.isEmpty() ? "" : NavigationPreferences.target(this, guide.target));
        setText(distance, guide == null || guide.distance == null ? "" : getString(R.string.distance, Math.round(guide.distance)));
        next.setEnabled(!shownTargetId.isEmpty());
        confirm.setEnabled(!shownTargetId.isEmpty());
        map.setEnabled(!shownMapUrl.isEmpty());
    }

    private static void setText(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) { view.setText(text); }
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String title, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackgroundTintList(null);
        GradientDrawable background = surface(primary ? ACCENT : PANEL, 9, primary ? 0 : OUTLINE);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2996ead0), background, null));
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? 0xff3f5852 : 0xff8298a5, primary ? 0xff10252a : INK}));
        if (click != null) { button.setOnClickListener(view -> click.run()); }
        return button;
    }

    private GradientDrawable surface(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) { drawable.setStroke(dp(1), stroke); }
        return drawable;
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
