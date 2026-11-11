package com.example.fastddstest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.fastddstest.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Single screen: configure the DDS participant, publish messages and watch incoming samples.
 *
 * <p>The activity owns a {@link DdsManager} for its own lifetime only. All native work is done on
 * the manager's executor, so nothing here blocks the main thread.</p>
 */
public class MainActivity extends AppCompatActivity implements DdsManager.Listener {

    /** Keeps the log bounded; it is rebuilt on every append, so it must stay small. */
    private static final int MAX_LOG_LINES = 200;

    private static final int MODE_POSITION_SIMPLE = 0;
    private static final int MODE_POSITION_DISCOVERY_SERVER = 1;

    /**
     * Android 17 (API 37) enforces Local Network Protection: UDP to LAN addresses
     * (including the RTPS multicast range 224.0.0.0/4) requires {@code ACCESS_LOCAL_NETWORK}.
     * Targeting that SDK without the permission makes sendto() fail with EPERM.
     */
    private static final int API_LOCAL_NETWORK = 37;

    /**
     * Spelled out rather than using {@code Manifest.permission.ACCESS_LOCAL_NETWORK} so the field
     * does not trip the NewApi lint check on the {@code minSdk 24} build. The value is identical.
     */
    private static final String PERMISSION_ACCESS_LOCAL_NETWORK =
            "android.permission.ACCESS_LOCAL_NETWORK";

    private static final int REQ_LOCAL_NETWORK = 1001;

    private ActivityMainBinding binding;
    private DdsManager ddsManager;
    private DdsManager.Config config;

    private final Deque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WindowCompat.enableEdgeToEdge(getWindow());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // The IME must not cover the message field / log either, so take the larger bottom inset.
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            return insets;
        });

        setupDiscoveryModeSpinner();
        binding.applyButton.setOnClickListener(v -> onApplyClicked());
        binding.sendButton.setOnClickListener(v -> onSendClicked());
        binding.continuousButton.setOnClickListener(v -> onContinuousClicked());

        config = DdsManager.load(this);
        bindConfigToUi(config);
        appendLog("ROS topic " + DdsManager.ROS_TOPIC + " -> DDS " + DdsManager.DDS_TOPIC
                + " (" + DdsManager.DDS_TYPE_NAME + ")");

        ddsManager = new DdsManager(this, this);
        startWhenLocalNetworkAllowed();

        // Debug hook: adb shell am broadcast lets a test publish without touching the
        // UI (adb input text is unreliable on MIUI and the focusable log view stole
        // focus). EXPORTED is required for the shell to deliver the broadcast; the
        // custom action means nothing else on the device will ever send it.
        // ContextCompat handles the flag overload across API levels.
        androidx.core.content.ContextCompat.registerReceiver(this,
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final String data = intent.getStringExtra("data");
                        if (data != null && ddsManager != null) {
                            appendLog("TX(broadcast) " + data);
                            ddsManager.send(data);
                        }
                    }
                },
                new IntentFilter("com.example.fastddstest.SEND_SAMPLE"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * Starts the participant, asking for {@code ACCESS_LOCAL_NETWORK} first on API 37+.
     *
     * <p>Local Network Protection is a per-UID check in the socket layer, so it is orthogonal to
     * the Wi-Fi multicast lock: without the permission the sockets fail with {@code EPERM},
     * whereas a missing multicast lock merely makes inbound packets get filtered silently.
     * Platforms below 37 grant local network access implicitly through {@code INTERNET}.</p>
     */
    private void startWhenLocalNetworkAllowed() {
        if (ddsManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < API_LOCAL_NETWORK || hasLocalNetworkPermission()) {
            ddsManager.start(config);
            return;
        }
        appendLog("Requesting local network access (Android 17+)");
        ActivityCompat.requestPermissions(this,
                new String[]{PERMISSION_ACCESS_LOCAL_NETWORK}, REQ_LOCAL_NETWORK);
    }

    private boolean hasLocalNetworkPermission() {
        return checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCAL_NETWORK) {
            return;
        }
        final boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        appendLog(granted
                ? "Local network access granted"
                : "Local network access denied - LAN discovery will be blocked");
        // Start regardless: the participant still comes up, and the error surfaces as 0 peers.
        if (ddsManager != null) {
            ddsManager.start(config);
        }
    }

    @Override
    protected void onDestroy() {
        if (ddsManager != null) {
            ddsManager.stop();
            ddsManager = null;
        }
        binding = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ setup

    private void setupDiscoveryModeSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"SIMPLE", "DISCOVERY_SERVER"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.discoveryMode.setAdapter(adapter);
        binding.discoveryMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateServerFieldsEnabled();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Spinner always has a selection.
            }
        });
    }

    private void bindConfigToUi(@NonNull DdsManager.Config cfg) {
        binding.discoveryMode.setSelection(cfg.isDiscoveryServer()
                ? MODE_POSITION_DISCOVERY_SERVER
                : MODE_POSITION_SIMPLE);
        binding.serverAddress.setText(cfg.serverHost);
        binding.serverPort.setText(String.valueOf(cfg.serverPort));
        binding.domainId.setText(String.valueOf(cfg.domainId));
        binding.rateHz.setText(String.valueOf(cfg.rateHz));
        updateServerFieldsEnabled();
        updateStatusHeader();
    }

    /** Server endpoint fields are meaningless in SIMPLE discovery, so grey them out. */
    private void updateServerFieldsEnabled() {
        boolean discoveryServer =
                binding.discoveryMode.getSelectedItemPosition() == MODE_POSITION_DISCOVERY_SERVER;
        setFieldEnabled(binding.serverAddress, discoveryServer);
        setFieldEnabled(binding.serverPort, discoveryServer);
    }

    private static void setFieldEnabled(@NonNull EditText field, boolean enabled) {
        field.setEnabled(enabled);
        field.setAlpha(enabled ? 1f : 0.4f);
    }

    private void updateStatusHeader() {
        if (binding == null || config == null) {
            return;
        }
        String mode = config.isDiscoveryServer()
                ? "DISCOVERY_SERVER " + config.serverEndpoint()
                : "SIMPLE";
        binding.statusText.setText("Topic: " + DdsManager.DDS_TOPIC
                + "\nType:  " + DdsManager.DDS_TYPE_NAME
                + "\nMode:  " + mode + " | domain " + config.domainId);
    }

    // ------------------------------------------------------------------ actions

    private void onApplyClicked() {
        DdsManager.Config updated = new DdsManager.Config(
                parseIntField(binding.domainId, DdsManager.Config.DEFAULT_DOMAIN_ID, "domain id"),
                binding.discoveryMode.getSelectedItemPosition() == MODE_POSITION_DISCOVERY_SERVER
                        ? DdsBridge.MODE_DISCOVERY_SERVER
                        : DdsBridge.MODE_SIMPLE,
                binding.serverAddress.getText().toString(),
                parseIntField(binding.serverPort, DdsManager.Config.DEFAULT_SERVER_PORT, "server port"),
                parseIntField(binding.rateHz, DdsManager.Config.DEFAULT_RATE_HZ, "rate Hz"));

        config = updated;
        DdsManager.save(this, updated);
        // Write the clamped values back so the UI shows what is actually used.
        bindConfigToUi(updated);
        appendLog("Applied " + updated.describe());

        if (ddsManager != null) {
            ddsManager.restart(updated);
        }
    }

    private void onSendClicked() {
        String text = binding.messageInput.getText().toString();
        if (text.trim().isEmpty()) {
            appendLog("Nothing to send.");
            return;
        }
        appendLog("TX " + text);
        if (ddsManager != null) {
            if (!ddsManager.isRunning()) {
                // The manager serialises on its executor, so a send issued while the participant is
                // still coming up is written once start() has completed rather than dropped.
                appendLog("Participant not up yet; sample queued.");
            }
            ddsManager.send(text);
        }
        binding.messageInput.setText("");
    }

    private void onContinuousClicked() {
        if (ddsManager == null) {
            return;
        }
        if (ddsManager.isContinuousPublishing()) {
            ddsManager.stopContinuousPublishing();
            binding.continuousButton.setText("Start continuous publish");
        } else {
            final int rate = parseIntField(binding.rateHz, DdsManager.Config.DEFAULT_RATE_HZ,
                    "rate Hz");
            if (rate < 1) {
                appendLog("Rate Hz must be 1..1000 to start continuous publish.");
                return;
            }
            ddsManager.startContinuousPublishing(rate);
            binding.continuousButton.setText("Stop continuous publish");
        }
    }

    private int parseIntField(@NonNull EditText field, int fallback, String label) {
        String raw = field.getText().toString().trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            appendLog("Invalid " + label + " \"" + raw + "\", using " + fallback);
            return fallback;
        }
    }

    // ------------------------------------------------------------------ DdsManager.Listener

    @Override
    public void onSample(String topic, String data) {
        if (data.startsWith(DdsManager.RTT_PREFIX)) {
            // The RTT stats line itself carries the aggregate; keep the log readable
            // by logging only the echoed copy (the network RTT), not the local loopback.
            if (data.endsWith(DdsManager.RTT_ECHO_MARKER)) {
                appendLog("RX [" + topic + "] " + data + "  (echo)");
            }
            return;
        }
        appendLog("RX [" + topic + "] " + data);
    }

    @Override
    public void onStatus(String status) {
        appendLog(status);
    }

    @Override
    public void onError(String message) {
        appendLog("ERROR " + message);
    }

    // ------------------------------------------------------------------ log

    /** Appends one timestamped line, trims to {@link #MAX_LOG_LINES} and scrolls to the bottom. */
    private void appendLog(String line) {
        if (binding == null) {
            return;
        }
        logLines.addLast(timeFormat.format(new Date()) + "  " + line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }

        StringBuilder sb = new StringBuilder(logLines.size() * 48);
        for (String entry : logLines) {
            sb.append(entry).append('\n');
        }
        binding.logText.setText(sb);

        final ScrollView scroll = binding.logScroll;
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
