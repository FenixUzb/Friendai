package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * An identical failing call runs once more, then reuses its recorded error; a success, changed arguments or another
 * tool never inherit the record.
 */
class RepeatedCallGuardTest {
    private static ToolCall call(String name, String json) {
        return new ToolCall("c", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
    }

    private static ToolResult fail(String why) {
        return ToolResult.error(why);
    }

    @Test
    void theFirstRepeatRunsAndTheThirdReusesTheError() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        ToolCall call = call("mine_blocks", "{\"type\":\"minecraft:stone\"}");

        assertTrue(guard.blocked(call).isEmpty(), "the first execution runs");
        guard.record(call, fail("no such block nearby"));
        assertTrue(guard.blocked(call).isEmpty(), "one transient retry is allowed");
        guard.record(call, fail("no such block nearby"));

        ToolResult reused = guard.blocked(call).orElseThrow();
        assertTrue(reused.error());
        assertTrue(reused.content().contains("not run again"), reused.content());
        assertTrue(reused.content().contains("no such block nearby"), reused.content());
    }

    @Test
    void aChangedArgumentIsADifferentCall() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        ToolCall first = call("mine_blocks", "{\"type\":\"minecraft:stone\"}");
        guard.record(first, fail("no such block nearby"));
        guard.record(first, fail("no such block nearby"));

        assertTrue(guard.blocked(call("mine_blocks", "{\"type\":\"minecraft:iron_ore\"}"))
                .isEmpty());
    }

    @Test
    void keyOrderDoesNotMakeANewCall() {
        assertEquals(
                RepeatedCallGuard.key(call("go_to", "{\"x\":1,\"y\":64,\"z\":2}")),
                RepeatedCallGuard.key(call("go_to", "{\"z\":2,\"x\":1,\"y\":64}")));
        assertNotEquals(
                RepeatedCallGuard.key(call("go_to", "{\"x\":1}")), RepeatedCallGuard.key(call("go_to", "{\"x\":2}")));
    }

    @Test
    void anotherToolNeverInheritsTheRecord() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        ToolCall first = call("mine_blocks", "{\"type\":\"minecraft:stone\"}");
        guard.record(first, fail("no such block nearby"));
        guard.record(first, fail("no such block nearby"));

        assertTrue(guard.blocked(call("excavate", "{\"type\":\"minecraft:stone\"}"))
                .isEmpty());
    }

    @Test
    void aSuccessClearsTheLedgerBecauseTheWorldChanged() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        ToolCall failing = call("mine_blocks", "{\"type\":\"minecraft:stone\"}");
        guard.record(failing, fail("no such block nearby"));
        guard.record(failing, fail("no such block nearby"));
        assertTrue(guard.blocked(failing).isPresent());

        guard.record(call("pickup_items", "{}"), ToolResult.ok("took 4 cobblestone"));

        assertEquals(0, guard.tracked());
        assertTrue(guard.blocked(failing).isEmpty(), "the earlier failure describes an older world");
    }

    @Test
    void theLedgerIsBounded() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        for (int i = 0; i < RepeatedCallGuard.MAX_TRACKED + 10; i++) {
            guard.record(call("go_to", "{\"x\":" + i + "}"), fail("unreachable"));
        }

        assertEquals(RepeatedCallGuard.MAX_TRACKED, guard.tracked());
    }

    @Test
    void theReusedSentenceStaysBounded() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        ToolCall call = call("mine_blocks", "{}");
        guard.record(call, fail("x".repeat(2000)));
        guard.record(call, fail("x".repeat(2000)));

        String content = guard.blocked(call).orElseThrow().content();
        assertTrue(content.length() < 500, "reused content was " + content.length() + " characters");
    }

    @Test
    void malformedArgumentsFallBackToTheirRawText() {
        RepeatedCallGuard guard = new RepeatedCallGuard();
        ToolCall broken = new ToolCall("c", "mine_blocks", null, "{\"type\":", null);
        guard.record(broken, fail("malformed arguments"));
        guard.record(broken, fail("malformed arguments"));

        assertTrue(guard.blocked(new ToolCall("c", "mine_blocks", null, "{\"type\":", null))
                .isPresent());
        assertTrue(guard.blocked(new ToolCall("c", "mine_blocks", null, "{\"type\":\"stone\"}", null))
                .isEmpty());
    }
}
