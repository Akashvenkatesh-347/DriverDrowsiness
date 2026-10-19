
package com.example.driverdrowsiness;

public class DrowsinessRiskScorer {

    public enum RiskLevel {
        LOW,
        MODERATE,
        HIGH
    }

    public static class Result {
        public final int score;
        public final RiskLevel riskLevel;

        private Result(int score, RiskLevel riskLevel) {
            this.score = score;
            this.riskLevel = riskLevel;
        }
    }

    /**
     * Calculates an experimental score from 0 to 100.
     *
     * This score is for prototype evaluation only.
     * It must not control the existing alarm.
     *
     * @param eyeClosureDurationMs current eye-closure duration
     * @param possibleYawn whether the yawn detector reports a possible yawn
     * @param sustainedHeadDown whether the head-down detector reports this state
     */
    public Result calculate(
            long eyeClosureDurationMs,
            boolean possibleYawn,
            boolean sustainedHeadDown
    ) {
        int score = 0;

        // Eye closure contributes progressively to the score.
        if (eyeClosureDurationMs >= 1500) {
            score += 60;
        } else if (eyeClosureDurationMs >= 800) {
            score += 30;
        } else if (eyeClosureDurationMs >= 300) {
            score += 10;
        }

        // Yawning and head pose are preliminary signals.
        if (possibleYawn) {
            score += 20;
        }

        if (sustainedHeadDown) {
            score += 20;
        }

        score = Math.min(score, 100);

        RiskLevel level;

        if (score >= 60) {
            level = RiskLevel.HIGH;
        } else if (score >= 30) {
            level = RiskLevel.MODERATE;
        } else {
            level = RiskLevel.LOW;
        }

        return new Result(score, level);
    }
}
