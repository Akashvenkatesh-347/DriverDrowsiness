
package com.example.driverdrowsiness;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DrowsinessRiskScorerTest {

    private final DrowsinessRiskScorer scorer =
            new DrowsinessRiskScorer();

    @Test
    public void normalSignalsReturnLowRisk() {
        DrowsinessRiskScorer.Result result =
                scorer.calculate(0, false, false);

        assertEquals(0, result.score);
        assertEquals(
                DrowsinessRiskScorer.RiskLevel.LOW,
                result.riskLevel
        );
    }

    @Test
    public void possibleYawnAloneReturnsLowRisk() {
        DrowsinessRiskScorer.Result result =
                scorer.calculate(0, true, false);

        assertEquals(20, result.score);
        assertEquals(
                DrowsinessRiskScorer.RiskLevel.LOW,
                result.riskLevel
        );
    }

    @Test
    public void briefEyeClosureAddsTenPoints() {
        DrowsinessRiskScorer.Result result =
                scorer.calculate(500, false, false);

        assertEquals(10, result.score);
    }

    @Test
    public void yawnAndHeadDownReturnModerateRisk() {
        DrowsinessRiskScorer.Result result =
                scorer.calculate(0, true, true);

        assertEquals(40, result.score);
        assertEquals(
                DrowsinessRiskScorer.RiskLevel.MODERATE,
                result.riskLevel
        );
    }

    @Test
    public void prolongedEyeClosureReturnsHighRisk() {
        DrowsinessRiskScorer.Result result =
                scorer.calculate(1500, false, false);

        assertEquals(60, result.score);
        assertEquals(
                DrowsinessRiskScorer.RiskLevel.HIGH,
                result.riskLevel
        );
    }

    @Test
    public void scoreNeverExceedsOneHundred() {
        DrowsinessRiskScorer.Result result =
                scorer.calculate(2000, true, true);

        assertEquals(100, result.score);
        assertEquals(
                DrowsinessRiskScorer.RiskLevel.HIGH,
                result.riskLevel
        );
    }
}
