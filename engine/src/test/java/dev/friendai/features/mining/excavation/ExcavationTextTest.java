package dev.friendai.features.mining.excavation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The started sentence names the effective cap, and a request the region could not honour is reported instead of
 * looking like a silent reduction.
 */
class ExcavationTextTest {
    private static ExcavationSpec shaft(int length, int count) {
        ExcavationGeometry geometry = ExcavationGeometry.shaft(ExcavationDirection.DOWN, 0, 64, 0, length, 1, 1)
                .orElseThrow();
        return ExcavationSpec.of(geometry, count, null, 0, true, false, false, true, 0)
                .orElseThrow();
    }

    @Test
    void anExactRequestIsNotDecorated() {
        ExcavationSpec spec = shaft(24, 24);

        String text = ExcavationText.describe(spec);

        assertTrue(text.contains("(cap 24)"), text);
        assertFalse(text.contains("requested"), text);
    }

    @Test
    void aCappedRequestNamesBothNumbers() {
        ExcavationSpec spec = shaft(24, 40);

        String text = ExcavationText.describe(spec, 40);

        assertTrue(text.contains("(cap 24)"), text);
        assertTrue(text.contains("requested 40"), text);
        assertTrue(text.contains("24 cells"), text);
        assertTrue(text.contains("capped"), text);
    }

    @Test
    void anUnspecifiedCountReportsTheRegionWithoutARequestNumber() {
        ExcavationSpec spec = shaft(24, 0);

        assertFalse(ExcavationText.describe(spec).contains("requested"));
    }
}
