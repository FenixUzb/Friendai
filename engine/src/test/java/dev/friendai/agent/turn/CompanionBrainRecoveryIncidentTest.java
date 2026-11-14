package dev.friendai.agent.turn;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One blocked job cannot spawn a chain of unrelated work: after the shared budget is spent the turn that carries
 * the next failure is answered in words, names one blocker, and offers no tool. A changed world fact or an explicit
 * owner line makes another attempt eligible, and unrelated news keeps its tools.
 */
class CompanionBrainRecoveryIncidentTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private RecordingSpeaker speaker;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor();
        speaker = new RecordingSpeaker();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                tools,
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<UUID>()::contains));
    }

    /** A plan stops at job #12 for a target it cannot reach. */
    private void postBlockedPlan() {
        brain.post(new BrainEvent.PlanBlocked(7, "mine stone", 12, "go_to", "couldn't reach the target", 5));
    }

    /** One stand-alone job fails while the blocked plan is still open: a remedy of it, not a new incident. */
    private void postFailedRemedy(int taskId, String extra) {
        brain.post(new BrainEvent.TaskFinished(taskId, 1, 0, "excavate", false, "can't reach the ore", extra));
    }

    /** Drives the incident to its budget: the block plus two failed remedies. */
    private void spendTheIncident() {
        gateway.enqueue(text("I will reach it another way."));
        gateway.enqueue(text("That did not work either."));
        gateway.enqueue(text("Let me try once more."));
        postBlockedPlan();
        postFailedRemedy(1, "facts: +1 cobblestone");
        postFailedRemedy(2, "facts: +1 cobblestone");
    }

    @Test
    void aSpentIncidentAnswersInWordsWithOneBlockerAndNoTool() {
        gateway.enqueue(text("I will reach it another way."));
        gateway.enqueue(text("That did not work either."));
        gateway.enqueue(text("[silent]"));
        postBlockedPlan();
        postFailedRemedy(1, "facts: +1 cobblestone");
        postFailedRemedy(2, "facts: +1 cobblestone");

        assertEquals(3, gateway.requests.size());
        assertFalse(gateway.requests.get(0).tools().isEmpty(), "the block itself is still worked on");
        assertFalse(gateway.requests.get(1).tools().isEmpty(), "one failed remedy keeps the tools");
        assertTrue(gateway.requests.get(2).tools().isEmpty(), "the third failure pauses the incident");
        String failureLine = lastUserText(gateway.requests.get(2));
        assertTrue(failureLine.contains("[recovery] paused at plan #7 job #12: target not reachable"), failureLine);
        assertTrue(failureLine.contains("3 attempts made no progress"), failureLine);
        assertEquals(List.of("I will reach it another way.", "That did not work either."), speaker.said);
        assertEquals(1, speaker.messages.size(), "a silent model answer still gives the owner the blocker");
        assertEquals(
                "friendai.companion.recovery_blocked", speaker.messages.get(0).key());
        assertEquals(List.of("target not reachable"), speaker.messages.get(0).args());
        assertFalse(brain.isThinking());
    }

    @Test
    void aToolCallInThePausedTurnIsNotExecuted() {
        gateway.enqueue(text("I will reach it another way."));
        gateway.enqueue(text("That did not work either."));
        gateway.enqueue(calls("[silent]", call("c1", "queue_add")));
        postBlockedPlan();
        postFailedRemedy(1, "facts: +1 cobblestone");
        postFailedRemedy(2, "facts: +1 cobblestone");

        assertEquals(3, gateway.requests.size(), "a refused call must not earn another step");
        assertTrue(tools.executed.isEmpty(), "the paused turn starts no work");
        assertEquals(1, speaker.messages.size(), "the owner still hears what blocks it");
    }

    @Test
    void aChangedFactMakesAnotherAttemptEligible() {
        spendTheIncident();
        gateway.enqueue(text("Now I can reach it."));

        postFailedRemedy(3, "facts: +1 cobblestone, +4 oak_log");

        assertEquals(4, gateway.requests.size());
        assertFalse(gateway.requests.get(3).tools().isEmpty(), "a new fact is a changed world, not the same blocker");
        assertFalse(lastUserText(gateway.requests.get(3)).contains("[recovery] paused"));
    }

    @Test
    void theOwnersOwnLineMakesAnotherAttemptEligible() {
        spendTheIncident();
        gateway.enqueue(text("Then I will ask you for a different spot."));

        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "try from the other side", BrainEvent.Source.CHAT));

        assertEquals(4, gateway.requests.size());
        assertFalse(gateway.requests.get(3).tools().isEmpty());
    }

    @Test
    void unrelatedNewsKeepsItsToolsAfterThePause() {
        spendTheIncident();
        gateway.enqueue(text("Noted."));

        brain.post(new BrainEvent.WorldNotice("the furnace finished"));

        assertEquals(4, gateway.requests.size());
        assertFalse(gateway.requests.get(3).tools().isEmpty(), "another job is not part of this incident");
    }

    @Test
    void aSettledPlanClosesItsIncident() {
        spendTheIncident();
        gateway.enqueue(text("The plan is done."));
        gateway.enqueue(text("A new attempt."));

        brain.post(new BrainEvent.PlanFinished(7, "mine stone", true, "done"));
        postFailedRemedy(3, "facts: +1 cobblestone");

        assertEquals(5, gateway.requests.size());
        assertFalse(gateway.requests.get(4).tools().isEmpty(), "a fresh incident starts with its own budget");
        assertFalse(lastUserText(gateway.requests.get(4)).contains("[recovery] paused"));
    }

    @Test
    void aManualRepairRequestMakesAnotherAttemptEligible() {
        spendTheIncident();
        gateway.enqueue(text("Repairing the plan."));
        gateway.enqueue(text("The repair did not hold."));

        brain.post(new BrainEvent.Replan(7, "manual repair"));
        postFailedRemedy(3, "facts: +1 cobblestone");

        assertEquals(5, gateway.requests.size());
        assertFalse(gateway.requests.get(3).tools().isEmpty(), "an explicit repair request reopens the work");
        assertFalse(
                lastUserText(gateway.requests.get(4)).contains("[recovery] paused"),
                "the repair started the budget again, so one more failure is inside it");
        assertFalse(gateway.requests.get(4).tools().isEmpty());
    }

    @Test
    void aStopEndsTheEpisodeAndAFreshOneGetsItsOwnBudget() {
        spendTheIncident();

        brain.cancel();
        gateway.enqueue(text("Trying again after the stop."));
        postFailedRemedy(3, "facts: +1 cobblestone");

        assertEquals(4, gateway.requests.size(), "a late event after a stop still starts one fresh turn");
        assertFalse(gateway.requests.get(3).tools().isEmpty(), "the stopped episode cannot gate the new one");
    }

    private static String lastUserText(FakeGateway.Request request) {
        List<Message> messages = request.messages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).role() == Role.USER) {
                return messages.get(index).text();
            }
        }
        return "";
    }
}
