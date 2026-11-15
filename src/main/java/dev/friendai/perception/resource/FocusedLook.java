package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.foundation.spatial.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * One deliberate look for a kind of block the companion has not seen yet, the way a player who is asked about something
 * turns and scans the area. Candidate cells are found in loaded chunks, but each one is recorded only when a real sight
 * ray from the companion's eyes reaches it, so a block behind a wall or inside a hill stays unseen. It covers the first
 * seconds after the companion arrives somewhere, before its passive sweeps have looked in every direction: asked "mine
 * that diamond" right after a summon, it used to answer that it saw no diamond ore beside one in plain view. Server
 * thread only.
 */
public final class FocusedLook {
    /** The widest look, in blocks: a deliberate glance, not a survey of the whole loaded world. */
    public static final int MAX_RADIUS = 32;

    /** Independent hard limits keep a glance bounded even on a stalled clock. */
    public static final int MAX_SCANNED_CELLS = 8_192;

    public static final int MAX_RAYS = 64;
    private static final int MAX_SEEN = 24;
    private static final int RAY_CELLS = 128;
    // Give a cold glance enough work to examine the eye's section before applying the soft deadline.
    private static final int MIN_SCANNED_CELLS = 4_096;
    private static final int MIN_RAYS = 4;
    private static final long SOFT_BUDGET_NANOS = 1_000_000L;

    /** An incomplete glance cannot establish absence; counts expose the actual bounded work. */
    public record Result(int seen, boolean complete, int scannedCells, int rays) {}

    /** Aim points inside the block: its centre, then the middle of each face, so a block seen edge-on still counts. */
    private static final double[][] AIM = {
        {0.5, 0.5, 0.5},
        {0.5, 0.9, 0.5},
        {0.1, 0.5, 0.5},
        {0.9, 0.5, 0.5},
        {0.5, 0.5, 0.1},
        {0.5, 0.5, 0.9},
        {0.5, 0.1, 0.5}
    };

    private FocusedLook() {}

    /** Looks only in loaded chunks, and reports when the bounded glance did not finish. */
    public static Result lookFor(
            ServerLevel level,
            CompanionEntity companion,
            ResourceCandidates resources,
            Predicate<BlockState> wanted,
            int radius) {
        if (companion.ownerUuid() == null) return new Result(0, true, 0, 0);
        int reach = Math.max(1, Math.min(MAX_RADIUS, radius));
        Vec3 eye = companion.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int x = (center.getX() - reach) >> 4; x <= (center.getX() + reach) >> 4; x++) {
            for (int z = (center.getZ() - reach) >> 4; z <= (center.getZ() + reach) >> 4; z++) {
                chunks.add(new int[] {x, z});
            }
        }
        chunks.sort(Comparator.comparingInt(
                chunk -> Math.max(Math.abs(chunk[0] - centerChunkX), Math.abs(chunk[1] - centerChunkZ))));
        long deadline = System.nanoTime() + SOFT_BUDGET_NANOS;
        int scanned = 0;
        int rays = 0;
        int seen = 0;
        boolean complete = true;
        String dimension = level.dimension().identifier().toString();
        BlockPos viewpoint = companion.blockPosition();
        long tick = level.getServer().overworld().getGameTime();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        List<BlockPos> missedCenters = new ArrayList<>();
        for (int[] at : chunks) {
            BlockPos sample = new BlockPos(at[0] << 4, center.getY(), at[1] << 4);
            if (!level.hasChunkAt(sample)) {
                complete = false;
                continue;
            }
            LevelChunk chunk = level.getChunkAt(sample);
            LevelChunkSection[] sections = chunk.getSections();
            // Visit the eye's section first, then work outward vertically.
            int centerSection = SectionPos.blockToSectionCoord(center.getY()) - chunk.getSectionYFromSectionIndex(0);
            for (int delta = 0; delta < sections.length * 2; delta++) {
                int index = centerSection + (delta % 2 == 0 ? delta / 2 : -(delta + 1) / 2);
                if (index < 0 || index >= sections.length) continue;
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                if (baseY + 15 < center.getY() - reach || baseY > center.getY() + reach) continue;
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir() || !section.maybeHas(wanted)) continue;
                int minY = Math.max(0, center.getY() - baseY - reach);
                int maxY = Math.min(15, center.getY() - baseY + reach);
                int eyeY = Math.clamp(center.getY() - baseY, minY, maxY);
                for (int offset = 0; offset < 31; offset++) {
                    int y = eyeY + (offset % 2 == 0 ? offset / 2 : -(offset + 1) / 2);
                    if (y < minY || y > maxY) continue;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (scanned >= MAX_SCANNED_CELLS
                                    || (scanned >= MIN_SCANNED_CELLS
                                            && scanned % 64 == 0
                                            && System.nanoTime() >= deadline))
                                return new Result(seen, false, scanned, rays);
                            scanned++;
                            if (!wanted.test(section.getBlockState(x, y, z))) continue;
                            BlockPos pos = new BlockPos((at[0] << 4) + x, baseY + y, (at[1] << 4) + z);
                            if (center.distSqr(pos) > (double) reach * reach) continue;
                            Exposure exposure = exposed(level, pos, neighbour);
                            if (exposure == Exposure.UNKNOWN) complete = false;
                            if (exposure != Exposure.OPEN) continue;
                            if (rays >= MAX_RAYS || (rays >= MIN_RAYS && System.nanoTime() >= deadline))
                                return new Result(seen, false, scanned, rays);
                            rays++;
                            ResourceSight.Outcome outcome = ray(level, eye, pos, AIM[0]);
                            if (outcome == ResourceSight.Outcome.PENDING || outcome == ResourceSight.Outcome.UNLOADED)
                                return new Result(seen, false, scanned, rays);
                            if (outcome == ResourceSight.Outcome.VISIBLE) {
                                record(level, companion, resources, dimension, viewpoint, tick, pos);
                                if (++seen >= MAX_SEEN) return new Result(seen, false, scanned, rays);
                            } else {
                                missedCenters.add(pos);
                            }
                        }
                    }
                }
            }
        }
        // Test each candidate's centre before spending the remaining rays on alternate faces.
        for (int aim = 1; aim < AIM.length; aim++) {
            for (int i = 0; i < missedCenters.size(); i++) {
                BlockPos pos = missedCenters.get(i);
                if (pos == null) continue;
                if (rays >= MAX_RAYS || (rays >= MIN_RAYS && System.nanoTime() >= deadline))
                    return new Result(seen, false, scanned, rays);
                rays++;
                ResourceSight.Outcome outcome = ray(level, eye, pos, AIM[aim]);
                if (outcome == ResourceSight.Outcome.PENDING || outcome == ResourceSight.Outcome.UNLOADED)
                    return new Result(seen, false, scanned, rays);
                if (outcome == ResourceSight.Outcome.VISIBLE) {
                    record(level, companion, resources, dimension, viewpoint, tick, pos);
                    missedCenters.set(i, null);
                    if (++seen >= MAX_SEEN) return new Result(seen, false, scanned, rays);
                }
            }
        }
        return new Result(seen, complete, scanned, rays);
    }

    private static ResourceSight.Outcome ray(ServerLevel level, Vec3 eye, BlockPos pos, double[] aim) {
        Vec3 endpoint = new Vec3(pos.getX() + aim[0], pos.getY() + aim[1], pos.getZ() + aim[2]);
        ResourceSight.Job sight = ResourceSight.rayTo(level, eye, endpoint, pos);
        ResourceSight.Outcome outcome = sight.step(RAY_CELLS);
        return outcome == ResourceSight.Outcome.VISIBLE && !pos.equals(sight.hitBlock())
                ? ResourceSight.Outcome.OCCLUDED
                : outcome;
    }

    private static void record(
            ServerLevel level,
            CompanionEntity companion,
            ResourceCandidates resources,
            String dimension,
            BlockPos viewpoint,
            long tick,
            BlockPos pos) {
        resources.observe(
                companion,
                new ResourceObservation(
                        BuiltInRegistries.BLOCK
                                .getKey(level.getBlockState(pos).getBlock())
                                .toString(),
                        new ResourceObservation.Location(dimension, new Pos(pos.getX(), pos.getY(), pos.getZ())),
                        new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                        tick));
    }

    private enum Exposure {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    /** Unloaded neighbours are unknown, so they cannot make a buried block appear exposed. */
    private static Exposure exposed(ServerLevel level, BlockPos pos, BlockPos.MutableBlockPos neighbour) {
        boolean unknown = false;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            if (!level.hasChunkAt(neighbour)) {
                unknown = true;
            } else if (!level.getBlockState(neighbour).canOcclude()) {
                return Exposure.OPEN;
            }
        }
        return unknown ? Exposure.UNKNOWN : Exposure.CLOSED;
    }
}
