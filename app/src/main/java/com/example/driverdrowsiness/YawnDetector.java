
package com.example.driverdrowsiness;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

public class YawnDetector {

    // MediaPipe Face Landmarker mouth landmark indices.
    private static final int UPPER_LIP = 13;
    private static final int LOWER_LIP = 14;
    private static final int LEFT_MOUTH_CORNER = 78;
    private static final int RIGHT_MOUTH_CORNER = 308;

    // Initial experimental threshold; validate against live readings.
    private static final double MAR_THRESHOLD = 0.60;

    // Mouth must remain open this long to count as a possible yawn.
    private static final long YAWN_DURATION_MS = 800;

    private long mouthOpenStartMs = -1;

    public static class Result {
        public final double mar;
        public final boolean mouthOpen;
        public final boolean possibleYawn;

        Result(double mar, boolean mouthOpen, boolean possibleYawn) {
            this.mar = mar;
            this.mouthOpen = mouthOpen;
            this.possibleYawn = possibleYawn;
        }
    }

    public synchronized Result update(
            List<NormalizedLandmark> landmarks,
            int imageWidth,
            int imageHeight,
            long timestampMs
    ) {
        if (landmarks == null
                || landmarks.size() <= RIGHT_MOUTH_CORNER
                || imageWidth <= 0
                || imageHeight <= 0) {
            reset();
            return new Result(0.0, false, false);
        }

        NormalizedLandmark upper = landmarks.get(UPPER_LIP);
        NormalizedLandmark lower = landmarks.get(LOWER_LIP);
        NormalizedLandmark left = landmarks.get(LEFT_MOUTH_CORNER);
        NormalizedLandmark right = landmarks.get(RIGHT_MOUTH_CORNER);

        double verticalOpening = distance(
                upper, lower, imageWidth, imageHeight
        );

        double horizontalWidth = distance(
                left, right, imageWidth, imageHeight
        );

        if (horizontalWidth < 1e-6) {
            reset();
            return new Result(0.0, false, false);
        }

        double mar = verticalOpening / horizontalWidth;
        boolean mouthOpen = mar >= MAR_THRESHOLD;

        if (mouthOpen) {
            if (mouthOpenStartMs < 0) {
                mouthOpenStartMs = timestampMs;
            }
        } else {
            mouthOpenStartMs = -1;
        }

        boolean possibleYawn = mouthOpen
                && mouthOpenStartMs >= 0
                && timestampMs - mouthOpenStartMs >= YAWN_DURATION_MS;

        return new Result(mar, mouthOpen, possibleYawn);
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

    public synchronized void reset() {
        mouthOpenStartMs = -1;
    }
}
