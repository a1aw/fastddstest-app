package com.example.fastddstest;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Fast DDS participant plus its writer/reader for one ROS 2 {@code std_msgs/msg/String}
 * topic, and exposes it to the UI as plain callbacks.
 *
 * <p>Threading model: every native call (creating/destroying the participant, endpoints and
 * writes) happens on one single-threaded {@link ScheduledExecutorService}. Native handles are only
 * ever read or mutated on that thread, which also serialises {@code start}/{@code restart}/
 * {@code stop}/{@code send} calls coming from the main thread. Results are posted back to the main
 * thread through a {@link Handler} bound to {@link Looper#getMainLooper()}.</p>
 *
 * <p>Configuration is persisted in {@code SharedPreferences} because the discovery setup outlives
 * the process: the reachable network (and therefore the required discovery mode) changes while the
 * app is not running, and the user should not have to retype it after every kill.</p>
 */
public final class DdsManager {

    private static final String TAG = "DdsManager";

    /**
     * ROS 2 side of the topic, for reference in logs and the UI.
     */
    public static final String ROS_TOPIC = "/android_chatter";

    /**
     * DDS topic actually used on the wire.
     *
     * <p>rmw maps a ROS 2 topic to a DDS topic by dropping the leading {@code /} and adding the
     * ROS-to-DDS namespace prefix {@code rt/}, so ROS {@code /android_chatter} becomes DDS
     * {@code rt/android_chatter}. Publishing on the bare {@code /android_chatter} (or on
     * {@code android_chatter}) creates a <em>different</em> DDS topic and {@code ros2 topic echo}
     * silently receives nothing.</p>
     */
    public static final String DDS_TOPIC = "rt/android_chatter";

    /**
     * DDS type name of {@code std_msgs/msg/String}.
     *
     * <p>Fast DDS-Gen run with {@code -typeros2} emits the ROS 2 style name
     * {@code std_msgs::msg::dds_::String_}: namespace {@code <pkg>::msg::dds_} plus the trailing
     * {@code _}. Both ends must use this exact string - a different type name means the endpoints
     * never match, even when the topic matches.</p>
     */
    public static final String DDS_TYPE_NAME = "std_msgs::msg::dds_::String_";

    /** Participant name shown by {@code fastdds discovery} / Fast DDS monitor. */
    public static final String PARTICIPANT_NAME = "android_fastdds";

    /** Tag of the {@link WifiManager.MulticastLock}; required for discovery to work at all. */
    private static final String MULTICAST_LOCK_TAG = "dds";

    private static final String PREFS_NAME = "dds_config";
    private static final String KEY_DOMAIN_ID = "domainId";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SERVER_HOST = "serverHost";
    private static final String KEY_SERVER_PORT = "serverPort";
    private static final String KEY_RATE_HZ = "rateHz";

    // RTT measurement: every continuous sample is "RTT <seq> <nanos>"; the responder
    // appends " ~E" so the app can distinguish the echoed copy (the true RTT) from the
    // local loopback copy the app's own reader receives right after the write.
    static final String RTT_PREFIX = "RTT ";
    static final String RTT_ECHO_MARKER = " ~E";
    private static final int RTT_MAX_OUTSTANDING = 4096;

    private static final long PEER_POLL_PERIOD_S = 5L;

    /** Receives everything the native layer produces, always on the main thread. */
    public interface Listener {
        void onSample(String topic, String data);

        void onStatus(String status);

        void onError(String message);
    }

    /**
     * Immutable, self-validating snapshot of the participant settings. Values are clamped in the
     * constructor so an out-of-range domain id or port coming from a text field (or a hand-edited
     * preference file) can never reach native code.
     */
    public static final class Config {

        public static final int DOMAIN_ID_MIN = 0;
        public static final int DOMAIN_ID_MAX = 232;
        public static final int DEFAULT_DOMAIN_ID = 0;
        public static final int DEFAULT_SERVER_PORT = 11811;
        public static final String DEFAULT_SERVER_HOST = "";
        public static final int RATE_HZ_MAX = 1000;
        public static final int DEFAULT_RATE_HZ = 0;

        public final int domainId;
        public final int mode;
        public final String serverHost;
        public final int serverPort;

        /** Continuous publishing rate in Hz. 0 = off (manual send only). */
        public final int rateHz;

        public Config(int domainId, int mode, String serverHost, int serverPort) {
            this(domainId, mode, serverHost, serverPort, DEFAULT_RATE_HZ);
        }

        public Config(int domainId, int mode, String serverHost, int serverPort, int rateHz) {
            this.domainId = clamp(domainId);
            this.mode = (mode == DdsBridge.MODE_DISCOVERY_SERVER)
                    ? DdsBridge.MODE_DISCOVERY_SERVER
                    : DdsBridge.MODE_SIMPLE;
            this.serverHost = (serverHost == null) ? DEFAULT_SERVER_HOST : serverHost.trim();
            this.serverPort = (serverPort < 1 || serverPort > 65535) ? DEFAULT_SERVER_PORT : serverPort;
            this.rateHz = clampRate(rateHz);
        }

        public static Config defaults() {
            return new Config(DEFAULT_DOMAIN_ID, DdsBridge.MODE_SIMPLE, DEFAULT_SERVER_HOST,
                    DEFAULT_SERVER_PORT, DEFAULT_RATE_HZ);
        }

        /** Clamps a rate into {@code 0..1000} Hz; 0 disables continuous publishing. */
        private static int clampRate(int value) {
            if (value < 0) {
                return 0;
            }
            return Math.min(value, RATE_HZ_MAX);
        }

        public boolean isDiscoveryServer() {
            return mode == DdsBridge.MODE_DISCOVERY_SERVER;
        }

        public String serverEndpoint() {
            return serverHost + ":" + serverPort;
        }

        public static String modeName(int mode) {
            return mode == DdsBridge.MODE_DISCOVERY_SERVER ? "DISCOVERY_SERVER" : "SIMPLE";
        }

        /** Log/UI helper: one line describing the effective settings. */
        public String describe() {
            return "domain=" + domainId + " mode=" + modeName(mode)
                    + (isDiscoveryServer() ? " server=" + serverEndpoint() : "")
                    + (rateHz > 0 ? " rate=" + rateHz + "Hz" : "");
        }

        @NonNull
        @Override
        public String toString() {
            return describe();
        }

        /** Clamps a domain id into the DDS range {@code 0..232} (232 is the RTPS port limit). */
        private static int clamp(int value) {
            return value < DOMAIN_ID_MIN ? DOMAIN_ID_MIN : Math.min(value, DOMAIN_ID_MAX);
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dds-native");
                thread.setDaemon(true);
                return thread;
            });

    // ---- Owned by the executor thread only. ----
    private long participantHandle;
    private long writerHandle;
    private long readerHandle;
    private Config runningConfig;
    private ScheduledFuture<?> peerPoll;
    private ScheduledFuture<?> continuousPublish;

    /**
     * RTT bookkeeping. Pings are registered on the executor thread (send) and matched
     * on the DDS listener thread (echo), so the outstanding map is a ConcurrentHashMap
     * and the aggregate stats are guarded by a lock.
     */
    private static final class RttTracker {
        private final java.util.concurrent.ConcurrentHashMap<Integer, Long> outstanding =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
        private final Object lock = new Object();

        private long count;
        private long totalNs;
        private long minNs = Long.MAX_VALUE;
        private long maxNs;
        private long lastNs;
        private long lastSeq = -1;

        /** Registers a ping and returns its sequence number. */
        int register(long sentNanos) {
            final int id = (int) (seq.incrementAndGet() & 0x7fffffff);
            outstanding.put(id, sentNanos);
            if (outstanding.size() > RTT_MAX_OUTSTANDING) {
                // Drop the oldest entries when pings are piling up (no responder running).
                int dropped = 0;
                for (Integer key : outstanding.keySet()) {
                    if (outstanding.remove(key) != null) {
                        if (++dropped >= RTT_MAX_OUTSTANDING / 2) {
                            break;
                        }
                    }
                }
            }
            return id;
        }

        /** Matches an echo and updates the running stats. Returns the RTT in ns. */
        long record(int id, long receivedNanos) {
            final Long original = outstanding.remove(id);
            if (original == null) {
                return -1;
            }
            final long rtt = receivedNanos - original;
            synchronized (lock) {
                count++;
                totalNs += rtt;
                if (rtt < minNs) {
                    minNs = rtt;
                }
                if (rtt > maxNs) {
                    maxNs = rtt;
                }
                lastNs = rtt;
                lastSeq = id;
            }
            return rtt;
        }

        /** Read-only snapshot for the UI. */
        Snapshot snapshot() {
            synchronized (lock) {
                return new Snapshot(count, totalNs, minNs, maxNs, lastNs, lastSeq, outstanding.size());
            }
        }

        /** Immutable aggregate view of the RTT stats. */
        static final class Snapshot {
            final long count;
            final long totalNs;
            final long minNs;
            final long maxNs;
            final long lastNs;
            final long lastSeq;
            final int outstandingPings;

            Snapshot(long count, long totalNs, long minNs, long maxNs, long lastNs,
                    long lastSeq, int outstandingPings) {
                this.count = count;
                this.totalNs = totalNs;
                this.minNs = minNs;
                this.maxNs = maxNs;
                this.lastNs = lastNs;
                this.lastSeq = lastSeq;
                this.outstandingPings = outstandingPings;
            }

            double avgMs() {
                return (count == 0) ? 0 : (totalNs / (double) count) / 1_000_000.0;
            }

            double minMs() {
                return (count == 0) ? 0 : minNs / 1_000_000.0;
            }

            double maxMs() {
                return (count == 0) ? 0 : maxNs / 1_000_000.0;
            }

            double lastMs() {
                return (count == 0) ? 0 : lastNs / 1_000_000.0;
            }

            private static String format(double v) {
                return String.format(java.util.Locale.US, "%.2f", v);
            }

            @Override
            public String toString() {
                return "RTT n=" + count + " avg=" + format(avgMs()) + "ms min=" + format(minMs())
                        + " max=" + format(maxMs()) + " last=" + format(lastMs())
                        + " seq=" + lastSeq + " pending=" + outstandingPings;
            }
        }
    }

    private final RttTracker rttTracker = new RttTracker();

    // ---- Continuous publish rate reporting. Executor thread only. ----
    private long txCount;
    private long lastTxReportNs;

    /**
     * Mirrored for {@link #isRunning()}, which is called from the main thread. It flips to
     * {@code true} only after the participant, writer and reader all exist.
     */
    private volatile boolean running;

    /** Holding it for the participant lifetime; released in {@link #stop()}. */
    private WifiManager.MulticastLock multicastLock;

    /**
     * Called by the native reader on a DDS listener thread; bounces straight to the main thread.
     */
    private final DdsBridge.StringListener sampleListener = this::postSample;

    public DdsManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Creates the participant + endpoints. No-op when already running. Safe from the main thread.
     */
    public void start(final Config config) {
        final Config cfg = (config == null) ? Config.defaults() : config;
        executor.execute(() -> {
            if (running) {
                postStatus("Already running: " + runningConfig.describe());
                return;
            }
            create(cfg);
        });
    }

    /**
     * Tears the participant down and builds a fresh one. Participant QoS (domain id, discovery mode,
     * server endpoint) is immutable in Fast DDS, so changing it can only be done by recreating.
     */
    public void restart(final Config config) {
        final Config cfg = (config == null) ? Config.defaults() : config;
        executor.execute(() -> {
            postStatus("Restarting: " + cfg.describe());
            teardown();
            create(cfg);
        });
    }

    /**
     * Releases writer, then reader, then participant, then the multicast lock. Idempotent and safe
     * from the main thread; the teardown itself runs on the executor.
     */
    public void stop() {
        executor.execute(() -> {
            final boolean wasRunning = running;
            teardown();
            postStatus(wasRunning ? "Stopped" : "Already stopped");
        });
    }

    /** Reflects the last completed start/stop; may lag a queued call by one executor turn. */
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ continuous publish

    /** True while the continuous publisher is running. Main thread only. */
    public boolean isContinuousPublishing() {
        return continuousPublishing;
    }

    /**
     * Starts continuous publishing at {@code rateHz} samples per second, writing
     * {@code RTT <seq> <nanos>} so the RTT tracker can match the echo. No-op when already
     * running or when the participant is not up (the task is queued, so it picks up as soon
     * as start() completes).
     */
    public void startContinuousPublishing(final int rateHz) {
        executor.execute(() -> {
            if (continuousPublishing) {
                postStatus("Continuous publish already running");
                return;
            }
            final int clamped = (rateHz < 1) ? 1 : Math.min(rateHz, Config.RATE_HZ_MAX);
            final long periodNs = 1_000_000_000L / clamped;
            postStatus("Continuous publish: " + clamped + " Hz (" + periodNs + " ns period)");
            continuousPublishing = true;
            // Fixed delay (not fixed rate) is lint-clean and keeps up under load; the
            // effective rate is measured from the sent timestamps, not the schedule.
            continuousPublish = executor.scheduleWithFixedDelay(
                    this::publishContinuousSample, periodNs, periodNs, TimeUnit.NANOSECONDS);
        });
    }

    /** Stops the continuous publisher. No-op when not running. */
    public void stopContinuousPublishing() {
        executor.execute(() -> {
            if (!continuousPublishing) {
                return;
            }
            if (continuousPublish != null) {
                continuousPublish.cancel(false);
                continuousPublish = null;
            }
            continuousPublishing = false;
            postStatus("Continuous publish stopped");
        });
    }

    /** Reflects the last start/stop of the continuous publisher (main thread only). */
    private volatile boolean continuousPublishing;

    /** Executor thread only. One continuous publish tick. */
    private void publishContinuousSample() {
        if (!running || writerHandle == 0) {
            return;
        }
        final long sentNanos = System.nanoTime();
        final int id = rttTracker.register(sentNanos);
        final String payload = RTT_PREFIX + id + " " + sentNanos;
        try {
            if (!DdsBridge.nativeWriteString(writerHandle, payload)) {
                postError("nativeWriteString failed (continuous): " + payload);
            }
        } catch (Throwable t) {
            postError("nativeWriteString failed (continuous): " + t);
        }

        // One status line per second so the effective rate is observable in the UI.
        txCount++;
        final long now = System.nanoTime();
        if (lastTxReportNs == 0) {
            lastTxReportNs = now;
        } else if (now - lastTxReportNs >= 1_000_000_000L) {
            final double hz = txCount / ((now - lastTxReportNs) / 1_000_000_000.0);
            lastTxReportNs = now;
            txCount = 0;
            postStatus(String.format(java.util.Locale.US, "Publishing %.1f Hz", hz));
        }
    }

    /**
     * Returns the current RTT snapshot (main thread only). The snapshot is a
     * plain value object; the tracker keeps running.
     */
    public RttTracker.Snapshot rttSnapshot() {
        return rttTracker.snapshot();
    }

    /**
     * Publishes one sample. Silently ignored (with a status) when the participant is not up, so the
     * UI can never invoke a native method on a {@code 0} handle.
     */
    public void send(final String data) {
        if (data == null) {
            return;
        }
        if (data.trim().isEmpty()) {
            postStatus("Nothing to send.");
            return;
        }
        executor.execute(() -> {
            if (!running || writerHandle == 0) {
                postError("Not running - message dropped: " + data);
                return;
            }
            try {
                if (DdsBridge.nativeWriteString(writerHandle, data)) {
                    Log.d(TAG, "wrote " + data.length() + " chars");
                } else {
                    postError("nativeWriteString returned false: " + data);
                }
            } catch (Throwable t) {
                postError("nativeWriteString failed: " + t);
            }
        });
    }

    // ------------------------------------------------------------------ executor thread

    /** Executor thread only. */
    private void create(Config cfg) {
        // Multicast lock first: without it Android filters the inbound discovery traffic and the
        // participant would come up but never see any peer.
        acquireMulticastLock();

        try {
            final String version = DdsBridge.nativeVersion();
            Log.i(TAG, "nativeVersion=" + version);
            postStatus("Fast DDS " + version + " | " + cfg.describe() + " | topic=" + DDS_TOPIC);

            if (cfg.isDiscoveryServer() && cfg.serverHost.isEmpty()) {
                postStatus("DISCOVERY_SERVER selected but no server host is set.");
            }

            final long participant = DdsBridge.nativeCreateParticipant(cfg.domainId, cfg.mode,
                    cfg.serverHost, cfg.serverPort, PARTICIPANT_NAME);
            if (participant == 0) {
                postError("nativeCreateParticipant failed (" + cfg.describe() + ")");
                releaseMulticastLock();
                return;
            }
            participantHandle = participant;

            final long writer = DdsBridge.nativeCreateWriter(participantHandle, DDS_TOPIC,
                    DDS_TYPE_NAME);
            if (writer == 0) {
                postError("nativeCreateWriter failed for " + DDS_TOPIC);
                teardown();
                return;
            }
            writerHandle = writer;

            final long reader = DdsBridge.nativeCreateReader(participantHandle, DDS_TOPIC,
                    DDS_TYPE_NAME, sampleListener);
            if (reader == 0) {
                postError("nativeCreateReader failed for " + DDS_TOPIC);
                teardown();
                return;
            }
            readerHandle = reader;

            runningConfig = cfg;
            running = true;
            Log.i(TAG, "started " + cfg.describe() + " topic=" + DDS_TOPIC + " type=" + DDS_TYPE_NAME);
            postStatus("Running on " + DDS_TOPIC + " (" + Config.modeName(cfg.mode) + ")");
            startPeerPoll();
        } catch (Throwable t) {
            // Typically UnsatisfiedLinkError while libfastdds_bridge.so is not built/installed yet.
            Log.e(TAG, "participant creation failed", t);
            postError("Cannot start DDS: " + t);
            teardown();
        }
    }

    /**
     * Executor thread only. Reverse order (writer -> reader -> participant) so no endpoint outlives
     * its participant. Every step is null/zero-checked, so this is safe to call repeatedly and from
     * partially initialised states.
     */
    private void teardown() {
        cancelPeerPoll();
        if (continuousPublish != null) {
            continuousPublish.cancel(false);
            continuousPublish = null;
            continuousPublishing = false;
        }

        if (writerHandle != 0) {
            try {
                DdsBridge.nativeDelete(writerHandle, participantHandle);
            } catch (Throwable t) {
                Log.w(TAG, "writer delete failed", t);
            }
            writerHandle = 0;
        }

        if (readerHandle != 0) {
            try {
                DdsBridge.nativeDelete(readerHandle, participantHandle);
            } catch (Throwable t) {
                Log.w(TAG, "reader delete failed", t);
            }
            readerHandle = 0;
        }

        if (participantHandle != 0) {
            try {
                DdsBridge.nativeDestroyParticipant(participantHandle);
            } catch (Throwable t) {
                Log.w(TAG, "participant destroy failed", t);
            }
            participantHandle = 0;
        }

        releaseMulticastLock();
        running = false;
        runningConfig = null;
    }

    /** Executor thread only. */
    private void startPeerPoll() {
        cancelPeerPoll();
        peerPoll = executor.scheduleWithFixedDelay(this::pollPeers, PEER_POLL_PERIOD_S,
                PEER_POLL_PERIOD_S, TimeUnit.SECONDS);
    }

    /** Executor thread only. */
    private void cancelPeerPoll() {
        if (peerPoll != null) {
            peerPoll.cancel(false);
            peerPoll = null;
        }
    }

    /** Executor thread only. Must not throw, or the periodic task would stop being rescheduled. */
    private void pollPeers() {
        if (!running || participantHandle == 0) {
            return;
        }
        try {
            final int peers = DdsBridge.nativeDiscoveredPeers(participantHandle);
            // Counts matched endpoints (reader/writer pairs), which is the truthful
            // signal in both discovery modes. In SIMPLE mode it lags participant
            // discovery slightly because matching happens after discovery.
            final String message = (peers > 0)
                    ? "Matched endpoints: " + peers
                    : "No matched endpoints - check the mode (SIMPLE and DISCOVERY_SERVER do "
                            + "not interoperate) and that a peer is running";
            Log.i(TAG, message);
            postStatus(message);
        } catch (Throwable t) {
            Log.w(TAG, "peer poll failed", t);
        }
    }

    // ------------------------------------------------------------------ multicast lock

    /** Executor thread only. */
    private void acquireMulticastLock() {
        if (multicastLock != null) {
            return;
        }
        try {
            final WifiManager wifiManager = appContext.getSystemService(WifiManager.class);
            if (wifiManager == null) {
                Log.w(TAG, "WifiManager unavailable - multicast discovery may be filtered");
                postStatus("WifiManager unavailable; multicast discovery may be filtered.");
                return;
            }
            final WifiManager.MulticastLock lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG);
            lock.setReferenceCounted(false);
            lock.acquire();
            multicastLock = lock;
            Log.i(TAG, "multicast lock acquired");
        } catch (SecurityException e) {
            // android.permission.CHANGE_WIFI_MULTICAST_STATE is not granted.
            Log.w(TAG, "multicast lock not permitted", e);
            postError("Multicast lock not permitted: add CHANGE_WIFI_MULTICAST_STATE.");
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock unavailable", t);
        }
    }

    /** Executor thread only. */
    private void releaseMulticastLock() {
        if (multicastLock == null) {
            return;
        }
        try {
            if (multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock release failed", t);
        }
        multicastLock = null;
    }

    // ------------------------------------------------------------------ persistence

    /** Loads the persisted settings, falling back to defaults and re-clamping on the way in. */
    public static Config load(Context context) {
        final SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final Config config = new Config(
                prefs.getInt(KEY_DOMAIN_ID, Config.DEFAULT_DOMAIN_ID),
                prefs.getInt(KEY_MODE, DdsBridge.MODE_SIMPLE),
                prefs.getString(KEY_SERVER_HOST, Config.DEFAULT_SERVER_HOST),
                prefs.getInt(KEY_SERVER_PORT, Config.DEFAULT_SERVER_PORT),
                prefs.getInt(KEY_RATE_HZ, Config.DEFAULT_RATE_HZ));
        Log.i(TAG, "load " + config.describe());
        return config;
    }

    public static void save(Context context, Config config) {
        final Config cfg = (config == null) ? Config.defaults() : config;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DOMAIN_ID, cfg.domainId)
                .putInt(KEY_MODE, cfg.mode)
                .putString(KEY_SERVER_HOST, cfg.serverHost)
                .putInt(KEY_SERVER_PORT, cfg.serverPort)
                .putInt(KEY_RATE_HZ, cfg.rateHz)
                .apply();
        Log.i(TAG, "save " + cfg.describe());
    }

    // ------------------------------------------------------------------ main thread delivery

    private void postSample(String topic, String data) {
        // RTT echo matching: an echo of one of our pings is "<payload> ~E" and the
        // payload is "RTT <seq> <nanos>". The local loopback copy (no marker) is
        // deliberately ignored - it says nothing about the network.
        if (data.startsWith(RTT_PREFIX)) {
            final String payload = data.endsWith(RTT_ECHO_MARKER)
                    ? data.substring(0, data.length() - RTT_ECHO_MARKER.length())
                    : data;
            final String[] parts = payload.split(" ");
            if (parts.length == 3 && data.endsWith(RTT_ECHO_MARKER)) {
                try {
                    final int id = Integer.parseInt(parts[1]);
                    final long rtt = rttTracker.record(id, System.nanoTime());
                    if (rtt >= 0) {
                        postStatus(rttTracker.snapshot().toString());
                    }
                } catch (NumberFormatException ignored) {
                    // Not one of our pings - deliver as a normal sample.
                }
            }
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onSample(topic, data);
            }
        });
    }

    private void postStatus(String status) {
        Log.i(TAG, status);
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onStatus(status);
            }
        });
    }

    private void postError(String message) {
        Log.w(TAG, message);
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }
}
