package net.unfamily.repae2bridge;

import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.unfamily.repae2bridge.block.ModBlocks;
import net.unfamily.repae2bridge.block.entity.RepAE2BridgeBlockEntity;

/**
 * Class to manage the registration of bridge capabilities
 */
public class RepAE2BridgeCapabilities {

    /**
     * Registers bridge capabilities
     * @param event The capability registration event
     */
    public static void register(RegisterCapabilitiesEvent event) {
        // For Forge 1.20.1, capability registration is different
        // and is done through attaching capability providers
        // This logic must be implemented in the BlockEntity itself
    }
}
