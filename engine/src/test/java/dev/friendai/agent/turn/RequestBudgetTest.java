package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.provider.ContextWindows;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestBudgetTest {

    private static final Set<String> ESSENTIAL = Set.of("stop", "request_tools");

    private static RequestBudget.Fit fit(
            String system, List<Message> history, List<ToolSpec> tools, int context, int output) {
        return RequestBudget.fit(system, history, tools, context, output, ESSENTIAL::contains);
    }

    private static ToolSpec spec(String name, int descriptionChars) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        return new ToolSpec(name, "d".repeat(descriptionChars), schema);
    }

    @Test
    void theEstimateIsConservativeForEveryScript() {
        assertEquals(0, RequestBudget.estimateTokens(""));
        assertEquals(1, RequestBudget.estimateTokens("abcd"));
        assertEquals(2, RequestBudget.estimateTokens("abcdefgh"));
        assertEquals(3, RequestBudget.estimateTokens("привет"), "six Cyrillic characters are three tokens");
        assertEquals(4, RequestBudget.estimateTokens("你好世界"), "wide code points are one token each");
    }

    @Test
    void aRequestThatFitsIsSentUnchanged() {
        List<Message> history = List.of(Message.user("hi"), Message.assistant("hello", List.of()));
        List<ToolSpec> tools = List.of(spec("stop", 10), spec("mine_blocks", 10));

        RequestBudget.Fitted fitted = assertInstanceOf(
                RequestBudget.Fitted.class, fit("system", history, tools, ContextWindows.FALLBACK_TOKENS, 2_000));

        assertEquals(history, fitted.history());
        assertEquals(tools, fitted.tools());
        assertTrue(fitted.tokens() <= fitted.budget());
    }

    @Test
    void aMissingProviderLimitUsesTheConservativeBudget() {
        RequestBudget.Fitted fitted =
                assertInstanceOf(RequestBudget.Fitted.class, fit("system", List.of(), List.of(), 0, 0));

        assertEquals(ContextWindows.FALLBACK_TOKENS - ContextWindows.FALLBACK_OUTPUT_TOKENS, fitted.budget());
    }

    @Test
    void optionalSchemasAreDroppedFirstAndCoreOnesStay() {
        List<ToolSpec> tools = List.of(
                spec("stop", 20), spec("request_tools", 20), spec("mine_blocks", 4_000), spec("build_house", 4_000));
        // Room for the system prompt and two small schemas only.
        RequestBudget.Fitted fitted =
                assertInstanceOf(RequestBudget.Fitted.class, fit("system", List.of(), tools, 900, 100));

        assertEquals(
                List.of("stop", "request_tools"),
                fitted.tools().stream().map(ToolSpec::name).toList());
    }

    @Test
    void wholeExchangesAreDroppedFromTheOldestAndTheLastOneStays() {
        List<Message> history = new ArrayList<>();
        for (int turn = 1; turn <= 4; turn++) {
            String callId = "c" + turn;
            history.add(Message.user("request " + turn + " " + "u".repeat(4_000)));
            history.add(
                    Message.assistant("", List.of(new ToolCall(callId, "mine_blocks", new JsonObject(), "{}", null))));
            history.add(Message.toolResult(callId, "mine_blocks", "r".repeat(4_000)));
        }
        // Room for roughly the last exchange and the closing user line.
        RequestBudget.Fitted fitted =
                assertInstanceOf(RequestBudget.Fitted.class, fit("", history, List.of(), 6_000, 1_000));

        assertTrue(fitted.history().size() < history.size(), "something was dropped");
        assertEquals(Role.USER, fitted.history().getFirst().role(), "history still starts a whole exchange");
        assertEquals(history.getLast(), fitted.history().getLast(), "the current exchange is untouched");
        for (Message message : fitted.history()) {
            if (message.role() == Role.TOOL) {
                assertTrue(
                        fitted.history().stream()
                                .anyMatch(candidate -> candidate.toolCalls().stream()
                                        .anyMatch(call -> call.id().equals(message.toolCallId()))),
                        "a tool result never loses its call: " + message.toolCallId());
            }
        }
    }

    @Test
    void aSingleOversizedMessageIsRefusedInsteadOfSent() {
        List<Message> history = List.of(Message.user("x".repeat(40_000)));

        RequestBudget.Refusal refusal =
                assertInstanceOf(RequestBudget.Refusal.class, fit("system", history, List.of(), 2_000, 500));

        assertTrue(refusal.tokens() > refusal.budget());
    }
}
