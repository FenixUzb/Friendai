package dev.friendai.foundation.movement;

import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.Pos;

/**
 * Watches one route step for real forward progress, so a body that slides sideways, is pushed around or oscillates in
 * place is not mistaken for a body that is walking.
 *
 * <p>Net displacement is the wrong question: a companion pressed against a villager, a wall or a flowing current can
 * move more than a tenth of a block every ten ticks while never getting closer to the cell it is walking to. The
 * projection onto the step's own axis answers the question that matters. A window without forward progress first asks
 * for a jump, because a one-block lip is the usual cause, and a second one reports {@link Verdict#STALLED} so the
 * caller can fail the step and let the navigator re-plan instead of waiting out the step timeout.
 *
 * <p>Vertical steps measure the change in height: their horizontal axis is empty.
 */
public final class StepProgress {
    public enum Verdict {
        MOVING,
        NUDGE,
        STALLED
    }

    private final double originX;
    private final double originY;
    private final double originZ;
    private final double axisX;
    private final double axisY;
    private final double axisZ;
    private final int windowTicks;
    private final double minimum;

    private int ticks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean nudged;

    /**
     * @param windowTicks how many ticks make one progress window
     * @param minimum forward blocks a window must cover to count as progress
     */
    public StepProgress(Node from, Node to, int windowTicks, double minimum) {
        this(new Pos(from.x(), from.y(), from.z()), new Pos(to.x(), to.y(), to.z()), windowTicks, minimum);
    }

    public StepProgress(Pos from, Pos to, int windowTicks, double minimum) {
        if (windowTicks < 1) throw new IllegalArgumentException("windowTicks must be positive: " + windowTicks);
        if (minimum <= 0.0) throw new IllegalArgumentException("minimum must be positive: " + minimum);
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double horizontal = Math.hypot(dx, dz);
        boolean vertical = horizontal < 1.0e-6;
        this.axisX = vertical ? 0.0 : dx / horizontal;
        this.axisY = vertical ? (to.y() < from.y() ? -1.0 : 1.0) : 0.0;
        this.axisZ = vertical ? 0.0 : dz / horizontal;
        this.originX = from.x() + 0.5;
        this.originY = from.y();
        this.originZ = from.z() + 0.5;
        this.windowTicks = windowTicks;
        this.minimum = minimum;
        this.lastX = originX;
        this.lastY = originY;
        this.lastZ = originZ;
    }

    /** Restarts the window at the body's current position, with no nudge remembered. */
    public void reset(double x, double y, double z) {
        ticks = 0;
        lastX = x;
        lastY = y;
        lastZ = z;
        nudged = false;
    }

    /** Samples the body once this tick and returns what the caller should do about it. */
    public Verdict sample(double x, double y, double z) {
        if (++ticks < windowTicks) return Verdict.MOVING;
        double moved = forwardAt(x, y, z) - forwardAt(lastX, lastY, lastZ);
        ticks = 0;
        lastX = x;
        lastY = y;
        lastZ = z;
        if (moved >= minimum) {
            nudged = false;
            return Verdict.MOVING;
        }
        if (nudged) return Verdict.STALLED;
        nudged = true;
        return Verdict.NUDGE;
    }

    /** Distance along the step from its start cell's centre line; negative behind the start. */
    public double forwardAt(double x, double y, double z) {
        return (x - originX) * axisX + (y - originY) * axisY + (z - originZ) * axisZ;
    }
}
