package dev.friendai.foundation.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.foundation.movement.StepProgress.Verdict;
import dev.friendai.foundation.spatial.Pos;
import org.junit.jupiter.api.Test;

class StepProgressTest {
    private static StepProgress eastwardStep() {
        return new StepProgress(new Pos(0, 64, 0), new Pos(3, 64, 0), 5, 0.15);
    }

    private static Verdict[] sample(StepProgress progress, double x, double y, double z, int ticks) {
        Verdict[] verdicts = new Verdict[ticks];
        for (int i = 0; i < ticks; i++) verdicts[i] = progress.sample(x, y, z);
        return verdicts;
    }

    @Test
    void walkingTowardTheStepCountsAsProgress() {
        StepProgress progress = eastwardStep();
        progress.reset(0.5, 64, 0.5);
        Verdict[] verdicts = new Verdict[10];
        for (int i = 0; i < 10; i++) verdicts[i] = progress.sample(0.5 + (i + 1) * 0.4, 64, 0.5);
        for (Verdict verdict : verdicts) assertEquals(Verdict.MOVING, verdict);
    }

    @Test
    void aStationaryBodyIsNudgedOnceAndThenReportedStalled() {
        StepProgress progress = eastwardStep();
        progress.reset(0.5, 64, 0.5);
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.NUDGE, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.MOVING, progress.sample(0.5, 64, 0.5));
        assertEquals(Verdict.STALLED, progress.sample(0.5, 64, 0.5));
    }

    @Test
    void slidingSidewaysIsNotForwardProgress() {
        StepProgress progress = eastwardStep();
        progress.reset(0.5, 64, 0.5);
        Verdict[] verdicts = sample(progress, 0.5, 64, 9.5, 10);
        assertEquals(Verdict.NUDGE, verdicts[4]);
        assertEquals(Verdict.STALLED, verdicts[9]);
    }

    @Test
    void oscillatingInPlaceIsNotForwardProgress() {
        StepProgress progress = eastwardStep();
        progress.reset(0.5, 64, 0.5);
        Verdict[] verdicts = new Verdict[10];
        double[] positions = {1.2, 0.5, 1.2, 0.5, 0.5, 1.2, 0.5, 1.2, 0.5, 0.5};
        for (int i = 0; i < positions.length; i++) verdicts[i] = progress.sample(positions[i], 64, 0.5);
        assertEquals(Verdict.NUDGE, verdicts[4]);
        assertEquals(Verdict.STALLED, verdicts[9]);
    }

    @Test
    void aReturnToRealProgressClearsTheNudge() {
        StepProgress progress = eastwardStep();
        progress.reset(0.5, 64, 0.5);
        assertEquals(Verdict.NUDGE, sample(progress, 0.5, 64, 0.5, 5)[4]);
        assertEquals(Verdict.MOVING, sample(progress, 2.0, 64, 0.5, 5)[4]);
        assertEquals(Verdict.NUDGE, sample(progress, 2.0, 64, 0.5, 5)[4]);
    }

    @Test
    void aVerticalStepMeasuresHeight() {
        StepProgress progress = new StepProgress(new Pos(0, 64, 0), new Pos(0, 65, 0), 4, 0.15);
        progress.reset(0.5, 64, 0.5);
        Verdict[] rising = new Verdict[4];
        for (int i = 0; i < 4; i++) rising[i] = progress.sample(0.5, 64.1 + i * 0.1, 0.5);
        assertEquals(Verdict.MOVING, rising[3]);

        StepProgress drifting = new StepProgress(new Pos(0, 64, 0), new Pos(0, 65, 0), 4, 0.15);
        drifting.reset(0.5, 64, 0.5);
        Verdict[] sideways = sample(drifting, 3.5, 64, 0.5, 8);
        assertEquals(Verdict.NUDGE, sideways[3]);
        assertEquals(Verdict.STALLED, sideways[7]);
    }

    @Test
    void movingDownCountsAsProgressOnAVerticalDescent() {
        StepProgress progress = new StepProgress(new Pos(0, 65, 0), new Pos(0, 64, 0), 4, 0.15);
        progress.reset(0.5, 65, 0.5);
        for (int tick = 1; tick <= 8; tick++) {
            assertEquals(Verdict.MOVING, progress.sample(0.5, 65 - tick * 0.1, 0.5));
        }
    }

    @Test
    void aDiagonalStepProjectsOntoItsOwnAxis() {
        StepProgress progress = new StepProgress(new Pos(0, 64, 0), new Pos(3, 64, 3), 4, 0.15);
        progress.reset(0.5, 64, 0.5);
        Verdict[] diagonal = new Verdict[4];
        for (int i = 0; i < 4; i++) diagonal[i] = progress.sample(0.5 + (i + 1) * 0.4, 64, 0.5 + (i + 1) * 0.4);
        assertEquals(Verdict.MOVING, diagonal[3]);

        StepProgress sideways = new StepProgress(new Pos(0, 64, 0), new Pos(3, 64, 3), 4, 0.15);
        sideways.reset(0.5, 64, 0.5);
        // Perpendicular to the diagonal axis: as far from the line as the start, so no forward progress.
        Verdict[] drift = sample(sideways, 3.5, 64, -2.5, 8);
        assertEquals(Verdict.NUDGE, drift[3]);
        assertEquals(Verdict.STALLED, drift[7]);
    }

    @Test
    void rejectsAnEmptyWindowOrNonPositiveMinimum() {
        Pos from = new Pos(0, 64, 0);
        Pos to = new Pos(1, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> new StepProgress(from, to, 0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new StepProgress(from, to, 4, 0.0));
    }
}
