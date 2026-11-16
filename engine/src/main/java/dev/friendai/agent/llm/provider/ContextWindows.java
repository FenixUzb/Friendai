package dev.friendai.agent.llm.provider;

import java.util.List;
import java.util.Locale;

/**
 * Conservative context sizes for the models the shipped presets name, so one request can be bounded before it is sent.
 *
 * <p>The table is deliberately smaller than the vendor maximum wherever a model family has several sizes, and the
 * fallback is smaller still: a guess that is too small only shortens a long history, while a guess that is too large
 * fails the request at the provider after a paid round trip. Entries match by model-name prefix, and an unknown model
 * gets {@link #FALLBACK_TOKENS}.
 */
public final class ContextWindows {

    /** Context assumed for a model this table does not name; about 32k tokens. */
    public static final int FALLBACK_TOKENS = 32_768;

    /** Output reserved when the profile does not state a limit. */
    public static final int FALLBACK_OUTPUT_TOKENS = 8_192;

    private record Entry(String prefix, int tokens) {}

    private static final List<Entry> TABLE = List.of(
            new Entry("gpt-5", 128_000),
            new Entry("gpt-4.1", 128_000),
            new Entry("gpt-4o", 128_000),
            new Entry("o4", 128_000),
            new Entry("o3", 128_000),
            new Entry("gpt-4-turbo", 128_000),
            new Entry("gpt-4", 8_192),
            new Entry("gpt-3.5", 16_385),
            new Entry("claude", 200_000),
            new Entry("gemini", 128_000),
            new Entry("command-r", 128_000),
            new Entry("deepseek", 64_000),
            new Entry("llama", 8_192),
            new Entry("mistral", 8_192),
            new Entry("mixtral", 32_768),
            new Entry("qwen", 8_192),
            new Entry("gemma", 8_192),
            new Entry("phi", 8_192),
            new Entry("nemotron", 8_192));

    private ContextWindows() {}

    /** The context size assumed for a provider's model; {@code provider} is accepted for future routing rules. */
    public static int forModel(String provider, String model) {
        String name = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);
        for (Entry entry : TABLE) {
            if (name.startsWith(entry.prefix())) {
                return entry.tokens();
            }
        }
        return FALLBACK_TOKENS;
    }
}
