package com.example.driverdrowsiness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class DrowsinessMonitoringService extends LifecycleService {

    private static final String CHANNEL_ID = "drowsiness_monitoring";
    private static final int NOTIFICATION_ID = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong lastFrameTime = new AtomicLong(0);

    private final EarSmoother leftEarSmoother = new EarSmoother();
    private final EarSmoother rightEarSmoother = new EarSmoother();

    private final DrowsinessDetector drowsinessDetector =
            new DrowsinessDetector(0.18, 1500);

    private final HeadPoseDetector headPoseDetector =
            new HeadPoseDetector();

    private final YawnDetector yawnDetector = new YawnDetector();
    private final DrowsinessRiskScorer riskScorer =
            new DrowsinessRiskScorer();

    private static final int[] LEFT_EYE =
            {362, 385, 387, 263, 373, 380};

    private static final int[] RIGHT_EYE =
            {33, 160, 158, 133, 153, 144};

    private ExecutorService cameraExecutor;
    private FaceLandmarker faceLandmarker;
    private ProcessCameraProvider cameraProvider;
    private Preview previewUseCase;
    private Preview.SurfaceProvider previewSurfaceProvider;
    private ImageAnalysis analysisUseCase;

    private Ringtone alarmRingtone;
    private Vibrator vibrator;

    private volatile boolean isStopping = false;
    private volatile boolean isProcessing = false;
    private volatile boolean warningAcknowledged = false;
    private volatile boolean monitoring = false;

    private Listener listener;

    private String latestServiceStatus = "Monitoring service starting";
    private String latestDetectionStatus = "Waiting for camera";
    private String latestEarText = "EAR: --";
    private String latestHeadPoseText = "Head pose: --";
    private String latestRiskText = "Experimental risk score: --";
    private boolean latestAcknowledgeEnabled = false;

    public interface Listener {
        void onMonitoringStatus(String status);

        void onDetectionUpdate(
                String status,
                String earText,
                String headPoseText,
                String riskText,
                boolean acknowledgeEnabled
        );
    }

    public class LocalBinder extends android.os.Binder {
        public DrowsinessMonitoringService getService() {
            return DrowsinessMonitoringService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        super.onStartCommand(intent, flags, startId);

        if (isStopping) {
            return START_NOT_STICKY;
        }

        if (!monitoring) {
            try {
                startForegroundSafely();
                monitoring = true;
                isStopping = false;
                latestServiceStatus = "Monitoring active";
                notifyMonitoringStatus(latestServiceStatus);

                initializeDetection();
            } catch (Exception e) {
                latestServiceStatus =
                        "Unable to start monitoring: " + errorMessage(e);
                notifyMonitoringStatus(latestServiceStatus);
                stopMonitoring();
            }
        }

        return START_NOT_STICKY;
    }

    private void startForegroundSafely() {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Drowsiness Monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription(
                    "Shows when driver drowsiness monitoring is active."
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Drowsiness monitoring")
                .setContentText("Camera monitoring is active.")
                .setOngoing(true)
                .build();
    }

    public void setListener(Listener newListener) {
        listener = newListener;

        if (newListener != null) {
            String status = latestServiceStatus;
            String detection = latestDetectionStatus;
            String ear = latestEarText;
            String pose = latestHeadPoseText;
            String risk = latestRiskText;
            boolean acknowledge = latestAcknowledgeEnabled;

            mainHandler.post(() -> {
                if (listener != newListener) {
                    return;
                }

                newListener.onMonitoringStatus(status);
                newListener.onDetectionUpdate(
                        detection, ear, pose, risk, acknowledge
                );
            });
        }
    }

    public boolean isMonitoring() {
        return monitoring && !isStopping;
    }

    public void setPreviewSurfaceProvider(
            @Nullable Preview.SurfaceProvider surfaceProvider
    ) {
        mainHandler.post(() -> {
            previewSurfaceProvider = surfaceProvider;

            if (previewUseCase != null && !isStopping) {
                previewUseCase.setSurfaceProvider(surfaceProvider);
            }
        });
    }

    public void acknowledgeWarning() {
        mainHandler.post(() -> {
            if (isStopping) {
                return;
            }

            warningAcknowledged = true;
            stopAlarm();

            latestDetectionStatus =
                    "Warning acknowledged. Stay alert.";
            latestAcknowledgeEnabled = false;

            notifyDetectionUpdate();
        });
    }

    public void stopMonitoring() {
        if (isStopping) {
            return;
        }

        isStopping = true;
        monitoring = false;
        warningAcknowledged = false;

        stopAlarm();
        releaseCameraAndDetector();

        latestServiceStatus = "Monitoring stopped";
        latestDetectionStatus = "Monitoring stopped";
        latestEarText = "EAR: --";
        latestHeadPoseText = "Head pose: --";
        latestRiskText = "Experimental risk score: --";
        latestAcknowledgeEnabled = false;

        notifyMonitoringStatus(latestServiceStatus);
        notifyDetectionUpdate();

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void initializeDetection() throws IOException {
        cameraExecutor = Executors.newSingleThreadExecutor();

        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build();

        FaceLandmarker.FaceLandmarkerOptions options =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(1)
                        .setOutputFacialTransformationMatrixes(true)
                        .setResultListener(this::onLandmarksDetected)
                        .setErrorListener(error ->
                                mainHandler.post(() -> {
                                    if (!isStopping) {
                                        latestServiceStatus =
                                                "Detection error: "
                                                        + error.getMessage();
                                        notifyMonitoringStatus(
                                                latestServiceStatus
                                        );
                                    }
                                }))
                        .build();

        faceLandmarker =
                FaceLandmarker.createFromOptions(this, options);

        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            if (isStopping) {
                return;
            }

            try {
                cameraProvider = future.get();

                previewUseCase = new Preview.Builder().build();

                if (previewSurfaceProvider != null) {
                    previewUseCase.setSurfaceProvider(previewSurfaceProvider);
                }

                analysisUseCase = new ImageAnalysis.Builder()
                        .setOutputImageFormat(
                                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build();

                analysisUseCase.setAnalyzer(
                        cameraExecutor,
                        this::analyzeFrame
                );

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        previewUseCase,
                        analysisUseCase
                );

                latestServiceStatus =
                        "Monitoring active | Camera running";
                notifyMonitoringStatus(latestServiceStatus);

            } catch (Exception e) {
                latestServiceStatus =
                        "Camera setup failed: " + errorMessage(e);
                notifyMonitoringStatus(latestServiceStatus);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy imageProxy) {
        if (isStopping || isProcessing || faceLandmarker == null) {
            imageProxy.close();
            return;
        }

        long now = SystemClock.uptimeMillis();

        if (now - lastFrameTime.get() < 100) {
            imageProxy.close();
            return;
        }

        lastFrameTime.set(now);
        isProcessing = true;

        try {
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();

            ImageProcessingOptions processingOptions =
                    ImageProcessingOptions.builder()
                            .setRotationDegrees(
                                    imageProxy.getImageInfo()
                                            .getRotationDegrees()
                            )
                            .build();

            faceLandmarker.detectAsync(
                    mpImage,
                    processingOptions,
                    now
            );

        } catch (Exception e) {
            latestServiceStatus =
                    "Frame processing error: " + errorMessage(e);
            notifyMonitoringStatus(latestServiceStatus);
        } finally {
            imageProxy.close();
            isProcessing = false;
        }
    }

    private void onLandmarksDetected(
            FaceLandmarkerResult result,
            MPImage inputImage
    ) {
        if (isStopping) {
            return;
        }

        List<List<NormalizedLandmark>> faces = result.faceLandmarks();

        if (faces.isEmpty()) {
            drowsinessDetector.reset();
            headPoseDetector.reset();
            yawnDetector.reset();
            leftEarSmoother.reset();
            rightEarSmoother.reset();

            warningAcknowledged = false;
            stopAlarm();

            latestDetectionStatus = "No face detected";
            latestEarText = "EAR: --\nMAR: --";
            latestHeadPoseText = "Head pose: --";
            latestRiskText = "Experimental risk score: --";
            latestAcknowledgeEnabled = false;

            notifyDetectionUpdate();
            return;
        }

        List<NormalizedLandmark> landmarks = faces.get(0);
        long now = SystemClock.uptimeMillis();

        double leftEar = leftEarSmoother.add(
                calculateEar(
                        landmarks,
                        LEFT_EYE,
                        inputImage.getWidth(),
                        inputImage.getHeight()
                )
        );

        double rightEar = rightEarSmoother.add(
                calculateEar(
                        landmarks,
                        RIGHT_EYE,
                        inputImage.getWidth(),
                        inputImage.getHeight()
                )
        );

        double averageEar = (leftEar + rightEar) / 2.0;

        DrowsinessDetector.State eyeState =
                drowsinessDetector.update(averageEar, now);

        long closureDuration =
                drowsinessDetector.getClosureDurationMs(now);

        YawnDetector.Result yawnResult = yawnDetector.update(
                landmarks,
                inputImage.getWidth(),
                inputImage.getHeight(),
                now
        );

        HeadPoseDetector.Result landmarkPose =
                headPoseDetector.update(landmarks, now);

        DrowsinessRiskScorer.Result riskResult =
                riskScorer.calculate(
                        closureDuration,
                        yawnResult.possibleYawn,
                        landmarkPose.headDown
                );

        String riskMessage = String.format(
                Locale.US,
                "Experimental risk score: %d/100\nRisk level: %s",
                riskResult.score,
                riskResult.riskLevel.name()
        );

        boolean eyeWarning =
                eyeState == DrowsinessDetector.State.DROWSINESS_WARNING;
        boolean yawnWarning = yawnResult.possibleYawn;
        boolean activeWarning = eyeWarning || yawnWarning;

        if (activeWarning) {
            if (!warningAcknowledged) {
                startAlarm();
            }
        } else {
            warningAcknowledged = false;
            stopAlarm();
        }

        String stateMessage;

        if (eyeWarning) {
            stateMessage = "WARNING: PROLONGED EYE CLOSURE";
        } else if (yawnWarning) {
            stateMessage = "WARNING: YAWN DETECTED";
        } else if (eyeState == DrowsinessDetector.State.EYES_CLOSED) {
            stateMessage = "Eyes closed - monitoring";
        } else {
            stateMessage = "NORMAL";
        }

        String earMessage = String.format(
                Locale.US,
                "Left EAR: %.3f\nRight EAR: %.3f\n"
                        + "Average EAR: %.3f\nEye closure: %.1f s\n\n"
                        + "Mouth Aspect Ratio (MAR): %.3f\n"
                        + "Mouth: %s\n"
                        + "Yawn status: %s",
                leftEar,
                rightEar,
                averageEar,
                closureDuration / 1000.0,
                yawnResult.mar,
                yawnResult.mouthOpen ? "OPEN" : "CLOSED",
                yawnResult.possibleYawn ? "POSSIBLE YAWN" : "NONE"
        );

        String matrixPoseMessage;

        Optional<List<float[]>> matricesOptional =
                result.facialTransformationMatrixes();

        if (matricesOptional.isPresent()
                && !matricesOptional.get().isEmpty()
                && matricesOptional.get().get(0).length >= 16) {

            PoseAngles angles = extractPoseAngles(
                    matricesOptional.get().get(0)
            );

            matrixPoseMessage = String.format(
                    Locale.US,
                    "Matrix-derived orientation (provisional)\n"
                            + "Pitch: %.1f°\n"
                            + "Yaw: %.1f°\n"
                            + "Roll: %.1f°\n\n"
                            + "Landmark roll: %.1f°\n"
                            + "Head-down proxy: %.3f\n"
                            + "Sustained head down: %s",
                    angles.pitch,
                    angles.yaw,
                    angles.roll,
                    landmarkPose.rollDegrees,
                    landmarkPose.headDownScore,
                    landmarkPose.headDown ? "YES" : "NO"
            );
        } else {
            matrixPoseMessage =
                    "Transformation matrix unavailable\n"
                            + "Landmark roll: "
                            + String.format(
                            Locale.US,
                            "%.1f°",
                            landmarkPose.rollDegrees
                    );
        }

        latestDetectionStatus = stateMessage;
        latestEarText = earMessage;
        latestHeadPoseText = matrixPoseMessage;
        latestRiskText = riskMessage;
        latestAcknowledgeEnabled =
                activeWarning && !warningAcknowledged;

        notifyDetectionUpdate();
    }

    private static class PoseAngles {
        final double pitch;
        final double yaw;
        final double roll;

        PoseAngles(double pitch, double yaw, double roll) {
            this.pitch = pitch;
            this.yaw = yaw;
            this.roll = roll;
        }
    }

    private PoseAngles extractPoseAngles(float[] m) {
        double r00 = m[0];
        double r10 = m[1];
        double r20 = m[2];

        double r11 = m[5];
        double r12 = m[6];

        double r02 = m[8];
        double r22 = m[10];

        double sinPitch = Math.max(
                -1.0, Math.min(1.0, -r12)
        );

        double pitch = Math.asin(sinPitch);
        double yaw;
        double roll;

        if (Math.abs(Math.cos(pitch)) > 1e-6) {
            yaw = Math.atan2(r02, r22);
            roll = Math.atan2(r10, r11);
        } else {
            yaw = Math.atan2(-r20, r00);
            roll = 0.0;
        }

        return new PoseAngles(
                Math.toDegrees(pitch),
                Math.toDegrees(yaw),
                Math.toDegrees(roll)
        );
    }

    private synchronized void startAlarm() {
        if (isStopping) {
            return;
        }

        try {
            if (alarmRingtone == null) {
                Uri alarmUri = RingtoneManager.getDefaultUri(
                        RingtoneManager.TYPE_ALARM
                );

                if (alarmUri == null) {
                    alarmUri = RingtoneManager.getDefaultUri(
                            RingtoneManager.TYPE_NOTIFICATION
                    );
                }

                if (alarmUri != null) {
                    alarmRingtone = RingtoneManager.getRingtone(
                            getApplicationContext(),
                            alarmUri
                    );
                }

                if (alarmRingtone != null && Build.VERSION.SDK_INT >= 28) {
                    alarmRingtone.setLooping(true);
                }
            }

            if (alarmRingtone != null && !alarmRingtone.isPlaying()) {
                alarmRingtone.play();
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 500, 300, 500};

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                            VibrationEffect.createWaveform(pattern, 0)
                    );
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
            // Audio or vibration failure must not crash detection.
        }
    }

    private synchronized void stopAlarm() {
        try {
            if (alarmRingtone != null && alarmRingtone.isPlaying()) {
                alarmRingtone.stop();
            }

            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception ignored) {
            // Safe cleanup.
        }
    }

    private double calculateEar(
            List<NormalizedLandmark> landmarks,
            int[] indices,
            int imageWidth,
            int imageHeight
    ) {
        NormalizedLandmark p1 = landmarks.get(indices[0]);
        NormalizedLandmark p2 = landmarks.get(indices[1]);
        NormalizedLandmark p3 = landmarks.get(indices[2]);
        NormalizedLandmark p4 = landmarks.get(indices[3]);
        NormalizedLandmark p5 = landmarks.get(indices[4]);
        NormalizedLandmark p6 = landmarks.get(indices[5]);

        double vertical1 = distance(p2, p6, imageWidth, imageHeight);
        double vertical2 = distance(p3, p5, imageWidth, imageHeight);
        double horizontal = distance(p1, p4, imageWidth, imageHeight);

        if (horizontal < 1e-6) {
            return 0.0;
        }

        return (vertical1 + vertical2) / (2.0 * horizontal);
    }

    private double distance(
            NormalizedLandmark a,
            NormalizedLandmark b,
            int imageWidth,
            int imageHeight
    ) {
        double dx = (a.x() - b.x()) * imageWidth;
        double dy = (a.y() - b.y()) * imageHeight;

        return Math.sqrt(dx * dx + dy * dy);
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();

        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * rowStride + x * pixelStride;

                int r = buffer.get(index) & 0xFF;
                int g = buffer.get(index + 1) & 0xFF;
                int b = buffer.get(index + 2) & 0xFF;
                int a = buffer.get(index + 3) & 0xFF;

                pixels[y * width + x] =
                        (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888
        );

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void releaseCameraAndDetector() {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
                // Safe cleanup.
            }
            cameraProvider = null;
        }

        previewUseCase = null;
        analysisUseCase = null;

        if (faceLandmarker != null) {
            try {
                faceLandmarker.close();
            } catch (Exception ignored) {
                // Safe cleanup.
            }
            faceLandmarker = null;
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
            cameraExecutor = null;
        }
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private void notifyMonitoringStatus(String status) {
        latestServiceStatus = status;
        Listener currentListener = listener;

        if (currentListener != null) {
            mainHandler.post(() -> {
                if (listener == currentListener) {
                    currentListener.onMonitoringStatus(status);
                }
            });
        }
    }

    private void notifyDetectionUpdate() {
        Listener currentListener = listener;
        if (currentListener == null) {
            return;
        }

        String status = latestDetectionStatus;
        String ear = latestEarText;
        String pose = latestHeadPoseText;
        String risk = latestRiskText;
        boolean acknowledge = latestAcknowledgeEnabled;

        mainHandler.post(() -> {
            if (listener == currentListener) {
                currentListener.onDetectionUpdate(
                        status, ear, pose, risk, acknowledge
                );
            }
        });
    }

    @Override
    public void onDestroy() {
        isStopping = true;
        monitoring = false;

        stopAlarm();
        releaseCameraAndDetector();

        listener = null;
        super.onDestroy();
    }
}
