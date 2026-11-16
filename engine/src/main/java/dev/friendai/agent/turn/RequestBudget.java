package dev.friendai.agent.turn;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.provider.ContextWindows;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Fits one provider request into a token budget before it is sent.
 *
 * <p>Characters are estimated, not tokenized: ASCII text counts four characters per token, other scripts two and
 * scripts with wide code points (CJK, Hangul, emoji) one, which overstates the count rather than understating it. The
 * budget is the model's context minus the reply reservation, so a long history or a large tool catalog can never
 * overflow the provider and fail after a paid round trip.
 *
 * <p>Two reductions are applied in order, each keeping the exchange a request still needs: optional tool schemas are
 * dropped first (the model can still ask for a group with {@code request_tools}), then whole completed exchanges are
 * removed oldest-first, never the current one and never splitting a tool call from its results. A request that still
 * does not fit is refused with one typed result instead of being sent.
 */
public final class RequestBudget {

    /** Characters per token in ASCII text, the usual English/code ratio. */
    public static final int ASCII_CHARS_PER_TOKEN = 4;

    /** Characters per token in Latin-1, Cyrillic, Greek, Arabic and other non-wide scripts. */
    public static final int OTHER_CHARS_PER_TOKEN = 2;

    /** A budget below this is never produced, so the reserve cannot starve a request of every token. */
    private static final int MIN_INPUT_TOKENS = 1_024;

    private RequestBudget() {}

    /** The outcome of fitting: an accepted request or the numbers behind a refusal. */
    public sealed interface Fit {
        /** Tokens the request carries, after any reduction. */
        int tokens();

        /** Tokens the input could use. */
        int budget();
    }

    /** A request that fits, with the reduced lists that are actually sent. */
    public record Fitted(String system, List<Message> history, List<ToolSpec> tools, int tokens, int budget)
            implements Fit {
        public Fitted {
            history = List.copyOf(history);
            tools = List.copyOf(tools);
        }
    }

    /** A request that cannot fit even after every reduction; nothing is sent. */
    public record Refusal(int tokens, int budget) implements Fit {}

    /**
     * Fits a request into the provider's context.
     *
     * @param contextTokens the model's context size, or 0 when the gateway cannot say (the conservative fallback)
     * @param outputTokens the reply reservation, or 0 when the profile does not state one
     * @param essentialTool whether a tool schema must survive narrowing (core tools and {@code request_tools})
     */
    public static Fit fit(
            String system,
            List<Message> history,
            List<ToolSpec> tools,
            int contextTokens,
            int outputTokens,
            Predicate<String> essentialTool) {
        int context = contextTokens > 0 ? contextTokens : ContextWindows.FALLBACK_TOKENS;
        int reserve = outputTokens > 0 ? outputTokens : ContextWindows.FALLBACK_OUTPUT_TOKENS;
        reserve = Math.min(reserve, Math.max(MIN_INPUT_TOKENS, context / 2));
        int budget = Math.max(MIN_INPUT_TOKENS, context - reserve);

        String prompt = system == null ? "" : system;
        List<Message> keptHistory = history == null ? List.of() : history;
        List<ToolSpec> keptTools = tools == null ? List.of() : new ArrayList<>(tools);

        int fixed = estimateTokens(prompt);
        int total = fixed + estimateHistory(keptHistory) + estimateTools(keptTools);

        if (total > budget) {
            for (int i = keptTools.size() - 1; i >= 0 && total > budget; i--) {
                if (essentialTool != null && essentialTool.test(keptTools.get(i).name())) {
                    continue;
                }
                total -= estimateTokens(keptTools.get(i));
                keptTools.remove(i);
            }
        }
        if (total > budget && keptHistory.size() > 1) {
            int historyBudget = Math.max(0, budget - fixed - estimateTools(keptTools));
            List<Message> compacted = compactWholeExchanges(keptHistory, historyBudget);
            if (compacted.size() != keptHistory.size()) {
                total = fixed + estimateHistory(compacted) + estimateTools(keptTools);
                keptHistory = compacted;
            }
        }
        if (total > budget) {
            return new Refusal(total, budget);
        }
        return new Fitted(prompt, keptHistory, keptTools, total, budget);
    }

    /** Drops the oldest whole exchanges, keeping the last one whatever its size. */
    static List<Message> compactWholeExchanges(List<Message> history, int historyBudget) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).role() == dev.friendai.agent.llm.model.Role.USER) {
                starts.add(i);
            }
        }
        if (starts.size() <= 1) {
            return history;
        }
        int total = estimateHistory(history);
        int firstKept = 0;
        while (firstKept < starts.size() - 1 && total > historyBudget) {
            int from = starts.get(firstKept);
            int to = starts.get(firstKept + 1);
            total -= estimateHistory(history.subList(from, to));
            firstKept++;
        }
        return firstKept == 0 ? history : new ArrayList<>(history.subList(starts.get(firstKept), history.size()));
    }

    /** Conservative token estimate of one message, its text and its tool calls included. */
    public static int estimateTokens(Message message) {
        if (message == null) {
            return 0;
        }
        int tokens = estimateTokens(message.text());
        for (ToolCall call : message.toolCalls()) {
            tokens += estimateTokens(call.name());
            tokens += estimateTokens(call.rawArguments() == null ? "" : call.rawArguments());
        }
        return tokens;
    }

    /** Conservative token estimate of a tool schema: its name, description and parameter JSON. */
    public static int estimateTokens(ToolSpec spec) {
        if (spec == null) {
            return 0;
        }
        JsonObject schema = spec.parametersSchema();
        return estimateTokens(spec.name())
                + estimateTokens(spec.description())
                + estimateTokens(schema == null ? "" : schema.toString());
    }

    /** Conservative token estimate of a text: ASCII four characters per token, wide scripts one per code point. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long other = 0;
        long wide = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 0x80) {
                ascii++;
            } else if (codePoint < 0x2E80) {
                other++;
            } else {
                wide++;
            }
        }
        return (int) Math.ceil(ascii / (double) ASCII_CHARS_PER_TOKEN + other / (double) OTHER_CHARS_PER_TOKEN + wide);
    }

    private static int estimateHistory(List<Message> history) {
        int tokens = 0;
        for (Message message : history) {
            tokens += estimateTokens(message);
        }
        return tokens;
    }

    private static int estimateTools(List<ToolSpec> tools) {
        int tokens = 0;
        for (ToolSpec spec : tools) {
            tokens += estimateTokens(spec);
        }
        return tokens;
    }
}
