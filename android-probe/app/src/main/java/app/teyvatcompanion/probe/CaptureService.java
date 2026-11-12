package app.teyvatcompanion.probe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class CaptureService extends Service {
    static final String CONSENT = "screenCaptureConsent";
    static final String STOP = "app.teyvatcompanion.probe.STOP";
    static final String CONFIRM = "app.teyvatcompanion.probe.CONFIRM";
    static final String NEXT = "app.teyvatcompanion.probe.NEXT";
    static final String UNDO = "app.teyvatcompanion.probe.UNDO";
    static final String SETTINGS = "app.teyvatcompanion.probe.SETTINGS";
    static final String TARGET = "targetId";
    private static final String CHANNEL = "capture_test";
    private static final int NOTIFICATION_ID = 1;
    private static final int PROBE_MAX_SIDE = 480;
    private static final int NAVIGATION_MAX_SIDE = 1600;
    private static final long DIAGNOSTIC_INTERVAL_MS = 500;
    private static final long NAVIGATION_INTERVAL_MS = 100;

    // The service and Activity share the main thread. These totals are never written to disk.
    private static boolean active;
    private static int sampledFrames;
    private static int nonblankFrames;
    private static String state = "Ready to test";
    private static String overlayState = "Not started";
    private static NativeNavigation.Guidance guidance;
    private static boolean accountPageVisible;
    private static CaptureService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean navigationMode = NativeNavigation.available();
    private Context languageContext;
    private final Runnable expireGuidance = () -> {
        if (active && guidance != null) {
            showGuidance(guidance.withoutPosition(languageContext.getString(R.string.update_position)));
        }
    };
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private Context windowContext;
    private WindowManager windows;
    private TextView overlay;
    private Button confirmButton;
    private CompassPointer pointer;
    private NativeNavigation navigation;
    private long nextDiagnosticTime;
    private long nextNavigationTime;
    private long lastActionTime = -1_000;
    private int shownAutomaticChanges;
    private String notifiedTarget = "";
    private boolean contentVisible = true;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (active) {
                finishTest("Android stopped sharing");
            }
        }

        @Override public void onCapturedContentResize(int width, int height) {
            resizeCapture(width, height);
        }

        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            if (contentVisible == visible) {
                return;
            }
            contentVisible = visible;
            if (navigation != null) {
                nextNavigationTime = 0;
                if (!visible) {
                    navigation.invalidateFrame();
                    if (guidance.hasPosition()) {
                        showGuidance(guidance.withoutPosition(languageContext.getString(R.string.waiting_game)));
                    }
                }
            }
            if (overlay != null) {
                overlay.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            }
            updateConfirmButton();
        }
    };

    static boolean isActive() {
        return active;
    }

    static NativeNavigation.Guidance currentGuidance() { return active ? guidance : null; }
    static String currentState() { return state; }
    static void setAccountPageVisible(boolean visible) {
        accountPageVisible = visible;
        if (visible && instance != null && (active || instance.projection != null)) {
            instance.releaseCapture();
            instance.finishTest("Navigation stopped");
        }
    }

    static String summary() {
        String details = guidance == null ? "" : "\n" + guidance.status
                + (guidance.target.isEmpty() ? "" : "\nTarget: " + guidance.target);
        return state + details + "\nSampled frames: " + sampledFrames + "\nNonblank frames: " + nonblankFrames
                + "\nOverlay: " + overlayState;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            finishTest(navigationMode ? "Navigation stopped" : "Test stopped");
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (SETTINGS.equals(action)) {
            if (active) { applySettings(); } else { stopSelf(); }
            return START_NOT_STICKY;
        }
        if (CONFIRM.equals(action) || NEXT.equals(action) || UNDO.equals(action)) {
            requestTargetAction(CONFIRM.equals(action) ? 1 : NEXT.equals(action) ? 2 : 3, intent.getStringExtra(TARGET));
            if (!active) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (active) {
            return START_NOT_STICKY;
        }
        sampledFrames = 0;
        nonblankFrames = 0;
        state = "Starting capture";
        overlayState = "Not started";
        languageContext = NavigationPreferences.localized(this);
        shownAutomaticChanges = 0;
        notifiedTarget = "";
        guidance = navigationMode ? NativeNavigation.Guidance.status(languageContext.getString(R.string.preparing)) : null;
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            if (accountPageVisible) {
                finishTest("Navigation stopped");
                return START_NOT_STICKY;
            }
            Intent consent = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(CONSENT, Intent.class)
                    : intent.getParcelableExtra(CONSENT);
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = consent == null ? null : manager.getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) {
                finishTest("Android did not grant capture. Start a new test.");
                return START_NOT_STICKY;
            }
            projection.registerCallback(projectionCallback, handler);
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            windowContext = Build.VERSION.SDK_INT >= 30
                    ? createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    : this;
            windows = windowContext.getSystemService(WindowManager.class);
            Rect bounds = displayBounds();
            reader = newReader(bounds.width(), bounds.height());
            virtualDisplay = projection.createVirtualDisplay("Teyvat Companion capture", reader.getWidth(),
                    reader.getHeight(), densityDpi(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            if (virtualDisplay == null) {
                finishTest("Android could not start capture. Start a new test.");
                return START_NOT_STICKY;
            }
            nextDiagnosticTime = 0;
            nextNavigationTime = 0;
            active = true;
            state = "Capture active";
            if (navigationMode) {
                SharedPreferences preferences = NavigationPreferences.get(this);
                navigation = new NativeNavigation(getFilesDir().getAbsolutePath(),
                        preferences.getInt(NavigationPreferences.REGION, 0), preferences.getInt(NavigationPreferences.NEAR, 20),
                        NavigationPreferences.automaticCollection(preferences), this::showGuidance);
            }
            addOverlay();
        } catch (RuntimeException error) {
            finishTest("Capture could not start. Start a new test and approve Android’s sharing request.");
        }
        return START_NOT_STICKY;
    }

    private void requestTargetAction(int command, String targetId) {
        long now = SystemClock.elapsedRealtime();
        if (active && navigation != null && now - lastActionTime >= 500
                && (command == 3 || (guidance != null && guidance.targetId.equals(targetId)))
                && navigation.requestAction(command, targetId)) {
            lastActionTime = now;
        }
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Capture test", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_compass)
                .setContentTitle(navigationMode ? "Teyvat Companion" : "Teyvat Companion test")
                .setContentText(navigationMode ? languageContext.getString(R.string.navigation_running) : "Counting frames locally")
                .setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true);
        if (navigationMode && guidance != null && !guidance.targetId.isEmpty()) {
            builder.addAction(targetNotification(NEXT, R.string.next_chest));
            builder.addAction(targetNotification(CONFIRM, R.string.confirm_chest));
        }
        return builder.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_compass),
                navigationMode ? languageContext.getString(R.string.stop) : "Stop test", stop).build()).build();
    }

    private Notification.Action targetNotification(String action, int label) {
        String targetId = guidance.targetId;
        Intent intent = new Intent(this, CaptureService.class).setAction(action)
                .setData(Uri.fromParts("teyvat", targetId, null)).putExtra(TARGET, targetId);
        PendingIntent command = PendingIntent.getService(this, 2, intent, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_compass),
                languageContext.getString(label), command).build();
    }

    private Rect displayBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            return windows.getMaximumWindowMetrics().getBounds();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private int densityDpi() {
        return windowContext.getResources().getDisplayMetrics().densityDpi;
    }

    private ImageReader newReader(int width, int height) {
        int maxSide = navigationMode ? NAVIGATION_MAX_SIDE : PROBE_MAX_SIDE;
        double scale = Math.min(1.0, (double) maxSide / Math.max(width, height));
        ImageReader source = ImageReader.newInstance(Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)), PixelFormat.RGBA_8888, 2);
        source.setOnImageAvailableListener(this::sample, handler);
        return source;
    }

    private void resizeCapture(int width, int height) {
        if (virtualDisplay == null || width <= 0 || height <= 0) {
            return;
        }
        ImageReader next = null;
        try {
            next = newReader(width, height);
            if (next.getWidth() == reader.getWidth() && next.getHeight() == reader.getHeight()) {
                next.close();
                return;
            }
            virtualDisplay.resize(next.getWidth(), next.getHeight(), densityDpi());
            virtualDisplay.setSurface(next.getSurface());
            reader.close();
            reader = next;
            if (navigation != null) {
                nextNavigationTime = 0;
                navigation.invalidateFrame();
                if (guidance.hasPosition() || !guidance.target.isEmpty()) {
                    showGuidance(NativeNavigation.Guidance.status("Finding position"));
                }
            }
        } catch (RuntimeException error) {
            if (next != null) {
                next.close();
            }
            finishTest("Capture could not resize. Start a new test.");
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (Build.VERSION.SDK_INT < 34 && virtualDisplay != null) {
            Rect bounds = displayBounds();
            resizeCapture(bounds.width(), bounds.height());
        }
    }

    private void sample(ImageReader source) {
        if (source != reader) {
            return;
        }
        try (Image frame = source.acquireLatestImage()) {
            long now = SystemClock.elapsedRealtime();
            if (frame == null || !active || !contentVisible || accountPageVisible) {
                return;
            }
            boolean diagnosticFrame = now >= nextDiagnosticTime;
            boolean navigationFrame = navigation != null && now >= nextNavigationTime && navigation.canAcceptFrame()
                    && frame.getWidth() >= 1170 && frame.getWidth() > frame.getHeight();
            if (!diagnosticFrame && !navigationFrame) {
                return;
            }
            Image.Plane[] planes = frame.getPlanes();
            if (planes.length == 0) {
                return;
            }
            Image.Plane pixels = planes[0];
            ByteBuffer buffer = pixels.getBuffer();
            if (navigationFrame && pixels.getPixelStride() == 4
                    && navigation.submit(buffer, frame.getWidth(), frame.getHeight(), pixels.getRowStride(), now)) {
                nextNavigationTime = now + NAVIGATION_INTERVAL_MS;
            }
            if (diagnosticFrame) {
                nextDiagnosticTime = now + DIAGNOSTIC_INTERVAL_MS;
                sampledFrames++;
                if (isNonblank(buffer, pixels.getRowStride(), pixels.getPixelStride(), frame.getWidth(), frame.getHeight())) {
                    nonblankFrames++;
                }
                if (!navigationMode) {
                    updateOverlayText();
                }
            }
        } catch (RuntimeException error) {
            finishTest("Android’s frame stream ended. Start a new test.");
        }
    }

    static boolean isNonblank(ByteBuffer pixels, int rowStride, int pixelStride, int width, int height) {
        int bright = 0;
        int samples = 0;
        int darkest = 255;
        int brightest = 0;
        for (int row = 1; row <= 12; row++) {
            int y = row * height / 13;
            for (int column = 1; column <= 16; column++) {
                int x = column * width / 17;
                int offset = pixels.position() + y * rowStride + x * pixelStride;
                if (offset + 2 >= pixels.limit()) {
                    continue;
                }
                int luminance = (77 * (pixels.get(offset) & 255) + 150 * (pixels.get(offset + 1) & 255)
                        + 29 * (pixels.get(offset + 2) & 255)) >> 8;
                darkest = Math.min(darkest, luminance);
                brightest = Math.max(brightest, luminance);
                bright += luminance > 16 ? 1 : 0;
                samples++;
            }
        }
        return bright >= Math.max(2, samples / 10) && brightest - darkest >= 16;
    }

    private void addOverlay() {
        SharedPreferences preferences = NavigationPreferences.get(this);
        if (navigationMode && !preferences.getBoolean(NavigationPreferences.OVERLAY, true)) {
            overlayState = "Disabled";
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayState = "Permission not granted";
            return;
        }
        try {
            int width = navigationMode ? preferences.getInt(NavigationPreferences.SIZE, 160) : 124;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(width),
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            int placement = navigationMode ? preferences.getInt(NavigationPreferences.POSITION, 0) : 0;
            params.gravity = Gravity.TOP | (placement == 1 ? Gravity.LEFT : placement == 2 ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL);
            params.x = placement == 0 ? 0 : dp(18);
            params.y = dp(28);
            params.alpha = navigationMode ? preferences.getInt(NavigationPreferences.OPACITY, 65) / 100f : 0.65f;
            if (Build.VERSION.SDK_INT >= 31) {
                float limit = getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch();
                params.alpha = Math.min(params.alpha, limit);
            }
            if (params.alpha <= 0) {
                overlayState = "Android disallows a visible tap-through overlay";
                return;
            }
            TextView label = new TextView(windowContext);
            label.setTextColor(Color.WHITE);
            label.setTextSize(navigationMode ? 12 : 11);
            label.setPadding(dp(8), dp(4), dp(8), dp(4));
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            if (navigationMode) {
                pointer = new CompassPointer();
                pointer.setBounds(0, 0, dp(30), dp(30));
                label.setCompoundDrawables(pointer, null, null, null);
            } else {
                label.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stat_compass, 0, 0, 0);
            }
            label.setCompoundDrawablePadding(dp(6));
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(18, 30, 39));
            background.setCornerRadius(dp(10));
            label.setBackground(background);
            label.setVisibility(contentVisible ? View.VISIBLE : View.INVISIBLE);
            windows.addView(label, params);
            overlay = label;
            overlayState = "Requested on screen";
            updateOverlayText();
            if (navigationMode) { addConfirmButton(params, placement); }
        } catch (RuntimeException error) {
            overlayState = "Android refused the overlay";
        }
    }

    private void addConfirmButton(WindowManager.LayoutParams compass, int placement) {
        int size = dp(48);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH, PixelFormat.TRANSLUCENT);
        params.gravity = compass.gravity;
        params.x = (placement == 0 ? (compass.width + size) / 2 : compass.x + compass.width) + dp(6);
        params.y = compass.y;
        params.alpha = compass.alpha;

        Button button = new Button(windowContext);
        String label = languageContext.getString(R.string.confirm_chest);
        button.setText("✓");
        button.setTextSize(22);
        button.setTextColor(0xff83d7ba);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setPadding(0, 0, 0, 0);
        button.setFilterTouchesWhenObscured(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(18, 30, 39));
        background.setCornerRadius(dp(10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), background, null));
        button.setVisibility(View.GONE);
        button.setOnClickListener(view -> {
            if (view.isShown() && guidance != null && guidance.hasPosition()) {
                requestTargetAction(1, guidance.targetId);
            }
        });
        try {
            windows.addView(button, params);
            confirmButton = button;
            updateConfirmButton();
        } catch (RuntimeException error) {
            overlayState = "Compass visible; Android refused the confirmation button";
        }
    }

    private void updateConfirmButton() {
        if (confirmButton != null) {
            boolean ready = active && contentVisible && guidance != null && guidance.hasPosition() && !guidance.targetId.isEmpty();
            confirmButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        }
    }

    private void removeOverlay() {
        if (confirmButton != null) {
            try { windows.removeViewImmediate(confirmButton); } catch (IllegalArgumentException detached) { /* Permission revoked. */ }
            confirmButton = null;
        }
        if (overlay != null) {
            try { windows.removeViewImmediate(overlay); } catch (IllegalArgumentException detached) { /* Permission revoked. */ }
            overlay = null;
            pointer = null;
        }
    }

    private void applySettings() {
        languageContext = NavigationPreferences.localized(this);
        SharedPreferences preferences = NavigationPreferences.get(this);
        if (navigation != null) {
            navigation.applyOptions(preferences.getInt(NavigationPreferences.REGION, 0),
                    preferences.getInt(NavigationPreferences.NEAR, 20), NavigationPreferences.automaticCollection(preferences));
            showGuidance(NativeNavigation.Guidance.status(languageContext.getString(R.string.find_position)));
        }
        removeOverlay();
        addOverlay();
    }

    private int dp(int value) {
        return Math.round(value * windowContext.getResources().getDisplayMetrics().density);
    }

    private void updateOverlayText() {
        if (overlay == null) {
            return;
        }
        String caption;
        if (guidance == null) {
            caption = "TEST\n" + nonblankFrames + " / " + sampledFrames + " frames";
        } else {
            pointer.setBearing(guidance.bearing);
            String title = guidance.near ? languageContext.getString(R.string.near_chest)
                    : guidance.target.isEmpty() ? NavigationPreferences.status(languageContext, guidance)
                    : NavigationPreferences.target(languageContext, guidance.target);
            caption = guidance.distance == null ? title : title + "\n" + languageContext.getString(R.string.distance, Math.round(guidance.distance));
        }
        if (!TextUtils.equals(overlay.getText(), caption)) {
            overlay.setText(caption);
        }
    }

    private void showGuidance(NativeNavigation.Guidance next) {
        if (active) {
            if (next.automaticChanges > shownAutomaticChanges) {
                shownAutomaticChanges = next.automaticChanges;
                Toast.makeText(languageContext, R.string.automatically_saved, Toast.LENGTH_SHORT).show();
            } else if (next.confirmed) {
                Toast.makeText(languageContext, R.string.confirm_sync_pending, Toast.LENGTH_SHORT).show();
            }
        }
        handler.removeCallbacks(expireGuidance);
        if (!contentVisible) {
            next = next.withoutPosition(next.status);
        } else if (next.hasPosition()) {
            long freshFor = next.positionExpiresAt - SystemClock.elapsedRealtime();
            if (freshFor > 0) {
                handler.postDelayed(expireGuidance, freshFor);
            } else {
                next = next.withoutPosition(languageContext.getString(R.string.update_position));
            }
        }
        guidance = next;
        if (active && navigationMode && !notifiedTarget.equals(next.targetId)) {
            if (confirmButton != null) {
                confirmButton.cancelPendingInputEvents();
                confirmButton.setPressed(false);
            }
            notifiedTarget = next.targetId;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager.areNotificationsEnabled()) { manager.notify(NOTIFICATION_ID, notification()); }
        }
        updateOverlayText();
        updateConfirmButton();
    }

    private void finishTest(String message) {
        active = false;
        state = message;
        updateConfirmButton();
        stopSelf();
    }

    private void releaseCapture() {
        active = false;
        handler.removeCallbacks(expireGuidance);
        if (navigation != null) {
            navigation.close();
            navigation = null;
        }
        if (guidance != null) {
            guidance = null;
        }
        removeOverlay();
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
    }

    @Override public void onDestroy() {
        if (instance == this) { instance = null; }
        releaseCapture();
        if (state.equals("Capture active")) {
            state = "Test stopped";
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private static final class CompassPointer extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private Double bearing;
        private int alpha = 255;

        CompassPointer() {
            arrow.moveTo(0, -0.7f);
            arrow.lineTo(0.42f, 0.53f);
            arrow.lineTo(0, 0.29f);
            arrow.lineTo(-0.42f, 0.53f);
            arrow.close();
        }

        void setBearing(Double bearing) {
            if (!Objects.equals(this.bearing, bearing)) {
                this.bearing = bearing;
                invalidateSelf();
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float radius = Math.min(bounds.width(), bounds.height()) * 0.5f;
            canvas.save();
            canvas.translate(bounds.exactCenterX(), bounds.exactCenterY());
            canvas.scale(radius, radius);
            paint.setColor(0xffb8d0db);
            paint.setAlpha(alpha);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.08f);
            canvas.drawCircle(0, 0, 0.91f, paint);
            paint.setStyle(Paint.Style.FILL);
            if (bearing == null) {
                canvas.drawCircle(0, 0, 0.12f, paint);
            } else {
                canvas.rotate((float) (bearing % 360));
                paint.setColor(0xff83d7ba);
                paint.setAlpha(alpha);
                canvas.drawPath(arrow, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
