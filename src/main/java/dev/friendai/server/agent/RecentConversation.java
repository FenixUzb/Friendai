package dev.friendai.server.agent;

import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns transcript rows into the chat history a recreated conversation starts from.
 *
 * <p>The transcript is newest-first and the history is oldest-first, so the selection is reversed. Only text
 * becomes a message: a {@link Speaker#PLAYER} row is a player line and a {@link Speaker#COMPANION} row is a line
 * that was actually spoken, which is why no tool call, tool result or unspoken draft can ever be restored. The
 * restored history must start at a player line, because providers reject a history that opens with an assistant
 * message; leading companion rows are dropped rather than guessed at.
 */
public final class RecentConversation {

    private RecentConversation() {}

    /** At most {@code maxMessages} of the newest rows, oldest first, starting at a player line. */
    public static List<Message> messages(List<TranscriptEntry> newestFirst, int maxMessages) {
        if (newestFirst == null || newestFirst.isEmpty() || maxMessages < 1) {
            return List.of();
        }
        List<TranscriptEntry> rows =
                newestFirst.size() > maxMessages ? newestFirst.subList(0, maxMessages) : newestFirst;
        List<Message> messages = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            TranscriptEntry entry = rows.get(i);
            if (entry == null || entry.text() == null || entry.text().isBlank()) {
                continue;
            }
            messages.add(
                    entry.speaker() == Speaker.PLAYER
                            ? Message.user(entry.text())
                            : Message.assistant(entry.text(), List.of()));
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).role() != Role.USER) {
            start++;
        }
        return start == messages.size() ? List.of() : List.copyOf(messages.subList(start, messages.size()));
    }
}
