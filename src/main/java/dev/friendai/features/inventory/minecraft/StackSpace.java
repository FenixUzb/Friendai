package dev.friendai.features.inventory.minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a real inventory can take of one item, measured the way the game will fill it.
 *
 * <p>Compatibility is decided by {@link ItemStack#isSameItemSameComponents}, so a renamed or otherwise differently
 * componented stack is never counted as room for a plain one. A stack that will be completely consumed frees its
 * slot; consuming the smallest stacks first is the conservative order, because it leaves full stacks intact.
 */
public final class StackSpace {

    private StackSpace() {}

    /** Room left in carried stacks a plain stack of this item could merge into; 0 when none are compatible. */
    public static int freeRoom(Container inventory, String itemId) {
        ItemStack prototype = prototype(itemId);
        if (inventory == null || prototype.isEmpty()) {
            return 0;
        }
        int room = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(prototype, stack)) {
                continue;
            }
            room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return room;
    }

    /** The stack limit of the item; 64 when the id names no item or a tag. */
    public static int maxStack(String itemId) {
        ItemStack prototype = prototype(itemId);
        return prototype.isEmpty() ? 64 : Math.max(1, prototype.getMaxStackSize());
    }

    /** Slots that become empty when {@code consumed} of the item are taken out of the inventory. */
    public static int emptiedSlots(Container inventory, String itemId, int consumed) {
        ItemStack prototype = prototype(itemId);
        if (inventory == null || prototype.isEmpty() || consumed <= 0) {
            return 0;
        }
        List<ItemStack> matching = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(prototype, stack)) {
                matching.add(stack);
            }
        }
        matching.sort(Comparator.comparingInt(ItemStack::getCount));
        int left = consumed;
        int emptied = 0;
        for (ItemStack stack : matching) {
            if (left <= 0) {
                break;
            }
            int taken = Math.min(left, stack.getCount());
            left -= taken;
            if (taken == stack.getCount()) {
                emptied++;
            }
        }
        return emptied;
    }

    /** A plain stack of the item, or empty for a tag, an unknown id or an item that cannot be stacked. */
    public static ItemStack prototype(String itemId) {
        if (itemId == null || itemId.isBlank() || itemId.startsWith("#")) {
            return ItemStack.EMPTY;
        }
        Identifier id = Identifier.tryParse(itemId.strip());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return new ItemStack(item);
    }
}
