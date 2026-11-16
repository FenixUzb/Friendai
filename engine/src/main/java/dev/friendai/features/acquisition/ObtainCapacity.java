package dev.friendai.features.acquisition;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * How many empty inventory slots one obtain step needs, from the items it produces and consumes.
 *
 * <p>Counts, not stacks, are the unit. A produced item first fills the room left in compatible carried stacks, so a
 * companion holding 63 cobblestone needs no free slot to obtain the 64th. Items the step consumes can free slots
 * first — a craft turns the slot that held its last ingredient into the slot that holds its result — so the answer is
 * the production overflow minus the slots the step empties, never negative. The caller measures compatibility, stack
 * limits, and how many stacks a consumption really empties on the live inventory; this class only does the bounded
 * arithmetic, so the same rule is testable without a world.
 */
public final class ObtainCapacity {

    private ObtainCapacity() {}

    /** The live inventory, measured by the caller. Every count it returns is non-negative. */
    public interface Inventory {
        /** Room left in carried stacks a produced item could merge into; 0 when none are compatible. */
        int freeRoom(String itemId);

        /** The item's stack limit, at least 1. */
        int maxStack(String itemId);

        /** Slots that become empty when {@code consumed} of the item are taken out. */
        int emptiedSlots(String itemId, int consumed);
    }

    /**
     * The empty slots the step needs before it starts.
     *
     * @param produced item id to the count the step adds to the inventory
     * @param consumed item id to the count the step takes out of it before its results arrive
     */
    public static int emptySlotsNeeded(
            Map<String, Integer> produced, Map<String, Integer> consumed, Inventory inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("inventory is required");
        }
        Set<String> ids = new LinkedHashSet<>();
        if (produced != null) {
            ids.addAll(produced.keySet());
        }
        if (consumed != null) {
            ids.addAll(consumed.keySet());
        }
        int slots = 0;
        int freed = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            int taken = positive(consumed, id);
            if (taken > 0) {
                freed += Math.max(0, inventory.emptiedSlots(id, taken));
            }
            int added = positive(produced, id);
            if (added <= 0) {
                continue;
            }
            int max = Math.max(1, inventory.maxStack(id));
            int overflow = Math.max(0, added - Math.max(0, inventory.freeRoom(id)));
            slots += (overflow + max - 1) / max;
        }
        return Math.max(0, slots - freed);
    }

    private static int positive(Map<String, Integer> counts, String id) {
        Integer value = counts == null ? null : counts.get(id);
        return value == null ? 0 : Math.max(0, value);
    }
}
