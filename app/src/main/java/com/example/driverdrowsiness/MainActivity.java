
package com.example.driverdrowsiness;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

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

public class MainActivity extends AppCompatActivity {

    private PreviewView cameraPreview;
    private TextView statusText;
    private TextView earText;
    private TextView headPoseText;
    private TextView riskScoreText;
    private Button acknowledgeButton;

    private ExecutorService cameraExecutor;
    private FaceLandmarker faceLandmarker;

    private Ringtone alarmRingtone;
    private Vibrator vibrator;

    private final EarSmoother leftEarSmoother = new EarSmoother();
    private final EarSmoother rightEarSmoother = new EarSmoother();

    private final DrowsinessDetector drowsinessDetector =
            new DrowsinessDetector(0.18, 1500);

    private final HeadPoseDetector headPoseDetector =
            new HeadPoseDetector();

    // Yawn detection and risk scoring are display-only.
    private final YawnDetector yawnDetector = new YawnDetector();
    private final DrowsinessRiskScorer riskScorer = new DrowsinessRiskScorer();

    private final AtomicLong lastFrameTime = new AtomicLong(0);

    private volatile boolean isClosed = false;
    private volatile boolean isProcessing = false;
    private volatile boolean warningAcknowledged = false;

    private static final int[] LEFT_EYE =
            {362, 385, 387, 263, 373, 380};

    private static final int[] RIGHT_EYE =
            {33, 160, 158, 133, 153, 144};

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startCamera();
                        } else {
                            statusText.setText("Camera permission required");
                        }
                    }
            );

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraPreview = findViewById(R.id.cameraPreview);
        statusText = findViewById(R.id.statusText);
        earText = findViewById(R.id.earText);
        headPoseText = findViewById(R.id.headPoseText);
        riskScoreText = findViewById(R.id.riskScoreText);
        acknowledgeButton = findViewById(R.id.acknowledgeButton);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        acknowledgeButton.setEnabled(false);
        acknowledgeButton.setOnClickListener(view -> {
            warningAcknowledged = true;
            stopAlarm();
            statusText.setText("Warning acknowledged. Stay alert.");
            acknowledgeButton.setEnabled(false);
        });

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            setupFaceLandmarker();
        } catch (Exception e) {
            statusText.setText(
                    "MediaPipe initialization failed: " + e.getMessage()
            );
            return;
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void setupFaceLandmarker() throws IOException {
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
                        .setErrorListener(error -> runOnUiThread(() -> {
                            if (!isFinishing()) {
                                statusText.setText(
                                        "Detection error: " + error.getMessage()
                                );
                            }
                        }))
                        .build();

        faceLandmarker = FaceLandmarker.createFromOptions(this, options);
    }

    private void onLandmarksDetected(
            FaceLandmarkerResult result,
            MPImage inputImage
    ) {
        if (isClosed || isFinishing()) {
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

            runOnUiThread(() -> {
                if (!isFinishing()) {
                    statusText.setText("No face detected");
                    earText.setText("EAR: --\nMAR: --");
                    headPoseText.setText("Head pose: --");
                    riskScoreText.setText("Experimental risk score: --");
                    acknowledgeButton.setEnabled(false);
                }
            });
            return;
        }

        List<NormalizedLandmark> landmarks = faces.get(0);
        long now = SystemClock.uptimeMillis();

        // Calculate eye aspect ratios.
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

        // Existing eye-closure detection.
        DrowsinessDetector.State eyeState =
                drowsinessDetector.update(averageEar, now);

        long closureDuration =
                drowsinessDetector.getClosureDurationMs(now);

        // Yawn detection. A confirmed possible yawn now shares the existing alarm.
        YawnDetector.Result yawnResult = yawnDetector.update(
                landmarks,
                inputImage.getWidth(),
                inputImage.getHeight(),
                now
        );

        // Existing landmark-based head-pose estimate.
        HeadPoseDetector.Result landmarkPose =
                headPoseDetector.update(landmarks, now);

        // Experimental score combines signals for display only.
        // It is deliberately not connected to the existing alarm logic.
        DrowsinessRiskScorer.Result riskResult = riskScorer.calculate(
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

        // Eye closure and a detected yawn share the existing alarm.
        // Acknowledgement silences the alarm until both warning conditions clear.
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

        // Display EAR and the preliminary yawn-detection measurements.
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

        // Existing provisional matrix-derived head-pose display.
        Optional<List<float[]>> matricesOptional =
                result.facialTransformationMatrixes();

        String matrixPoseMessage;

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

        boolean showAcknowledge = activeWarning && !warningAcknowledged;

        runOnUiThread(() -> {
            if (!isFinishing()) {
                statusText.setText(stateMessage);
                earText.setText(earMessage);
                headPoseText.setText(matrixPoseMessage);
                riskScoreText.setText(riskMessage);
                acknowledgeButton.setEnabled(showAcknowledge);
            }
        });
    }

    /*
     * Matrix is a flattened 4x4 column-major array.
     * These Euler angles are provisional and must be validated
     * against real head movements before being used for warnings.
     */
    private PoseAngles extractPoseAngles(float[] m) {
        double r00 = m[0];
        double r10 = m[1];
        double r20 = m[2];

        double r01 = m[4];
        double r11 = m[5];
        double r21 = m[6];

        double r02 = m[8];
        double r12 = m[9];
        double r22 = m[10];

        double pitch;
        double yaw;
        double roll;

        double sinPitch = Math.max(
                -1.0, Math.min(1.0, -r12)
        );

        pitch = Math.asin(sinPitch);

        if (Math.abs(Math.cos(pitch)) > 1e-6) {
            yaw = Math.atan2(r02, r22);
            roll = Math.atan2(r10, r11);
        } else {
            // Near the Euler-angle singularity.
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
        if (isClosed) {
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
                            getApplicationContext(), alarmUri
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
            // Audio or vibration failure should not crash detection.
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

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setOutputImageFormat(
                                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis
                );

                statusText.setText("Camera running | Looking for face");

            } catch (Exception e) {
                statusText.setText(
                        "Camera setup failed: " + e.getMessage()
                );
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(ImageProxy imageProxy) {
        if (isClosed || isProcessing || faceLandmarker == null) {
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
                                    imageProxy.getImageInfo().getRotationDegrees()
                            )
                            .build();

            faceLandmarker.detectAsync(mpImage, processingOptions, now);

        } catch (Exception e) {
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    statusText.setText("Frame error: " + e.getMessage());
                }
            });
        } finally {
            imageProxy.close();
            isProcessing = false;
        }
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

    @Override
    protected void onDestroy() {
        isClosed = true;
        stopAlarm();

        if (faceLandmarker != null) {
            faceLandmarker.close();
            faceLandmarker = null;
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        super.onDestroy();
    }
}
