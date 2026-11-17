package dev.friendai.test;

import com.google.gson.Gson;
import com.mojang.serialization.MapCodec;
import dev.friendai.infrastructure.config.ServerConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/** Gives server-configuration tests a private copy for one GameTest batch. */
public final class GameTestConfigEnvironment implements ModInitializer, TestEnvironmentDefinition<ServerConfig> {
    private static final Gson GSON = new Gson();
    private static final MapCodec<GameTestConfigEnvironment> CODEC = MapCodec.unit(GameTestConfigEnvironment::new);

    @Override
    public void onInitialize() {
        Registry.register(
                BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE,
                Identifier.fromNamespaceAndPath("friendai-gametest", "config"),
                CODEC);
    }

    @Override
    public ServerConfig setup(ServerLevel level) {
        var runtime = GameTestSupport.runtime(level.getServer());
        ServerConfig original = runtime.config();
        runtime.applyConfig(GSON.fromJson(GSON.toJsonTree(original), ServerConfig.class));
        return original;
    }

    @Override
    public void teardown(ServerLevel level, ServerConfig original) {
        GameTestSupport.runtime(level.getServer()).applyConfig(original);
    }

    @Override
    public MapCodec<GameTestConfigEnvironment> codec() {
        return CODEC;
    }
}
