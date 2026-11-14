package dev.friendai.features.mining;

import dev.friendai.foundation.spatial.Cell;
import dev.friendai.foundation.spatial.CellView;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Pos;
import java.util.Objects;
import java.util.Set;

/**
 * The first edge of one mining target: whether some standable neighbouring cell can break it today, and, when none
 * can, the typed reason.
 *
 * <p>This is the cheap half of the contract {@link MiningGoal} already enforces through a path search: the same
 * {@link MiningSafety#canBreak} rule, probed against the six neighbouring stances instead of the reachable ones. It
 * answers a question that needs no walking and no ticks, so a tool can label a target before the companion commits
 * and a task can refuse a cell the world cannot open before spending its no-progress budget. A target with no open
 * stance may still be reachable through digging, so a caller that only reports must not turn this into a ban; a
 * caller that is about to start work may, because the first cell has no earlier break to open it.
 */
public final class MiningEntry {

    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Why no neighbouring stance can start this target. Every value is a fact about the world, not an estimate. */
    public enum Refusal {
        UNLOADED("chunk not loaded"),
        PROTECTED("protected"),
        UNSAFE("unsafe to break here"),
        NOT_BREAKABLE("cannot be broken with what you carry"),
        NO_STANCE("no stance open beside it"),
        STEP_UNSUPPORTED("the step below it has no support");

        private final String label;

        Refusal(String label) {
            this.label = label;
        }

        /** A short English phrase for a tool result or a task summary. */
        public String label() {
            return label;
        }
    }

    /**
     * @param refusal {@code null} only when a stance was found
     * @param stance the neighbouring stance the target can be broken from, or {@code null} when refused
     */
    public record Verdict(Refusal refusal, Node stance) {
        public Verdict {
            if ((refusal == null) == (stance == null)) {
                throw new IllegalArgumentException("a ready verdict names its stance and a refusal names its cause");
            }
        }

        public static Verdict ready(Node stance) {
            return new Verdict(null, Objects.requireNonNull(stance));
        }

        public static Verdict refused(Refusal refusal) {
            return new Verdict(Objects.requireNonNull(refusal), null);
        }

        public boolean ready() {
            return refusal == null;
        }

        /** The label a tool or a summary shows; empty when the target can be started from this stance. */
        public String label() {
            return refusal == null ? "" : refusal.label();
        }
    }

    private MiningEntry() {}

    /**
     * Probes the six neighbours of {@code target} as stances.
     *
     * @param stepDown the caller planned a carved passage, so a forward-down cell is an intended step rather than an
     *     underfoot resource; see {@link MiningSafety#canBreak(CellView, Node, Pos, Set, boolean)}
     */
    public static Verdict check(CellView view, Pos target, Set<Pos> protectedSupports, boolean stepDown) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(target, "target");
        Set<Pos> supports = protectedSupports == null ? Set.of() : protectedSupports;
        if (view.at(target).kind() == Cell.Kind.UNLOADED) {
            return Verdict.refused(Refusal.UNLOADED);
        }
        if (supports.contains(target)) {
            return Verdict.refused(Refusal.PROTECTED);
        }
        if (!MiningSafety.safeTarget(view, target)) {
            return Verdict.refused(Refusal.UNSAFE);
        }
        if (!view.at(target).breakable()) {
            return Verdict.refused(Refusal.NOT_BREAKABLE);
        }
        boolean standable = false;
        for (int[] offset : NEIGHBORS) {
            Node stance = new Node(target.x() + offset[0], target.y() + offset[1], target.z() + offset[2]);
            if (!MiningSafety.safeNode(view, stance)) {
                continue;
            }
            standable = true;
            if (MiningSafety.canBreak(view, stance, target, supports, stepDown)) {
                return Verdict.ready(stance);
            }
        }
        return Verdict.refused(standable ? Refusal.STEP_UNSUPPORTED : Refusal.NO_STANCE);
    }
}
