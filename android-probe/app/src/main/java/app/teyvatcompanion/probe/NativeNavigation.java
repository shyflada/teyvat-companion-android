package app.teyvatcompanion.probe;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

final class NativeNavigation implements AutoCloseable {
    private static final boolean AVAILABLE = load();
    private static final long MAX_POSITION_AGE_MS = 1_500;

    private static boolean load() {
        for (String library : new String[]{"teyvat_android", "teyvat_android_arm64-v8a"}) {
            try {
                System.loadLibrary(library);
                return true;
            } catch (LinkageError | SecurityException unavailable) {
                // The standalone probe ships without a native navigation library.
            }
        }
        return false;
    }

    static boolean available() {
        return AVAILABLE;
    }

    // Serializing all sessions also protects a restart while the previous native call is ending.
    private static final class Worker {
        private static final Handler HANDLER = start();

        private static Handler start() {
            HandlerThread thread = new HandlerThread("Teyvat navigation");
            thread.start();
            return new Handler(thread.getLooper());
        }
    }

    static final class Guidance {
        final String state;
        final String status;
        final Double bearing;
        final Double distance;
        final String target;
        final String targetId;
        final String mapUrl;
        final boolean near;
        final boolean confirmed;
        final int automaticChanges;
        final long positionExpiresAt;

        private Guidance(String state, String status, Double bearing, Double distance, String target, String targetId, String mapUrl, boolean near,
                boolean confirmed, int automaticChanges, long positionExpiresAt) {
            this.state = state;
            this.status = status;
            this.bearing = bearing;
            this.distance = distance;
            this.target = target;
            this.targetId = targetId;
            this.mapUrl = mapUrl;
            this.near = near;
            this.confirmed = confirmed;
            this.automaticChanges = automaticChanges;
            this.positionExpiresAt = positionExpiresAt;
        }

        static Guidance status(String status) {
            return new Guidance("", status, null, null, "", "", "", false, false, 0, 0);
        }

        Guidance withoutPosition(String status) {
            return new Guidance("", status, null, null, target, targetId, mapUrl, false, false, automaticChanges, 0);
        }

        boolean hasPosition() {
            return bearing != null || distance != null || near;
        }

        private static Guidance parse(String result, long frameTimeMs) {
            if (result != null) {
                try {
                    JSONObject data = new JSONObject(result);
                    double bearing = data.optDouble("bearing", Double.NaN);
                    double distance = data.optDouble("distance", Double.NaN);
                    long positionTimeMs = data.optLong("positionTimeMs", frameTimeMs);
                    long verifiedAt = positionTimeMs >= 0 ? Math.min(frameTimeMs, positionTimeMs) : frameTimeMs;
                    return new Guidance(data.optString("state"), data.optString("status", "Finding position"),
                            Double.isFinite(bearing) ? bearing : null,
                            Double.isFinite(distance) && distance >= 0 ? distance : null,
                            data.optString("target", ""), data.optString("targetId", ""), data.optString("mapUrl", ""), data.optBoolean("near"),
                            data.optBoolean("confirmed"), data.optInt("automaticChanges"), verifiedAt + MAX_POSITION_AGE_MS);
                } catch (JSONException invalidResult) {
                    return status("Navigation returned an invalid result");
                }
            }
            return status("Navigation returned no result");
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<Guidance> listener;
    private volatile boolean closed;
    private volatile long handle; // Lifetime is owned by the worker.
    private boolean ready;
    private boolean busy;
    private boolean actionPending;
    private volatile int revision;
    private ByteBuffer frameBuffer;

    NativeNavigation(String filesDirectory, int region, int near, boolean automatic, Consumer<Guidance> listener) {
        this.listener = listener;
        Worker.HANDLER.post(() -> {
            if (closed) {
                return;
            }
            String status;
            try {
                handle = create(filesDirectory);
                if (handle != 0) { configure(handle, region, near, automatic); }
                status = handle != 0 ? "Finding position" : "Navigation could not start";
            } catch (RuntimeException error) {
                status = error.getMessage() == null ? "Navigation could not start" : error.getMessage();
            } catch (LinkageError error) {
                status = "Navigation library could not initialize";
            }
            boolean created = handle != 0;
            Guidance startup = Guidance.status(status);
            main.post(() -> {
                if (!closed) {
                    ready = created;
                    listener.accept(startup);
                }
            });
        });
    }

    boolean canAcceptFrame() {
        return !closed && ready && !busy && !actionPending;
    }

    boolean submit(ByteBuffer pixels, int width, int height, int rowStride, long timeMs) {
        if (!canAcceptFrame()) {
            return false;
        }
        int length = (height - 1) * rowStride + width * 4;
        int paddedLength = height * rowStride;
        if (rowStride < width * 4 || length <= 0 || pixels.remaining() < length) {
            return false;
        }
        if (frameBuffer == null || frameBuffer.capacity() < paddedLength) {
            frameBuffer = ByteBuffer.allocateDirect(paddedLength);
        }
        ByteBuffer input = pixels.duplicate();
        input.limit(input.position() + length);
        frameBuffer.clear();
        frameBuffer.put(input);
        // ImageReader may omit the final row's padding. Native QImage expects the full stride.
        while (frameBuffer.position() < paddedLength) {
            frameBuffer.put((byte) 0);
        }
        frameBuffer.flip();
        ByteBuffer frame = frameBuffer.slice();
        int currentRevision = revision;
        busy = Worker.HANDLER.post(() -> {
            Guidance guidance = null;
            if (!closed && revision == currentRevision) {
                try {
                    guidance = Guidance.parse(observe(handle, frame, width, height, rowStride, false, timeMs), timeMs);
                } catch (LinkageError | RuntimeException error) {
                    guidance = Guidance.status("Could not process the captured frame");
                }
            }
            complete(guidance, currentRevision);
        });
        return busy;
    }

    boolean requestAction(int action, String targetId) {
        if (closed || actionPending || !ready) {
            return false;
        }
        int currentRevision = revision;
        actionPending = Worker.HANDLER.post(() -> {
            if (!closed) {
                Guidance guidance;
                try {
                    guidance = Guidance.parse(targetAction(handle, action, targetId), android.os.SystemClock.elapsedRealtime());
                } catch (LinkageError | RuntimeException error) {
                    guidance = Guidance.status(error.getMessage() == null ? "Could not update the target" : error.getMessage());
                }
                Guidance result = guidance;
                main.post(() -> {
                    actionPending = false;
                    if (!closed && revision == currentRevision) { listener.accept(result); }
                });
            }
        });
        return actionPending;
    }

    void applyOptions(int region, int near, boolean automatic) {
        invalidateFrame();
        Worker.HANDLER.post(() -> {
            if (!closed && handle != 0) {
                try { configure(handle, region, near, automatic); }
                catch (RuntimeException error) {
                    main.post(() -> { if (!closed) { listener.accept(Guidance.status(error.getMessage())); } });
                }
            }
        });
    }


    private void complete(Guidance guidance, int currentRevision) {
        main.post(() -> {
            if (!closed) {
                busy = false;
                if (guidance != null && revision == currentRevision) {
                    listener.accept(guidance);
                }
            }
        });
    }

    void invalidateFrame() {
        revision++;
    }

    @Override public void close() {
        if (closed) {
            return;
        }
        closed = true;
        frameBuffer = null;
        long session = handle;
        if (session != 0) {
            cancel(session);
        }
        Worker.HANDLER.post(() -> {
            if (handle != 0) {
                destroy(handle);
                handle = 0;
            }
        });
    }

    private static native long create(String filesDirectory);
    private static native String observe(long handle, ByteBuffer rgba, int width, int height, int rowStride,
            boolean worldMap, long timeMs);
    private static native String targetAction(long handle, int action, String expected);
    private static native void configure(long handle, int region, int near, boolean automatic);
    private static native void cancel(long handle);
    private static native void destroy(long handle);
}
