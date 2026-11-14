package dev.friendai.agent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContextWindowsTest {

    @Test
    void knownModelFamiliesGetAConservativeSize() {
        assertEquals(200_000, ContextWindows.forModel("anthropic", "claude-sonnet-4-5"));
        assertEquals(128_000, ContextWindows.forModel("openai", "gpt-4o-mini"));
        assertEquals(128_000, ContextWindows.forModel("openai", "gpt-4.1-mini"));
        assertEquals(8_192, ContextWindows.forModel("openai", "gpt-4"));
        assertEquals(16_385, ContextWindows.forModel("openai", "gpt-3.5-turbo"));
        assertEquals(128_000, ContextWindows.forModel("google", "Gemini-2.5-pro"));
        assertEquals(8_192, ContextWindows.forModel("ollama", "llama3.1"));
    }

    @Test
    void anUnknownOrMissingModelFallsBackRatherThanGuessingLarge() {
        assertEquals(ContextWindows.FALLBACK_TOKENS, ContextWindows.forModel("custom", "my-model-v9"));
        assertEquals(ContextWindows.FALLBACK_TOKENS, ContextWindows.forModel(null, null));
        assertEquals(ContextWindows.FALLBACK_TOKENS, ContextWindows.forModel("", "  "));
    }
}
