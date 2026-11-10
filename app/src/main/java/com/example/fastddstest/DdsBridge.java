package com.example.fastddstest;

import androidx.annotation.Keep;

/**
 * JNI facade for the native Fast DDS bridge ({@code libfastdds_bridge.so}).
 *
 * <p>Every handle returned here (a {@code long}) is an <strong>opaque native pointer</strong> that
 * only the native layer understands. Java must never dereference it, do arithmetic on it or compare
 * it against anything except {@code 0}. A handle of {@code 0} means
 * <em>failure / nothing created</em> and must never be handed back to a native function.</p>
 *
 * <p>All entry points block: creating a participant starts discovery threads, and creating or
 * writing endpoints takes native locks and may wait on the network. Callers must therefore never
 * touch this class from the main thread - {@code DdsManager} owns the full lifecycle on a
 * single-threaded executor and is the only supported caller.</p>
 *
 * <p>{@code @Keep} is deliberate: the release build runs R8 with optimizations enabled, and the
 * native library resolves these methods by their mangled {@code Java_com_example_fastddstest_...}
 * names, so the class and its members must not be renamed or stripped.</p>
 */
@Keep
public final class DdsBridge {

    static {
        System.loadLibrary("fastdds_bridge");
    }

    /** Default Fast DDS discovery (multicast, UDPv4). Interoperates with plain ROS 2 nodes. */
    public static final int MODE_SIMPLE = 0;

    /** Fast DDS Discovery Server client: unicast only, must be pointed at a server host/port. */
    public static final int MODE_DISCOVERY_SERVER = 1;

    private DdsBridge() {
        throw new AssertionError("No instances.");
    }

    /**
     * @return participant handle, or {@code 0} on failure
     */
    public static native long nativeCreateParticipant(int domainId, int discoveryMode,
            String serverHost, int serverPort,
            String participantName);

    /**
     * @return writer handle, or {@code 0} on failure
     */
    public static native long nativeCreateWriter(long participantHandle, String topic, String typeName);

    /**
     * @return reader handle, or {@code 0} on failure. {@code listener} may be {@code null}, in which
     *         case samples are dropped (the reader is still created so it counts as a matched
     *         endpoint on the remote side).
     */
    public static native long nativeCreateReader(long participantHandle, String topic, String typeName,
            StringListener listener);

    /**
     * Publishes one {@code std_msgs::msg::dds_::String_} sample; blocks until written.
     *
     * @return {@code true} if the sample was handed to DDS
     */
    public static native boolean nativeWriteString(long writerHandle, String data);

    /**
     * Releases a writer or reader handle. {@code 0} handles are ignored by the native side.
     *
     * @param participantHandle the owning participant, so the native layer can clear its cached
     *        writer/reader pointer before freeing the endpoint (it is used by
     *        {@link #nativeDiscoveredPeers(long)}).
     */
    public static native void nativeDelete(long handle, long participantHandle);

    /**
     * Destroys the participant and everything still attached to it. {@code 0} is ignored.
     */
    public static native void nativeDestroyParticipant(long handle);

    /**
     * @return the number of remote domain participants currently matched, or {@code -1} on error
     */
    public static native int nativeDiscoveredPeers(long participantHandle);

    /**
     * @return the Fast DDS version the native library was built against, e.g. {@code "3.6.2"}
     */
    public static native String nativeVersion();

    /**
     * Receives samples on a native DDS listener thread. Implementations must not block and must
     * hop to the main thread before touching the UI.
     */
    public interface StringListener {
        void onSample(String topic, String data);
    }
}
