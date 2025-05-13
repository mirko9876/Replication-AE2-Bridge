package net.unfamily.repae2bridge;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.unfamily.repae2bridge.block.ModBlocks;
import net.unfamily.repae2bridge.block.entity.RepAE2BridgeBlockEntity;

/**
 * Classe per gestire la registrazione delle capabilities del bridge
 */
public class RepAE2BridgeCapabilities {

    /**
     * Registra le capabilities del bridge
     * @param event L'evento di registrazione delle capabilities
     */
    public static void register(RegisterCapabilitiesEvent event) {
        // Registra la capability ItemHandler per il bridge
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, blockPos, blockState, blockEntity, direction) -> {
            if (blockEntity instanceof RepAE2BridgeBlockEntity bridge && (direction == Direction.UP || direction == null)) {
                return bridge.getOutput();
            }
            return null;
        }, ModBlocks.REPAE2BRIDGE.get());
    }
}
