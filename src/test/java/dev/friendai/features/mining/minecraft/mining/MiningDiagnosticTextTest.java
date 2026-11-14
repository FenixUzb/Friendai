package dev.friendai.features.mining.minecraft.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A refused mining plan names its cause in the summary the model reads, so a generic "unreachable" cannot hide a
 * missing support route, an obstructed way back or an unloaded chunk.
 */
class MiningDiagnosticTextTest {
    @Test
    void routeRefusalsNameTheBlockedWayBack() {
        assertEquals(
                "the way back is obstructed", MiningSession.describeDiagnostic("return-route-obstructed-1,2,3->1,3,3"));
        assertEquals(
                "the way back needs more support blocks than are free",
                MiningSession.describeDiagnostic("return-needs-supports-4-spare-1"));
        assertEquals(
                "the way back crosses an unloaded chunk",
                MiningSession.describeDiagnostic("return-route-unloaded-1,2,3->1,3,3"));
    }

    @Test
    void planRefusalsNameWhatFailed() {
        assertEquals("no route to a stance that can break it", MiningSession.describeDiagnostic("no-path"));
        assertEquals("the planned passage is no longer walkable", MiningSession.describeDiagnostic("cut-passage"));
        assertEquals("the stance cannot see the block", MiningSession.describeDiagnostic("blind-stance"));
        assertEquals("there is no safe place for the drops", MiningSession.describeDiagnostic("platform-refused"));
    }

    @Test
    void aTargetRefusalKeepsItsCause() {
        assertTrue(MiningSession.describeDiagnostic("target-refused-no_tool").contains("no_tool"));
        assertTrue(MiningSession.describeDiagnostic("refused-unsafe_support").contains("unsafe_support"));
    }

    @Test
    void theSentenceNamesTheBlockAndHowMuchWorkIsLeft() {
        assertEquals(
                "the way back is obstructed at 12 63 -8; 4 candidate(s) left; 2 refused",
                MiningSession.detailText("return-route-obstructed", "12 63 -8", 4, 2));
        assertEquals("no route to the block", MiningSession.detailText("no-route", "", 0, 0));
    }

    @Test
    void theSentenceStaysBounded() {
        String longText = MiningSession.detailText("return-route-obstructed", "1 ".repeat(200), 123456, 789);
        assertTrue(longText.length() <= 220, "detail was " + longText.length() + " characters");
    }

    @Test
    void anUnknownTokenIsNotInvented() {
        assertEquals("something-new", MiningSession.describeDiagnostic("something-new"));
        assertFalse(MiningSession.describeDiagnostic("no-route").isBlank());
    }
}
