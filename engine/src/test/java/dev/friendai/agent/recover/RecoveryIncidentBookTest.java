package dev.friendai.agent.recover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One blocked job keeps one incident: another tool, another wording or a different stack size is the same blocker,
 * while a fact the book has not seen is real progress. A spent incident pauses and names what it tried.
 */
class RecoveryIncidentBookTest {
    private RecoveryIncidentBook book;

    @BeforeEach
    void setUp() {
        book = new RecoveryIncidentBook(new RecoveryAdvisor());
    }

    private void blockAtStone() {
        book.blocked(7, 12, "go_to", "couldn't reach the target", "facts: +1 cobblestone");
    }

    @Test
    void anotherToolAndWordingChargeTheSameBlockedIncident() {
        blockAtStone();
        book.failed(0, "excavate", "couldn't reach the first stair cell", "facts: +1 cobblestone");
        book.failed(0, "search_resource", "can't reach the ore from here", "facts: +1 cobblestone");

        RecoveryIncidentBook.View view = book.active().orElseThrow();
        assertEquals(7, view.planId());
        assertEquals(12, view.jobId());
        assertEquals(FailureKind.UNREACHABLE, view.kind());
        assertEquals(3, view.failures(), "a stand-alone job is a remedy of the blocked plan, not a new incident");
        assertEquals(List.of("go_to", "excavate", "search_resource"), view.remedies());
        assertTrue(book.paused());
    }

    @Test
    void aDifferentStackSizeIsNotProgress() {
        blockAtStone();
        book.failed(0, "mine_blocks", "can't reach the ore", "facts: +2 cobblestone");

        assertFalse(book.paused(), "two failures are inside the budget");
        assertEquals(2, book.active().orElseThrow().failures());
    }

    @Test
    void aFactTheIncidentHasNotSeenRestartsTheBudget() {
        blockAtStone();
        book.failed(0, "excavate", "can't reach the ore", "facts: +1 cobblestone");
        book.failed(0, "mine_blocks", "can't reach the ore", "facts: +1 cobblestone");
        assertTrue(book.paused(), "three attempts without progress");

        book.failed(0, "mine_blocks", "can't reach the ore", "facts: +1 cobblestone, +4 oak_log");

        assertFalse(book.paused(), "a new fact is a changed world and the companion may try again");
        assertEquals(1, book.active().orElseThrow().failures());
    }

    @Test
    void thePauseLineNamesTheCauseTheAttemptsAndTheOwnerDecision() {
        blockAtStone();
        book.failed(0, "excavate", "can't reach the ore", "facts: +1 cobblestone");
        book.failed(0, "mine_blocks", "can't reach the ore", "facts: +1 cobblestone");

        String line = book.modelLine();

        assertTrue(line.startsWith("[recovery] paused at plan #7 job #12: target not reachable"), line);
        assertTrue(line.contains("3 attempts made no progress"), line);
        assertTrue(line.contains("go_to, excavate, mine_blocks"), line);
        assertTrue(line.contains("decision you need from the owner"), line);
        assertEquals("target not reachable", book.detail());
    }

    @Test
    void anOwnerLineMakesAnotherAttemptEligible() {
        blockAtStone();
        book.failed(0, "excavate", "can't reach the ore", "facts: +1 cobblestone");
        book.failed(0, "mine_blocks", "can't reach the ore", "facts: +1 cobblestone");
        assertTrue(book.paused());

        book.ownerSpoke();

        assertFalse(book.paused());
        assertEquals(0, book.active().orElseThrow().failures());
        assertTrue(book.active().orElseThrow().remedies().isEmpty());
    }

    @Test
    void aSettledPlanClosesItsIncident() {
        blockAtStone();
        book.failed(0, "excavate", "can't reach the ore", "facts: +1 cobblestone");

        book.settled(7);

        assertTrue(book.active().isEmpty());
        assertFalse(book.paused());
    }

    @Test
    void aSuccessfulRemedyStartsTheBudgetAgain() {
        blockAtStone();
        book.failed(0, "obtain_item", "can't reach the ore", "facts: +1 cobblestone");
        book.succeeded(0);

        book.failed(0, "excavate", "can't reach the ore", "facts: +1 cobblestone");

        assertEquals(1, book.active().orElseThrow().failures(), "a success is progress, not a spent budget");
        assertFalse(book.paused());
    }

    @Test
    void theIncidentJobSucceedingClosesTheIncident() {
        blockAtStone();
        book.failed(7, "mine_blocks", "can't reach the ore", "facts: +1 cobblestone");

        book.succeeded(7);

        assertTrue(book.active().isEmpty());
    }

    @Test
    void aPlannerConsultationThatProducedNothingCountsAsASpentRemedy() {
        blockAtStone();
        book.noProgress(7);
        book.noProgress(7);

        assertTrue(book.paused());
        assertTrue(book.active().orElseThrow().remedies().contains("planner"));
    }

    @Test
    void aRepairStartsTheBudgetAgain() {
        blockAtStone();
        book.noProgress(7);
        book.noProgress(7);
        assertTrue(book.paused());

        book.repaired(7);

        assertFalse(book.paused());
        assertEquals(0, book.active().orElseThrow().failures());
    }

    @Test
    void directFailuresGroupByTypedCauseNotByTool() {
        book.failed(0, "go_to", "couldn't reach the ledge", "");
        book.failed(0, "excavate", "can't reach the ledge at 40 64 -20", "");

        assertEquals(2, book.active().orElseThrow().failures(), "the same cause at shifted coordinates");
        assertEquals(FailureKind.UNREACHABLE, book.active().orElseThrow().kind());

        book.failed(0, "obtain_item", "can't get cobblestone", "");

        assertEquals(FailureKind.RESOURCE_NOT_FOUND, book.active().orElseThrow().kind());
        assertEquals(1, book.active().orElseThrow().failures(), "a different cause is its own incident");
    }

    @Test
    void anUnclassifiableFailureFormsNoIncident() {
        book.failed(0, "stay", "something odd happened", "");

        assertTrue(book.active().isEmpty());
    }

    @Test
    void aCancellationEndsTheEpisode() {
        blockAtStone();
        book.failed(0, "excavate", "can't reach the ore", "");

        book.cancelled();

        assertTrue(book.active().isEmpty());
    }

    @Test
    void repeatedBlocksAtOneJobChargeOneIncident() {
        book.blocked(7, 12, "go_to", "can't reach the target", "facts: +1 cobblestone");
        book.blocked(7, 12, "go_to", "can't reach the target", "facts: +1 cobblestone");

        assertEquals(2, book.active().orElseThrow().failures());
    }

    @Test
    void aSecondBlockedPlanIsAFailedRemedyOfTheSameEpisode() {
        book.blocked(7, 12, "go_to", "can't reach the target", "");
        book.blocked(9, 1, "excavate", "can't reach the target", "");

        RecoveryIncidentBook.View view = book.active().orElseThrow();
        assertEquals(7, view.planId(), "the episode keeps the plan and the job the owner is waiting on");
        assertEquals(12, view.jobId());
        assertEquals(2, view.failures());
        assertEquals(List.of("go_to", "excavate"), view.remedies());
    }

    @Test
    void incidentsStayBounded() {
        for (int item = 0; item < 12; item++) {
            book.failed(0, "craft_item", "can't craft minecraft:item_" + item, "");
        }

        assertEquals(RecoveryIncidentBook.MAX_INCIDENTS, book.size(), "a long session cannot grow this book");
        assertEquals("cannot craft minecraft:item_11", book.detail());
    }
}
