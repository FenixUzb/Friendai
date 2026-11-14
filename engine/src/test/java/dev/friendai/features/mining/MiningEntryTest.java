package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.testutil.GridCellView;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The first edge of a target is a world fact: an open neighbouring stance, an unsupported step, or a typed refusal.
 * Every case is a static grid, so the answer is the geometry and nothing else.
 */
class MiningEntryTest {
    private static final Pos TARGET = new Pos(1, 1, 1);

    /** A floor, a target with one open side, and air above. */
    private static GridCellView openToTheWest() {
        return new GridCellView("###/###/###", "###/.##/###", ".../.../...");
    }

    @Test
    void anOpenNeighbourIsAStance() {
        MiningEntry.Verdict verdict = MiningEntry.check(openToTheWest(), TARGET, Set.of(), false);

        assertTrue(verdict.ready());
        assertEquals(new Node(0, 1, 1), verdict.stance(), "the open western cell is the stance");
        assertEquals("", verdict.label());
        assertNull(verdict.refusal());
    }

    @Test
    void anEnclosedTargetHasNoStance() {
        GridCellView solid = new GridCellView("###/###/###", "###/###/###", "###/###/###");

        MiningEntry.Verdict verdict = MiningEntry.check(solid, TARGET, Set.of(), false);

        assertEquals(MiningEntry.Refusal.NO_STANCE, verdict.refusal());
        assertEquals("no stance open beside it", verdict.label());
        assertNull(verdict.stance(), "a refusal names no stance");
    }

    @Test
    void aStepWhoseFloorIsOpenIsUnsupported() {
        GridCellView hill = new GridCellView("###/#.#/###", "###/###/###", ".../.../...", ".../.../...");

        MiningEntry.Verdict verdict = MiningEntry.check(hill, TARGET, Set.of(), true);

        assertEquals(MiningEntry.Refusal.STEP_UNSUPPORTED, verdict.refusal());
        assertFalse(verdict.ready());
    }

    @Test
    void anUnloadedTargetStaysUnknown() {
        MiningEntry.Verdict verdict = MiningEntry.check(openToTheWest(), new Pos(9, 9, 9), Set.of(), false);

        assertEquals(MiningEntry.Refusal.UNLOADED, verdict.refusal());
    }

    @Test
    void aProtectedTargetIsRefusedByName() {
        MiningEntry.Verdict verdict = MiningEntry.check(openToTheWest(), TARGET, Set.of(TARGET), false);

        assertEquals(MiningEntry.Refusal.PROTECTED, verdict.refusal());
        assertEquals("protected", verdict.label());
    }

    @Test
    void anUnbreakableBlockIsRefused() {
        GridCellView bedrock = new GridCellView("###/###/###", "###/#p#/###", ".../.../...");

        MiningEntry.Verdict verdict = MiningEntry.check(bedrock, TARGET, Set.of(), false);

        assertEquals(MiningEntry.Refusal.NOT_BREAKABLE, verdict.refusal());
    }

    @Test
    void lavaBesideTheTargetMakesTheBreakUnsafe() {
        GridCellView lava = new GridCellView("###/###/###", "###/.#L/###", ".../.../...");

        MiningEntry.Verdict verdict = MiningEntry.check(lava, TARGET, Set.of(), false);

        assertEquals(MiningEntry.Refusal.UNSAFE, verdict.refusal());
    }

    @Test
    void aVerdictNamesEitherAStanceOrACause() {
        assertThrows(IllegalArgumentException.class, () -> new MiningEntry.Verdict(null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MiningEntry.Verdict(MiningEntry.Refusal.NO_STANCE, new Node(0, 1, 1)));
    }
}
