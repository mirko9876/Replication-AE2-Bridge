package net.unfamily.repae2bridge.item;

import net.unfamily.repae2bridge.RepAE2Bridge;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;


/**
 * Base class for all virtual matter items.
 * These items will disappear if left in the player's inventory.
 */
class MatterItem extends Item {
    public MatterItem(Properties properties) {
        super(properties);
    }
    
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        // Makes the item disappear if it's in a player's inventory
        if (entity instanceof Player && !level.isClientSide()) {
            // Remove the item from inventory
            stack.setCount(0);
        }
    }
}


public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, RepAE2Bridge.MOD_ID);

    // Earth Matter
    public static final DeferredHolder<Item, Item> EARTH_MATTER = ITEMS.register("earth",
            () -> new MatterItem(new Item.Properties()));

    // Nether Matter
    public static final DeferredHolder<Item, Item> NETHER_MATTER = ITEMS.register("nether",
            () -> new MatterItem(new Item.Properties()));

    // Organic Matter
    public static final DeferredHolder<Item, Item> ORGANIC_MATTER = ITEMS.register("organic",
            () -> new MatterItem(new Item.Properties()));

    // Ender Matter
    public static final DeferredHolder<Item, Item> ENDER_MATTER = ITEMS.register("ender",
            () -> new MatterItem(new Item.Properties()));

    // Metallic Matter
    public static final DeferredHolder<Item, Item> METALLIC_MATTER = ITEMS.register("metallic",
            () -> new MatterItem(new Item.Properties()));

    // Precious Matter
    public static final DeferredHolder<Item, Item> PRECIOUS_MATTER = ITEMS.register("precious",
            () -> new MatterItem(new Item.Properties()));

    // Living Matter
    public static final DeferredHolder<Item, Item> LIVING_MATTER = ITEMS.register("living",
            () -> new MatterItem(new Item.Properties()));

    // Quantum Matter
    public static final DeferredHolder<Item, Item> QUANTUM_MATTER = ITEMS.register("quantum",
            () -> new QuantumMatterItem(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
