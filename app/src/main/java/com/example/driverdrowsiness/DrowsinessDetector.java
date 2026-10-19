
package com.example.driverdrowsiness;

public class DrowsinessDetector {

    public enum State {
        NORMAL,
        EYES_CLOSED,
        DROWSINESS_WARNING
    }

    private final double earThreshold;
    private final long warningDurationMs;
    private final long recoveryGraceMs = 250;

    private long eyeClosureStartMs = -1;
    private long recoveryStartMs = -1;

    public DrowsinessDetector(
            double earThreshold,
            long warningDurationMs
    ) {
        this.earThreshold = earThreshold;
        this.warningDurationMs = warningDurationMs;
    }

    public synchronized State update(
            double ear,
            long timestampMs
    ) {
        if (ear < earThreshold) {
            recoveryStartMs = -1;

            if (eyeClosureStartMs < 0) {
                eyeClosureStartMs = timestampMs;
            }

            long duration = timestampMs - eyeClosureStartMs;

            if (duration >= warningDurationMs) {
                return State.DROWSINESS_WARNING;
            }

            return State.EYES_CLOSED;
        }

        // A brief noisy EAR reading should not reset the timer.
        if (eyeClosureStartMs >= 0) {
            if (recoveryStartMs < 0) {
                recoveryStartMs = timestampMs;
            }

            if (timestampMs - recoveryStartMs <= recoveryGraceMs) {
                long duration = timestampMs - eyeClosureStartMs;

                if (duration >= warningDurationMs) {
                    return State.DROWSINESS_WARNING;
                }

                return State.EYES_CLOSED;
            }
        }

        // Eyes appear open continuously beyond the grace period.
        eyeClosureStartMs = -1;
        recoveryStartMs = -1;

        return State.NORMAL;
    }

    public synchronized long getClosureDurationMs(
            long timestampMs
    ) {
        if (eyeClosureStartMs < 0) {
            return 0;
        }

        long effectiveTimestamp = recoveryStartMs >= 0
                ? Math.min(timestampMs, recoveryStartMs)
                : timestampMs;

        return Math.max(0, effectiveTimestamp - eyeClosureStartMs);
    }

    public synchronized void reset() {
        eyeClosureStartMs = -1;
        recoveryStartMs = -1;
    }
}
