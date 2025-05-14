package net.unfamily.repae2bridge.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.SoundType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.unfamily.repae2bridge.block.custom.*;
import net.unfamily.repae2bridge.item.ModItems;
import net.unfamily.repae2bridge.RepAE2Bridge;
import com.buuz135.replication.block.MatterPipeBlock;

import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, RepAE2Bridge.MOD_ID);

    public static final RegistryObject<Block> REPAE2BRIDGE = registerBlock("rep_ae2_bridge",
            () -> new RepAE2BridgeBl(BlockBehaviour.Properties.of()
                .strength(0.3F, 0.3F)  // Very easy to break
                .sound(SoundType.COPPER)  // Copper sound
                .noOcclusion()));      // Maintains noOcclusion property

    private static <T extends Block> RegistryObject<T> registerBlock(String name, Supplier<T> block) {
        RegistryObject<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> void registerBlockItem(String name, RegistryObject<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        
        // Add our block to the list of blocks the pipe can connect to
        // This is done after registration so the block is available
        registerConnectableBlocks();
    }
    
    /**
     * Register our block in the list of blocks to which Matter Network pipes can connect
     */
    private static void registerConnectableBlocks() {
        try {
            // Use reflection to safely access the ALLOWED_CONNECTION_BLOCKS field
            try {
                var field = MatterPipeBlock.class.getDeclaredField("ALLOWED_CONNECTION_BLOCKS");
                if (field != null) {
                    field.setAccessible(true);
                    var list = field.get(null);
                    if (list != null) {
                        // Use reflection to add our predicate
                        java.lang.reflect.Method addMethod = list.getClass().getMethod("add", Object.class);
                        addMethod.invoke(list, (java.util.function.Predicate<net.minecraft.world.level.block.Block>) block -> 
                            block instanceof RepAE2BridgeBl || 
                            (block.getClass().getName().contains("repae2bridge"))
                        );
                    }
                }
            } catch (NoSuchFieldException e) {
                // If the field doesn't exist, try with the alternative API if it exists
                try {
                    var method = MatterPipeBlock.class.getMethod("registerExternalConnectableBlock", java.util.function.Predicate.class);
                    method.invoke(null, (java.util.function.Predicate<net.minecraft.world.level.block.Block>) block -> 
                        block instanceof RepAE2BridgeBl || 
                        (block.getClass().getName().contains("repae2bridge"))
                    );
                } catch (Exception ex) {
                    // Only debug log as this functionality is secondary
                }
            } catch (Exception e) {
                // Only debug log as this functionality is secondary
            }
        } catch (Exception e) {
            // Handle the exception in case the field is not yet initialized
        }
    }
}
