package dev.friendai.agent.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers which exact calls failed in this turn, so a model that repeats one cannot run it again and again.
 *
 * <p>A call is identified by its tool name plus its canonical arguments: object keys are sorted, arrays keep their
 * order and numbers keep their literal form, so two spellings of the same request are one key while a changed
 * argument is a different call. Arguments that did not parse fall back to their raw text.
 *
 * <p>The first identical repeat still executes — a transient fault is worth one retry — and every later copy reuses
 * the recorded error with a bounded explanation. Any successful call clears the whole ledger, because the world has
 * changed and the previous failure may no longer describe it; a different tool or different arguments never consult
 * another call's record.
 */
public final class RepeatedCallGuard {

    /** How many times an identical failing call may execute before its recorded error is reused. */
    public static final int TRANSIENT_RETRIES = 1;

    /** The ledger is bounded so a turn with many distinct failures cannot grow without limit. */
    static final int MAX_TRACKED = 64;

    private static final int MAX_KEY_CHARS = 512;
    private static final int MAX_PRIOR_CHARS = 300;

    private final Map<String, Failure> failures = new LinkedHashMap<>();

    /**
     * The recorded result an identical call must reuse instead of executing, or empty when the call may run.
     *
     * <p>The result is still an error and keeps its place in the provider's ordered tool-result group; the content
     * says why the tool was not invoked and repeats the earlier answer.
     */
    public Optional<ToolResult> blocked(ToolCall call) {
        Failure failure = failures.get(key(call));
        if (failure == null || failure.retries() < TRANSIENT_RETRIES) {
            return Optional.empty();
        }
        return Optional.of(ToolResult.error("not run again: an identical " + call.name() + " call already failed "
                + (TRANSIENT_RETRIES + 1) + " times in this turn, so change its arguments or wait for the world to"
                + " change. Earlier result: " + bounded(failure.result().content())));
    }

    /** Records one executed outcome: an error counts toward the allowance, a success clears every record. */
    public void record(ToolCall call, ToolResult result) {
        if (result == null) {
            return;
        }
        if (!result.error()) {
            failures.clear();
            return;
        }
        String key = key(call);
        Failure previous = failures.get(key);
        if (previous == null) {
            if (failures.size() < MAX_TRACKED) {
                failures.put(key, new Failure(result, 0));
            }
            return;
        }
        failures.put(key, new Failure(result, previous.retries() + 1));
    }

    /** Starts a turn with no memory of an earlier one. */
    public void clear() {
        failures.clear();
    }

    /** How many distinct failing calls the ledger currently holds; for tests and diagnostics. */
    public int tracked() {
        return failures.size();
    }

    /** The canonical identity of a call: its name plus sorted-key JSON, bounded to a stable length. */
    public static String key(ToolCall call) {
        StringBuilder out = new StringBuilder(64);
        out.append(call.name()).append('\u0000');
        JsonElement arguments = call.arguments();
        if (arguments != null) {
            canonical(arguments, out);
        } else {
            out.append(call.rawArguments() == null ? "" : call.rawArguments());
        }
        return out.length() <= MAX_KEY_CHARS ? out.toString() : out.substring(0, MAX_KEY_CHARS);
    }

    private static void canonical(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            out.append("null");
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            var names = new java.util.ArrayList<>(object.keySet());
            java.util.Collections.sort(names);
            out.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(name).append(':');
                canonical(object.get(name), out);
            }
            out.append('}');
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                canonical(array.get(i), out);
            }
            out.append(']');
            return;
        }
        out.append(element.getAsString());
    }

    private static String bounded(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= MAX_PRIOR_CHARS ? value : value.substring(0, MAX_PRIOR_CHARS) + "…";
    }

    private record Failure(ToolResult result, int retries) {}
}
