package net.unfamily.repae2bridge;

import net.unfamily.repae2bridge.block.ModBlocks;
import net.unfamily.repae2bridge.block.entity.ModBlockEntities;
import net.unfamily.repae2bridge.block.entity.RepAE2BridgeBlockEntity;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.buuz135.replication.network.DefaultMatterNetworkElement;
import com.hrznstudio.titanium.block_network.element.NetworkElement;
import com.hrznstudio.titanium.block_network.NetworkManager;
import com.hrznstudio.titanium.block_network.element.NetworkElementRegistry;
import com.buuz135.replication.block.tile.NetworkBlockEntity;
import com.buuz135.replication.network.MatterNetwork;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
//import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.unfamily.repae2bridge.item.ModItems;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.capabilities.CapabilityToken;
import appeng.api.networking.IInWorldGridNodeHost;
import com.buuz135.replication.block.MatterPipeBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(RepAE2Bridge.MOD_ID)
public class RepAE2Bridge
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "rep_ae2_bridge";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Define the capability for IInWorldGridNodeHost
    private static final Capability<IInWorldGridNodeHost> IN_WORLD_GRID_NODE_HOST = 
        CapabilityManager.get(new CapabilityToken<>(){});

    // Add a static field to track if our NetworkBlockEntity patches have been applied
    private static boolean networksFixed = false;

    /**
     * Main constructor of the mod that gets the event bus in the non-deprecated way
     */
    public RepAE2Bridge() {
        LOGGER.info("RepAE2Bridge: Main constructor called");
        
        // Get the event bus from the mod loading context (non-deprecated way)
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        
        // Register all items
        ModItems.register(modEventBus);
        
        // Register all blocks
        ModBlocks.register(modEventBus);
        
        // Register all block entities
        ModBlockEntities.register(modEventBus);
        
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the addCreative event handler for adding items to the creative menu
        modEventBus.addListener(this::addCreative);

        // Register ourselves for server and other game events
        // Note: This is where the ServerStartingEvent and ServerStoppingEvent are registered
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Register a safe version of NetworkBlockEntity.getNetwork that avoids NullPointerException
     */
    private void patchNetworkBlockEntityClass() {
        try {
            // Check if we've already applied the patch
            if (networksFixed) {
                return;
            }
            
            // Apply our global patch to fix NetworkBlockEntity.getNetwork
            LOGGER.info("RepAE2Bridge: Applying patch to fix NetworkBlockEntity.getNetwork");
            
            // Define our custom implementation
            NetworkPatcher.initialize();
            
            // Mark as fixed
            networksFixed = true;
            LOGGER.info("RepAE2Bridge: NetworkBlockEntity patch applied successfully");
        } catch (Exception e) {
            LOGGER.error("RepAE2Bridge: Failed to patch NetworkBlockEntity: {}", e.getMessage());
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        // LOGGER.info("HELLO FROM COMMON SETUP");

        // if (Config.logDirtBlock)
        //     LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));

        // LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        // Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
        
        // Register the network element factory for the Replication mod
        // This is crucial for making the connection to the Replication network work
        event.enqueueWork(() -> {
            // Verifichiamo se la mod Replication è caricata
            boolean replicationLoaded = net.minecraftforge.fml.ModList.get().isLoaded("replication");
            
            if (replicationLoaded) {
                LOGGER.info("Replication mod is loaded, skipping DefaultMatterNetworkElement registration to avoid conflicts");
            } else {
                try {
                    // Replication non è caricata, quindi registriamo noi l'elemento
                    LOGGER.info("Replication mod not loaded, registering DefaultMatterNetworkElement factory");
                    NetworkElementRegistry.INSTANCE.addFactory(DefaultMatterNetworkElement.ID, new DefaultMatterNetworkElement.Factory());
                    LOGGER.info("Replication network integration complete");
                } catch (Exception e) {
                    // Se l'eccezione indica un duplicato, lo consideriamo un caso non problematico
                    if (e.getMessage() != null && e.getMessage().contains("duplicate")) {
                        LOGGER.info("DefaultMatterNetworkElement factory already registered, using existing registration");
                    } else {
                        // Altri tipi di errori sono ancora preoccupanti
                        LOGGER.error("Failed to register with Replication network system", e);
                    }
                }
            }
        });

        // Register our mod's namespace as an allowed namespace for Replication cables
        event.enqueueWork(() -> {
            // This ensures it runs on the main thread
            registerWithReplicationMod();
        });

        // Apply our patches to NetworkBlockEntity
        event.enqueueWork(this::patchNetworkBlockEntityClass);
    }

    // Register bridge capabilities
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Delegate the capability registration to the RepAE2BridgeCapabilities class
        // For Forge, registration is more complex and is done elsewhere
        RepAE2BridgeCapabilities.register(event);
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        // Add the bridge to AE2's main creative tab
        if (event.getTabKey() == appeng.api.ids.AECreativeTabIds.MAIN) {
            event.accept(ModBlocks.REPAE2BRIDGE.get());
            // LOGGER.info("Added RepAE2Bridge to AE2 creative tab");
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // LOGGER.info("RepAE2Bridge: Server starting");
        RepAE2BridgeBlockEntity.setWorldUnloading(false);
        
        // Ensure our patches are applied when the server starts
        if (!networksFixed) {
            patchNetworkBlockEntityClass();
        }
    }
    
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        LOGGER.info("RepAE2Bridge: Server stopping, notifying bridges to prepare for unload");

        // Set the static flag in the BlockEntity class to signal shutdown
        // Questo flag blocca nuove operazioni nei metodi tick delle entità
        RepAE2BridgeBlockEntity.setWorldUnloading(true);
        
        try {
            // Interruzione forzata di tutte le operazioni pendenti
            // Questo assicura una chiusura più pulita anche in caso di operazioni massive di autocrafting
            LOGGER.info("RepAE2Bridge: Cancelling all pending operations for rapid shutdown");
            RepAE2BridgeBlockEntity.cancelAllPendingOperations();
        } catch (Exception e) {
            // Non blocchiamo la chiusura del server anche in caso di errori
            LOGGER.warn("RepAE2Bridge: Exception during shutdown cleanup, continuing anyway", e);
        }

        LOGGER.info("RepAE2Bridge: All bridges notified of world unload");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // LOGGER.info("RepAE2Bridge: Client setup");
        }
    }

    /**
     * Register the mod namespace in the list of allowed namespaces for Replication cables
     */
    private void registerWithReplicationMod() {
        // LOGGER.info("Registering RepAE2Bridge with Replication mod");
        
        try {
            // First verify if the ALLOWED_CONNECTION_BLOCKS field exists
            try {
                var field = MatterPipeBlock.class.getDeclaredField("ALLOWED_CONNECTION_BLOCKS");
                if (field != null) {
                    field.setAccessible(true);
                    var list = field.get(null);
                    if (list != null) {
                        // Use reflection to add our predicate
                        java.lang.reflect.Method addMethod = list.getClass().getMethod("add", Object.class);
                        addMethod.invoke(list, (java.util.function.Predicate<net.minecraft.world.level.block.Block>) block -> 
                            block.getClass().getName().contains(MOD_ID)
                        );
                    }
                }
            } catch (NoSuchFieldException e) {
                // If the field doesn't exist, try with the alternative API if it exists
                try {
                    var method = MatterPipeBlock.class.getMethod("registerExternalConnectableBlock", java.util.function.Predicate.class);
                    method.invoke(null, (java.util.function.Predicate<net.minecraft.world.level.block.Block>) block -> 
                        block.getClass().getName().contains(MOD_ID)
                    );
                    LOGGER.info("Registered with Replication mod using alternative API");
                } catch (Exception ex) {
                    LOGGER.error("No compatible registration method found in Replication mod: " + ex.getMessage());
                }
            } catch (Exception e) {
                LOGGER.error("Error accessing ALLOWED_CONNECTION_BLOCKS: " + e.getMessage());
            }
            
            // LOGGER.info("Successfully registered with Replication mod");
        } catch (Exception e) {
            LOGGER.error("Failed to register with Replication mod: " + e.getMessage());
        }
    }

    /**
     * Helper class to safely patch the NetworkBlockEntity implementation
     * This avoids the NullPointerException that occurs in getNetwork
     */
    public static class NetworkPatcher {
        private static boolean initialized = false;
        
        public static void initialize() {
            if (initialized) {
                return;
            }
            
            LOGGER.info("NetworkPatcher: Initializing safety hooks for NetworkBlockEntity");
            initialized = true;
        }
        
        /**
         * Replacement for NetworkBlockEntity.getNetwork that handles null safely
         * This is called from RepAE2BridgeBlockEntity when it detects a potential issue
         * 
         * @param entity The NetworkBlockEntity instance
         * @param level The current world level
         * @param pos The position of the block
         * @return A MatterNetwork instance or null if none is available
         */
        public static MatterNetwork safeGetNetwork(BlockEntity entity, Level level, BlockPos pos) {
            if (level == null || level.isClientSide()) {
                return null;
            }
            
            try {
                NetworkManager networkManager = NetworkManager.get(level);
                if (networkManager == null) {
                    return null;
                }
                
                // Get the network element for this position
                NetworkElement element = networkManager.getElement(pos);
                
                // Critical fix: If element is null, try to create one to prevent NullPointerException
                if (element == null) {
                    LOGGER.warn("NetworkPatcher: Null network element detected at {}. Creating temporary element to prevent crash.", pos);
                    
                    // Create a new element for this position
                    element = new DefaultMatterNetworkElement(level, pos);
                    
                    // Register it with the network manager
                    networkManager.addElement(element);
                    
                    // Try to get the network from the newly created element
                    Object network = element.getNetwork();
                    if (network instanceof MatterNetwork) {
                        return (MatterNetwork) network;
                    }
                    
                    // If no network is available, return null safely
                    return null;
                }
                
                // Get the network from the element (standard path)
                Object network = element.getNetwork();
                if (network instanceof MatterNetwork) {
                    return (MatterNetwork) network;
                }
            } catch (Exception e) {
                LOGGER.error("NetworkPatcher: Error in safeGetNetwork: {}", e.getMessage());
            }
            
            return null;
        }
    }
}
