package dev.friendai.features.acquisition.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.TargetKind;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchResourceCheckpointTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void roundTripsTheRequestAndTheSessionWithoutAnAccessLevel() {
        UUID principal = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        SearchResourceTask.Saved saved =
                new SearchResourceTask.Saved(wood(), "auto", "expedition", "south", principal, session);

        SearchResourceTask.Saved decoded =
                SearchResourceTask.Saved.decode(saved.encode()).orElseThrow();

        assertEquals("wood", decoded.target().coverageKey());
        assertEquals("auto", decoded.strategy());
        assertEquals("expedition", decoded.scope());
        assertEquals("south", decoded.heading());
        assertEquals(principal, decoded.principal());
        assertEquals(session, decoded.session());
        assertFalse(saved.encode().contains("access"));
        assertFalse(saved.encode().contains("trusted"));
    }

    @Test
    void rejectsMalformedOversizedAndUnknownVersionData() {
        assertTrue(SearchResourceTask.Saved.decode(null).isEmpty());
        assertTrue(SearchResourceTask.Saved.decode("").isEmpty());
        assertTrue(SearchResourceTask.Saved.decode("not json").isEmpty());
        assertTrue(SearchResourceTask.Saved.decode("{\"v\":2}").isEmpty());
        assertTrue(SearchResourceTask.Saved.decode("{\"v\":1}").isEmpty());
        String oversized = "{\"v\":1,\"pad\":\"" + "x".repeat(9_000) + "\"}";
        assertTrue(SearchResourceTask.Saved.decode(oversized).isEmpty());
    }

    private static TargetKind wood() {
        return new TargetKind.BlockFamily(
                "wood",
                Set.of("minecraft:oak_log", "minecraft:birch_log"),
                new ResourceSearchProfile(
                        "wood",
                        Set.of(DIMENSION),
                        OptionalInt.empty(),
                        Set.of(ResourceSearchProfile.Strategy.SURFACE)));
    }
}
