
package com.example.driverdrowsiness;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private PreviewView cameraPreview;

    private TextView monitoringServiceStatus;
    private TextView statusText;
    private TextView earText;
    private TextView headPoseText;
    private TextView riskScoreText;

    private Button startMonitoringButton;
    private Button stopMonitoringButton;
    private Button acknowledgeButton;

    private DrowsinessMonitoringService monitoringService;
    private Intent serviceIntent;

    private boolean serviceBound = false;
    private boolean startPending = false;
    private boolean notificationPermissionDenied = false;

    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private final DrowsinessMonitoringService.Listener serviceListener =
            new DrowsinessMonitoringService.Listener() {

                @Override
                public void onMonitoringStatus(String status) {
                    runOnUiThread(() -> {
                        monitoringServiceStatus.setText(
                                "Monitoring service: " + status
                        );
                        updateButtons();
                    });
                }

                @Override
                public void onDetectionUpdate(
                        String status,
                        String earValue,
                        String headPoseValue,
                        String riskValue,
                        boolean acknowledgeEnabled
                ) {
                    runOnUiThread(() -> {
                        statusText.setText(status);
                        earText.setText(earValue);
                        headPoseText.setText(headPoseValue);
                        riskScoreText.setText(riskValue);
                        acknowledgeButton.setEnabled(acknowledgeEnabled);
                    });
                }
            };

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder binder
                ) {
                    DrowsinessMonitoringService.LocalBinder localBinder =
                            (DrowsinessMonitoringService.LocalBinder) binder;

                    monitoringService = localBinder.getService();
                    serviceBound = true;
                    startPending = false;

                    monitoringService.setListener(serviceListener);
                    monitoringService.setPreviewSurfaceProvider(
                            cameraPreview.getSurfaceProvider()
                    );

                    monitoringServiceStatus.setText(
                            monitoringService.isMonitoring()
                                    ? "Monitoring service: Active"
                                    : "Monitoring service: Connected"
                    );

                    if (notificationPermissionDenied) {
                        statusText.setText(
                                "Notification permission denied. " +
                                        "Monitoring notification visibility may be limited."
                        );
                    } else if (monitoringService.isMonitoring()) {
                        statusText.setText("Monitoring is active.");
                    }

                    updateButtons();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    serviceBound = false;
                    monitoringService = null;

                    monitoringServiceStatus.setText(
                            "Monitoring service: Disconnected"
                    );

                    updateButtons();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        serviceIntent = new Intent(
                this,
                DrowsinessMonitoringService.class
        );

        setContentView(R.layout.activity_main);

        bindViews();
        registerPermissionLaunchers();
        setupButtons();

        monitoringServiceStatus.setText(
                "Monitoring service: Not started"
        );
        statusText.setText(
                "Press Start Monitoring to begin."
        );

        updateButtons();
    }

    private void bindViews() {
        cameraPreview = findViewById(R.id.cameraPreview);

        monitoringServiceStatus =
                findViewById(R.id.monitoringServiceStatus);

        statusText = findViewById(R.id.statusText);
        earText = findViewById(R.id.earText);
        headPoseText = findViewById(R.id.headPoseText);
        riskScoreText = findViewById(R.id.riskScoreText);

        startMonitoringButton =
                findViewById(R.id.startMonitoringButton);

        stopMonitoringButton =
                findViewById(R.id.stopMonitoringButton);

        acknowledgeButton =
                findViewById(R.id.acknowledgeButton);
    }

    private void registerPermissionLaunchers() {
        cameraPermissionLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        granted -> {
                            if (granted) {
                                requestNotificationPermissionThenStart();
                            } else {
                                startPending = false;
                                statusText.setText(
                                        "Camera permission is required " +
                                                "to monitor drowsiness."
                                );
                                updateButtons();
                            }
                        }
                );

        notificationPermissionLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        granted -> {
                            notificationPermissionDenied = !granted;
                            startMonitoringService();
                        }
                );
    }

    private void setupButtons() {
        startMonitoringButton.setOnClickListener(view -> {
            if (startPending) {
                return;
            }

            startPending = true;
            updateButtons();

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(
                        Manifest.permission.CAMERA
                );
            } else {
                requestNotificationPermissionThenStart();
            }
        });

        stopMonitoringButton.setOnClickListener(view -> {
            startPending = false;

            if (monitoringService != null) {
                monitoringService.stopMonitoring();
            } else {
                stopService(serviceIntent);
            }

            monitoringServiceStatus.setText(
                    "Monitoring service: Stopping"
            );
            statusText.setText("Monitoring stopped by user.");
            acknowledgeButton.setEnabled(false);

            updateButtons();
        });

        acknowledgeButton.setOnClickListener(view -> {
            if (monitoringService != null) {
                monitoringService.acknowledgeWarning();
                acknowledgeButton.setEnabled(false);
                statusText.setText(
                        "Alarm acknowledged. Continue monitoring."
                );
            }
        });
    }

    private void requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {

            notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );
        } else {
            startMonitoringService();
        }
    }

    private void startMonitoringService() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED) {
            startPending = false;
            statusText.setText(
                    "Camera permission is required to monitor."
            );
            updateButtons();
            return;
        }

        try {
            ContextCompat.startForegroundService(
                    this,
                    serviceIntent
            );

            bindToMonitoringService();

            monitoringServiceStatus.setText(
                    "Monitoring service: Starting"
            );
            statusText.setText(
                    "Starting camera and drowsiness detection..."
            );

        } catch (SecurityException | IllegalStateException exception) {
            startPending = false;

            monitoringServiceStatus.setText(
                    "Monitoring service: Failed to start"
            );
            statusText.setText(
                    "Could not start monitoring. Check permissions " +
                            "and try again."
            );

            updateButtons();
        }
    }

    private void bindToMonitoringService() {
        if (serviceBound) {
            if (monitoringService != null) {
                monitoringService.setListener(serviceListener);
                monitoringService.setPreviewSurfaceProvider(
                        cameraPreview.getSurfaceProvider()
                );
            }

            startPending = false;
            updateButtons();
            return;
        }

        boolean bound = bindService(
                serviceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );

        if (!bound) {
            startPending = false;

            monitoringServiceStatus.setText(
                    "Monitoring service: Connection failed"
            );
            statusText.setText(
                    "Could not connect to the monitoring service."
            );

            updateButtons();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Reconnect to an already-running service without
        // creating a new service instance.
        if (!serviceBound && !startPending) {
            boolean bound = bindService(
                    serviceIntent,
                    serviceConnection,
                    0
            );

            if (bound) {
                serviceBound = true;
            }
        }
    }

    @Override
    protected void onStop() {
        // Detach the Activity preview, but do not stop the
        // foreground monitoring service.
        if (monitoringService != null) {
            monitoringService.setPreviewSurfaceProvider(null);
            monitoringService.setListener(null);
        }

        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }

        monitoringService = null;

        super.onStop();
    }

    private void updateButtons() {
        boolean monitoring =
                monitoringService != null
                        && monitoringService.isMonitoring();

        startMonitoringButton.setEnabled(
                !startPending && !monitoring
        );

        stopMonitoringButton.setEnabled(
                monitoring || startPending
        );

        if (!monitoring) {
            acknowledgeButton.setEnabled(false);
        }
    }
}
