package net.unfamily.repae2bridge;

import net.unfamily.repae2bridge.block.ModBlocks;
import net.unfamily.repae2bridge.block.entity.ModBlockEntities;
import net.unfamily.repae2bridge.block.entity.RepAE2BridgeBlockEntity;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.buuz135.replication.network.DefaultMatterNetworkElement;
import com.hrznstudio.titanium.block_network.element.NetworkElementRegistry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.unfamily.repae2bridge.item.ModItems;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import appeng.api.networking.IInWorldGridNodeHost;
import com.buuz135.replication.block.MatterPipeBlock;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(RepAE2Bridge.MOD_ID)
public class RepAE2Bridge
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "rep_ae2_bridge";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Definire la capability per IInWorldGridNodeHost
    private static final BlockCapability<IInWorldGridNodeHost, Void> IN_WORLD_GRID_NODE_HOST = 
        BlockCapability.createVoid(ResourceLocation.parse("ae2:inworld_gridnode_host"), IInWorldGridNodeHost.class);

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public RepAE2Bridge(IEventBus modEventBus, ModContainer modContainer)
    {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        
        // Registra l'evento per le capacità
        modEventBus.addListener(this::registerCapabilities);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (ExampleMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Registra i moduli
        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ;
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
        
        // Registra il factory degli elementi di rete per la mod Replication
        // Questo è cruciale per far funzionare la connessione alla rete Replication
        event.enqueueWork(() -> {
            try {
                // Registra direttamente il factory per l'elemento DefaultMatterNetworkElement come fa Replication
                LOGGER.info("Registering DefaultMatterNetworkElement factory for Replication integration");
                NetworkElementRegistry.INSTANCE.addFactory(DefaultMatterNetworkElement.ID, new DefaultMatterNetworkElement.Factory());
                LOGGER.info("Replication network integration complete");
            } catch (Exception e) {
                LOGGER.error("Failed to register with Replication network system", e);
            }
        });

        // Registra il namespace della nostra mod come namespace consentito per i cavi di Replication
        event.enqueueWork(() -> {
            // Questo garantisce che venga eseguito nel thread principale
            registerWithReplicationMod();
        });
    }

    // Registra le capacità del bridge
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Registra la capacità IInWorldGridNodeHost per il blocco bridge
        event.registerBlock(
            IN_WORLD_GRID_NODE_HOST,
            (level, pos, state, be, context) -> {
                if (be instanceof RepAE2BridgeBlockEntity bridge) {
                    return bridge;
                }
                return null;
            },
            ModBlocks.REPAE2BRIDGE.get()
        );

        // Log che le capacità sono state registrate
        LOGGER.info("AE2 Bridge capacities registered successfully");
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("RepAE2Bridge: Server starting");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            LOGGER.info("RepAE2Bridge: Client setup");
        }
    }

    /**
     * Registra il namespace della mod nella lista dei namespace consentiti per i cavi di Replication
     */
    private void registerWithReplicationMod() {
        LOGGER.info("Registering RepAE2Bridge with Replication mod");
        
        // Aggiungi il namespace della mod alla lista dei namespace consentiti
        MatterPipeBlock.ALLOWED_CONNECTION_BLOCKS.add(block -> 
            block.getClass().getName().contains(MOD_ID)
        );
        
        LOGGER.info("Successfully registered with Replication mod");
    }
}
