package net.unfamily.repae2bridge.block.entity;

import net.unfamily.repae2bridge.RepAE2Bridge;
import net.unfamily.repae2bridge.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, RepAE2Bridge.MOD_ID);

    public static final RegistryObject<BlockEntityType<RepAE2BridgeBlockEntity>> REPAE2BRIDGE_BE =
            BLOCK_ENTITIES.register("bridge_be", () -> BlockEntityType.Builder.of(
                    RepAE2BridgeBlockEntity::new, ModBlocks.REPAE2BRIDGE.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
