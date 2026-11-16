package dev.friendai.agent.turn;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A companion recreated after a reload keeps the last few delivered lines of its owner's conversation, so a short
 * follow-up can refer to the exchange before it. The seed is bounded, principals never share it, and clearing the
 * transcript removes it from the live conversation too.
 */
class CompanionBrainHistoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private FakeGateway gateway;
    private RecordingSpeaker speaker;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        speaker = new RecordingSpeaker();
        brain = newBrain(RecentConversationSource.NONE);
    }

    private CompanionBrain newBrain(RecentConversationSource source) {
        CompanionBrain created = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                new FakeToolExecutor(),
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<UUID>()::contains));
        created.setRecentConversationSource(source);
        return created;
    }

    private static BrainEvent said(UUID player, String name, String text) {
        return new BrainEvent.PlayerSaid(player, name, text, BrainEvent.Source.CHAT);
    }

    private static RecentConversationSource remembering(String... lines) {
        return (principal, maxMessages) -> {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < lines.length; i += 2) {
                messages.add(Message.user(lines[i]));
                if (i + 1 < lines.length) {
                    messages.add(Message.assistant(lines[i + 1], List.of()));
                }
            }
            return messages;
        };
    }

    @Test
    void aFollowUpSeesTheExchangeDeliveredBeforeTheRecreation() {
        brain = newBrain(remembering("mine 3 stone", "On it."));
        gateway.enqueue(text("It is done."));

        brain.post(said(OWNER, "Steve", "did you finish?"));

        List<Message> history = gateway.requests.getFirst().messages();
        assertEquals(3, history.size(), history.toString());
        assertEquals(Role.USER, history.get(0).role());
        assertEquals("mine 3 stone", history.get(0).text());
        assertEquals(Role.ASSISTANT, history.get(1).role());
        assertEquals("On it.", history.get(1).text());
        assertEquals(Role.USER, history.get(2).role());
        assertTrue(history.get(2).text().contains("did you finish?"));
        assertEquals(List.of("It is done."), speaker.said);
    }

    @Test
    void theSeedIsReadOncePerPrincipal() {
        AtomicInteger reads = new AtomicInteger();
        brain = newBrain((principal, maxMessages) -> {
            reads.incrementAndGet();
            return List.of(Message.user("earlier"));
        });
        gateway.enqueue(text("first"));
        gateway.enqueue(text("second"));

        brain.post(said(OWNER, "Steve", "one"));
        brain.post(said(OWNER, "Steve", "two"));

        assertEquals(1, reads.get(), "an existing conversation is not rebuilt on every turn");
        assertEquals(4, gateway.requests.get(1).messages().size(), "two turns on the same seeded history");
    }

    @Test
    void aGuestConversationIsNeverRestored() {
        AtomicInteger reads = new AtomicInteger();
        brain = newBrain((principal, maxMessages) -> {
            reads.incrementAndGet();
            return List.of(Message.user("owner-only words"));
        });
        gateway.enqueue(text("hello"));

        brain.post(said(GUEST, "Alex", "hi"));

        assertEquals(0, reads.get(), "a guest's history never reaches the source");
        assertEquals(1, gateway.requests.getFirst().messages().size());
    }

    @Test
    void aFailingSourceCostsOnlyTheContinuity() {
        brain = newBrain((principal, maxMessages) -> {
            throw new IllegalStateException("transcript unavailable");
        });
        gateway.enqueue(text("still here"));

        brain.post(said(OWNER, "Steve", "hello"));

        assertEquals(List.of("still here"), speaker.said);
        assertEquals(1, gateway.requests.getFirst().messages().size());
    }

    @Test
    void onlyPlayerAndSpokenTextIsRestored() {
        brain = newBrain((principal, maxMessages) -> List.of(
                Message.user("what did you mine?"),
                Message.assistant("cobblestone.", List.of(FakeGateway.call("c1", "mine_blocks"))),
                Message.toolResult("c1", "mine_blocks", "{\"ok\":true}"),
                Message.assistant("spoken line", List.of())));
        gateway.enqueue(text("and then?"));

        brain.post(said(OWNER, "Steve", "and then?"));

        List<Message> history = gateway.requests.getFirst().messages();
        assertEquals(3, history.size(), history.toString());
        assertEquals("what did you mine?", history.get(0).text());
        assertTrue(history.get(1).toolCalls().isEmpty(), "a restored assistant line never carries a tool call");
        assertEquals("spoken line", history.get(1).text());
        assertEquals(
                0,
                history.stream().filter(message -> message.role() == Role.TOOL).count());
    }

    @Test
    void clearingTheTranscriptRemovesTheLiveContext() {
        brain = newBrain(remembering("mine 3 stone", "On it."));
        gateway.enqueue(text("done"));
        gateway.enqueue(text("fresh start"));
        brain.post(said(OWNER, "Steve", "did you finish?"));

        brain.clearConversationHistory();
        brain.post(said(OWNER, "Steve", "what now?"));

        List<Message> history = gateway.requests.get(1).messages();
        assertEquals(1, history.size(), "no line from the cleared conversation survives: " + history);
        assertTrue(history.getFirst().text().contains("what now?"));
        assertFalse(brain.isThinking());
    }

    @Test
    void clearingDuringATurnAppliesWhenTheTurnEnds() {
        brain = newBrain(remembering("mine 3 stone", "On it."));
        CompletableFuture<dev.friendai.agent.llm.model.LlmResponse> pending = gateway.enqueuePending();
        brain.post(said(OWNER, "Steve", "did you finish?"));
        assertTrue(brain.isThinking());

        brain.clearConversationHistory();
        gateway.enqueue(text("fresh start"));
        pending.complete(text("done"));

        brain.post(said(OWNER, "Steve", "what now?"));

        List<Message> history = gateway.requests.get(1).messages();
        assertEquals(1, history.size(), "the deferred clear applied at the turn's end: " + history);
        assertTrue(history.getFirst().text().contains("what now?"));
    }
}
