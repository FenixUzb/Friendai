package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.friendai.foundation.spatial.Node;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturnAssessmentTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final Node ANCHOR = new Node(0, 64, 0);
    private static final Node STANCE = new Node(1, 62, 0);

    @Test
    void aVerifiedReversibleStairIsSafe() {
        var evidence = evidence(
                List.of(
                        new ReturnAssessment.Leg(STANCE, new Node(1, 63, 0), 0, true, true),
                        new ReturnAssessment.Leg(new Node(1, 63, 0), ANCHOR, 0, true, true)),
                true,
                true);

        var result = ReturnAssessment.assess(evidence, 0);

        assertEquals(ReturnAssessment.Status.SAFE, result.status());
        assertEquals(ANCHOR, result.anchor());
        assertEquals(0, result.requiredSupports());
        assertNull(result.refusal());
    }

    @Test
    void aSafeAlternativeThatSpendsSupportsIsStillSafe() {
        var evidence = evidence(
                List.of(
                        new ReturnAssessment.Leg(STANCE, new Node(1, 63, 0), 2, true, true),
                        new ReturnAssessment.Leg(new Node(1, 63, 0), ANCHOR, 0, true, true)),
                true,
                true);

        var result = ReturnAssessment.assess(evidence, 2);

        assertEquals(ReturnAssessment.Status.SAFE, result.status());
        assertEquals(2, result.requiredSupports());
    }

    @Test
    void aKnownRiseReportsTheSupportShortfall() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 2, true, true)), true, true);

        var result = ReturnAssessment.assess(evidence, 1);

        assertEquals(ReturnAssessment.Status.NEEDS_SUPPORTS, result.status());
        assertEquals(2, result.requiredSupports());
        assertEquals(ReturnAssessment.Refusal.SUPPORTS, result.refusal());
    }

    @Test
    void anObstructedFirstLegIsNamed() {
        var obstructed = new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, false);
        var evidence = evidence(List.of(obstructed), true, true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Status.UNKNOWN_OR_BLOCKED, result.status());
        assertEquals(ReturnAssessment.Refusal.LEG_OBSTRUCTED, result.refusal());
        assertSame(obstructed, result.failedLeg());
    }

    @Test
    void anUnloadedFirstLegIsNamed() {
        var unloaded = new ReturnAssessment.Leg(STANCE, ANCHOR, 0, false, false);
        var evidence = evidence(List.of(unloaded), true, true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.LEG_UNLOADED, result.refusal());
        assertSame(unloaded, result.failedLeg());
    }

    @Test
    void theFirstRefusedLegWinsOverLaterOnes() {
        var obstructed = new ReturnAssessment.Leg(STANCE, new Node(1, 63, 0), 0, true, false);
        var unloaded = new ReturnAssessment.Leg(new Node(1, 63, 0), ANCHOR, 0, false, false);
        var evidence = evidence(List.of(obstructed, unloaded), true, true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.LEG_OBSTRUCTED, result.refusal());
        assertSame(obstructed, result.failedLeg());
    }

    @Test
    void aBrokenChainIsNamed() {
        var stray = new ReturnAssessment.Leg(new Node(9, 63, 9), ANCHOR, 0, true, true);
        var evidence = evidence(List.of(stray), true, true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.LEG_BROKEN_CHAIN, result.refusal());
        assertSame(stray, result.failedLeg());
    }

    @Test
    void aRouteThatDoesNotReachTheAnchorIsNamed() {
        var shortLeg = new ReturnAssessment.Leg(STANCE, new Node(1, 63, 0), 0, true, true);
        var evidence = evidence(List.of(shortLeg), true, true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.ANCHOR_UNREACHED, result.refusal());
        assertSame(shortLeg, result.failedLeg());
    }

    @Test
    void anUnloadedAnchorIsNamed() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, true)), false, true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.ANCHOR_UNLOADED, result.refusal());
        assertNull(result.failedLeg());
    }

    @Test
    void staleEvidenceIsNamed() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, true)), true, false);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.STALE, result.refusal());
    }

    @Test
    void anotherDimensionIsNamed() {
        var evidence = new ReturnAssessment.RouteEvidence(
                "minecraft:overworld",
                "minecraft:the_nether",
                ANCHOR,
                STANCE,
                List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, true)),
                true,
                true);

        var result = ReturnAssessment.assess(evidence, 8);

        assertEquals(ReturnAssessment.Refusal.DIMENSION_CHANGED, result.refusal());
    }

    @Test
    void missingEvidenceIsNotSafe() {
        var result = ReturnAssessment.assess(null, 8);

        assertEquals(ReturnAssessment.Status.UNKNOWN_OR_BLOCKED, result.status());
        assertEquals(ReturnAssessment.Refusal.STALE, result.refusal());
    }

    private static ReturnAssessment.RouteEvidence evidence(
            List<ReturnAssessment.Leg> legs, boolean anchorLoaded, boolean fresh) {
        return new ReturnAssessment.RouteEvidence(DIMENSION, DIMENSION, ANCHOR, STANCE, legs, anchorLoaded, fresh);
    }
}
