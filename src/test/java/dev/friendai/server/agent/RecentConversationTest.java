package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The transcript adapter that turns stored rows into the chat history a recreated conversation starts from. */
class RecentConversationTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static TranscriptEntry entry(long id, Speaker speaker, String text) {
        return new TranscriptEntry(id, 1_000L + id, OWNER, "Steve", speaker, text);
    }

    /** Rows as the book returns them: newest first. */
    private static List<TranscriptEntry> newestFirst(TranscriptEntry... entries) {
        List<TranscriptEntry> rows = new ArrayList<>(List.of(entries));
        rows.sort((a, b) -> Long.compare(b.id(), a.id()));
        return rows;
    }

    @Test
    void rowsBecomeOldestFirstChatMessages() {
        List<Message> messages = RecentConversation.messages(
                newestFirst(
                        entry(1, Speaker.PLAYER, "mine 3 stone"),
                        entry(2, Speaker.COMPANION, "On it."),
                        entry(3, Speaker.PLAYER, "done?")),
                8);

        assertEquals(3, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        assertEquals("mine 3 stone", messages.get(0).text());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals("On it.", messages.get(1).text());
        assertEquals("done?", messages.get(2).text());
        assertTrue(messages.stream().allMatch(message -> message.toolCalls().isEmpty()));
    }

    @Test
    void onlyTheNewestRowsWithinTheBudgetAreKept() {
        List<Message> messages = RecentConversation.messages(
                newestFirst(
                        entry(1, Speaker.PLAYER, "one"),
                        entry(2, Speaker.COMPANION, "two"),
                        entry(3, Speaker.PLAYER, "three"),
                        entry(4, Speaker.COMPANION, "four")),
                2);

        assertEquals(2, messages.size());
        assertEquals("three", messages.get(0).text());
        assertEquals("four", messages.get(1).text());
    }

    @Test
    void aHistoryNeverOpensWithACompanionLine() {
        List<Message> messages = RecentConversation.messages(
                newestFirst(entry(1, Speaker.COMPANION, "old line"), entry(2, Speaker.PLAYER, "newer line")), 8);

        assertEquals(1, messages.size());
        assertEquals(Role.USER, messages.getFirst().role());
        assertEquals("newer line", messages.getFirst().text());
    }

    @Test
    void blankRowsAndAnEmptySelectionProduceNoHistory() {
        assertEquals(List.of(), RecentConversation.messages(newestFirst(entry(1, Speaker.COMPANION, "unprompted")), 8));
        assertEquals(List.of(), RecentConversation.messages(newestFirst(entry(1, Speaker.PLAYER, "   ")), 8));
        assertEquals(List.of(), RecentConversation.messages(List.of(), 8));
        assertEquals(List.of(), RecentConversation.messages(null, 8));
        assertEquals(List.of(), RecentConversation.messages(newestFirst(entry(1, Speaker.PLAYER, "hi")), 0));
    }
}
