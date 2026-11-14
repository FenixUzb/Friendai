package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bounded slot arithmetic behind the obtain task's room check. */
class ObtainCapacityTest {

    /** A stand-in inventory: room per item, stack limit per item and emptied stacks per consumption. */
    private static final class Bag implements ObtainCapacity.Inventory {
        final Map<String, Integer> room = new LinkedHashMap<>();
        final Map<String, Integer> max = new LinkedHashMap<>();
        final Map<String, Integer> emptied = new LinkedHashMap<>();

        @Override
        public int freeRoom(String itemId) {
            return room.getOrDefault(itemId, 0);
        }

        @Override
        public int maxStack(String itemId) {
            return max.getOrDefault(itemId, 64);
        }

        @Override
        public int emptiedSlots(String itemId, int consumed) {
            return Math.min(consumed, emptied.getOrDefault(itemId, 0));
        }
    }

    private static Map<String, Integer> counts(String item, int count) {
        return Map.of(item, count);
    }

    @Test
    void aCompatiblePartialStackNeedsNoFreeSlot() {
        Bag bag = new Bag();
        bag.room.put("minecraft:cobblestone", 1);

        assertEquals(
                0,
                ObtainCapacity.emptySlotsNeeded(counts("minecraft:cobblestone", 1), Map.of(), bag),
                "the 64th cobblestone fills the 63 stack that is already carried");
    }

    @Test
    void theOverflowBeyondCompatibleRoomNeedsSlots() {
        Bag bag = new Bag();
        bag.room.put("minecraft:cobblestone", 1);

        assertEquals(
                1,
                ObtainCapacity.emptySlotsNeeded(counts("minecraft:cobblestone", 65), Map.of(), bag),
                "65 items with one free place in a carried stack take one more slot");
        assertEquals(
                2,
                ObtainCapacity.emptySlotsNeeded(counts("minecraft:cobblestone", 129), Map.of(), bag),
                "129 items with one free place need two more slots");
    }

    @Test
    void aConsumedIngredientFreesTheSlotItsResultTakes() {
        Bag bag = new Bag();
        bag.emptied.put("minecraft:oak_log", 1);

        assertEquals(
                0,
                ObtainCapacity.emptySlotsNeeded(counts("minecraft:oak_planks", 4), counts("minecraft:oak_log", 1), bag),
                "the log's slot becomes the planks' slot");
        assertEquals(
                1,
                ObtainCapacity.emptySlotsNeeded(
                        counts("minecraft:oak_planks", 65), counts("minecraft:oak_log", 1), bag),
                "one freed slot covers one of the two needed");
    }

    @Test
    void aPartialConsumptionFreesNothing() {
        Bag bag = new Bag();

        assertEquals(
                1,
                ObtainCapacity.emptySlotsNeeded(counts("minecraft:oak_planks", 4), counts("minecraft:oak_log", 1), bag),
                "taking one item from a stack that stays full does not free its slot");
    }

    @Test
    void anythingWithNoOutputNeedsNoSlotAndNeverGoesNegative() {
        Bag bag = new Bag();
        bag.emptied.put("minecraft:bucket", 2);

        assertEquals(0, ObtainCapacity.emptySlotsNeeded(Map.of(), Map.of(), bag), "a search step changes nothing");
        assertEquals(
                0,
                ObtainCapacity.emptySlotsNeeded(null, counts("minecraft:bucket", 2), bag),
                "consuming alone never asks for a slot");
        assertEquals(
                0,
                ObtainCapacity.emptySlotsNeeded(counts("minecraft:milk_bucket", 2), counts("minecraft:bucket", 2), bag),
                "two buckets are replaced by two milk buckets");
    }

    @Test
    void anUnknownItemIdIsIgnored() {
        Bag bag = new Bag();

        assertEquals(
                0, ObtainCapacity.emptySlotsNeeded(Map.of("", 3), Map.of(), bag), "a blank id names no item to place");
    }
}
