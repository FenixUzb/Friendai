package dev.friendai.features.mining;

import dev.friendai.foundation.spatial.Node;
import java.util.List;
import java.util.Objects;

/** Pure check that a live, verified route still leads from an exploration stance back to its safe anchor. */
public final class ReturnAssessment {
    public enum Status {
        SAFE,
        NEEDS_SUPPORTS,
        UNKNOWN_OR_BLOCKED
    }

    /**
     * Why a route was not accepted. The first refused inverse leg is kept with the leg itself, so a caller can name the
     * exact edge and the kind of cause instead of merging every failure into one "blocked or unknown" record.
     */
    public enum Refusal {
        /** The route is absent, too long or no longer starts where the companion stands. */
        STALE,
        /** The route was built in another dimension than the one the companion is in. */
        DIMENSION_CHANGED,
        /** The safe anchor is in an unloaded chunk. */
        ANCHOR_UNLOADED,
        /** An inverse leg touches a cell whose chunk is not loaded. */
        LEG_UNLOADED,
        /** An inverse leg is loaded but has no walk-only move back; terrain or a placed block obstructs it. */
        LEG_OBSTRUCTED,
        /** An inverse leg does not start where the previous one ended, or is malformed. */
        LEG_BROKEN_CHAIN,
        /** The last inverse leg does not end at the safe anchor. */
        ANCHOR_UNREACHED,
        /** The route is verified but needs more support blocks than are free. */
        SUPPORTS
    }

    public record Leg(Node from, Node to, int supports, boolean loaded, boolean verified) {
        public Leg {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }

    /** Return legs are ordered from the current stance back to the anchor. */
    public record RouteEvidence(
            String dimension,
            String loadedDimension,
            Node anchor,
            Node stance,
            List<Leg> returnLegs,
            boolean anchorLoaded,
            boolean fresh) {
        public RouteEvidence {
            returnLegs = List.copyOf(returnLegs);
        }
    }

    /**
     * @param anchor the safe anchor the route returns to, when known
     * @param requiredSupports support blocks the return needs in total
     * @param refusal the first reason the route was not accepted, {@code null} only when it is safe
     * @param failedLeg the first refused inverse leg, {@code null} when the refusal is not about one leg
     */
    public record Result(Status status, Node anchor, int requiredSupports, Refusal refusal, Leg failedLeg) {
        public Result {
            Objects.requireNonNull(status);
            if (requiredSupports < 0) throw new IllegalArgumentException("requiredSupports cannot be negative");
            if ((status == Status.SAFE) == (refusal != null)) {
                throw new IllegalArgumentException("a safe result has no refusal and an unsafe one has one");
            }
        }

        public static Result safe(Node anchor, int requiredSupports) {
            return new Result(Status.SAFE, anchor, requiredSupports, null, null);
        }

        public static Result refused(Status status, Node anchor, int requiredSupports, Refusal refusal, Leg failedLeg) {
            return new Result(status, anchor, requiredSupports, refusal, failedLeg);
        }
    }

    private ReturnAssessment() {}

    public static Result assess(RouteEvidence evidence, int spareSupports) {
        if (evidence == null || evidence.anchor() == null || evidence.stance() == null) {
            return Result.refused(Status.UNKNOWN_OR_BLOCKED, null, 0, Refusal.STALE, null);
        }
        if (evidence.dimension() == null || !evidence.dimension().equals(evidence.loadedDimension())) {
            return Result.refused(Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), 0, Refusal.DIMENSION_CHANGED, null);
        }
        if (!evidence.fresh()) {
            return Result.refused(Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), 0, Refusal.STALE, null);
        }
        if (!evidence.anchorLoaded()) {
            return Result.refused(Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), 0, Refusal.ANCHOR_UNLOADED, null);
        }
        if (spareSupports < 0) {
            return Result.refused(Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), 0, Refusal.STALE, null);
        }

        Node expected = evidence.stance();
        Leg previous = null;
        int required = 0;
        for (Leg leg : evidence.returnLegs()) {
            if (leg == null || leg.supports() < 0 || !leg.from().equals(expected)) {
                return Result.refused(
                        Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), required, Refusal.LEG_BROKEN_CHAIN, leg);
            }
            if (!leg.loaded()) {
                return Result.refused(
                        Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), required, Refusal.LEG_UNLOADED, leg);
            }
            if (!leg.verified()) {
                return Result.refused(
                        Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), required, Refusal.LEG_OBSTRUCTED, leg);
            }
            required = (int) Math.min(Integer.MAX_VALUE, (long) required + leg.supports());
            expected = leg.to();
            previous = leg;
        }
        if (!expected.equals(evidence.anchor())) {
            return Result.refused(
                    Status.UNKNOWN_OR_BLOCKED, evidence.anchor(), required, Refusal.ANCHOR_UNREACHED, previous);
        }
        if (required > spareSupports) {
            return Result.refused(Status.NEEDS_SUPPORTS, evidence.anchor(), required, Refusal.SUPPORTS, null);
        }
        return Result.safe(evidence.anchor(), required);
    }
}
