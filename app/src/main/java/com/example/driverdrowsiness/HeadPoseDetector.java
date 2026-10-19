
package com.example.driverdrowsiness;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

public class HeadPoseDetector {

    public static class Result {
        public final double rollDegrees;
        public final double headDownScore;
        public final boolean headDown;

        Result(
                double rollDegrees,
                double headDownScore,
                boolean headDown
        ) {
            this.rollDegrees = rollDegrees;
            this.headDownScore = headDownScore;
            this.headDown = headDown;
        }
    }

    private static final int LEFT_EYE_OUTER = 33;
    private static final int RIGHT_EYE_OUTER = 263;
    private static final int NOSE = 1;
    private static final int CHIN = 152;

    private static final double HEAD_DOWN_THRESHOLD = 0.42;
    private static final long HEAD_DOWN_DURATION_MS = 1200;

    private long headDownStartMs = -1;

    public synchronized Result update(
            List<NormalizedLandmark> landmarks,
            long timestampMs
    ) {
        NormalizedLandmark leftEye = landmarks.get(LEFT_EYE_OUTER);
        NormalizedLandmark rightEye = landmarks.get(RIGHT_EYE_OUTER);
        NormalizedLandmark nose = landmarks.get(NOSE);
        NormalizedLandmark chin = landmarks.get(CHIN);

        double eyeDx = rightEye.x() - leftEye.x();
        double eyeDy = rightEye.y() - leftEye.y();

        double rollDegrees = Math.toDegrees(
                Math.atan2(eyeDy, eyeDx)
        );

        double eyeMidY = (leftEye.y() + rightEye.y()) / 2.0;
        double faceHeight = chin.y() - eyeMidY;

        double headDownScore = 0.0;

        if (faceHeight > 0.001) {
            headDownScore =
                    (nose.y() - eyeMidY) / faceHeight;
        }

        /*
         * This is a landmark geometry proxy, not a calibrated
         * 3D head-pose angle. Its threshold must be validated
         * with the actual camera position and phone.
         */
        boolean candidateHeadDown =
                headDownScore > HEAD_DOWN_THRESHOLD;

        if (candidateHeadDown) {
            if (headDownStartMs < 0) {
                headDownStartMs = timestampMs;
            }
        } else {
            headDownStartMs = -1;
        }

        boolean sustainedHeadDown =
                headDownStartMs >= 0
                        && timestampMs - headDownStartMs
                        >= HEAD_DOWN_DURATION_MS;

        return new Result(
                rollDegrees,
                headDownScore,
                sustainedHeadDown
        );
    }

    public synchronized void reset() {
        headDownStartMs = -1;
    }
}
