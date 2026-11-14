package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.Message;
import dev.friendai.execution.tool.Caller;
import java.util.List;

/**
 * Supplies the delivered lines of an earlier conversation with one principal, so a companion recreated after a
 * reload, a death or a re-summon can answer a short follow-up that refers to the exchange before it.
 *
 * <p>The source returns chat messages only, oldest first: a player line and, when the player heard one, the
 * companion's line. The brain restores them into the principal's empty history once, when that history is
 * created; a source that fails or returns nothing costs the turn its continuity, never the turn. Tool calls,
 * tool results and anything the player did not hear are never restored, and nothing here can replay work.
 *
 * <p>Visibility is the caller's: the implementation decides what a principal may see, and the brain asks only
 * for the principal being served, so one principal's text can never enter another's history.
 */
@FunctionalInterface
public interface RecentConversationSource {

    /** At most this many messages are restored: four exchanges, oldest first, starting at a player line. */
    int BUDGET_MESSAGES = 8;

    /** The newest delivered messages the principal may see, oldest first; empty when there are none. */
    List<Message> recent(Caller principal, int maxMessages);

    /** No continuity: a fresh companion starts where the conversation starts. */
    RecentConversationSource NONE = (principal, maxMessages) -> List.of();
}
