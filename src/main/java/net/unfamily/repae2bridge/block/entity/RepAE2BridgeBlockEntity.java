package net.unfamily.repae2bridge.block.entity;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.AECableType;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.MEStorage;
import com.buuz135.replication.block.tile.ReplicationMachine;
import com.buuz135.replication.calculation.client.ClientReplicationCalculation;
import com.buuz135.replication.network.MatterNetwork;
import com.buuz135.replication.api.IMatterType;
import com.buuz135.replication.calculation.MatterValue;
import com.hrznstudio.titanium.block.BasicTileBlock;
import com.hrznstudio.titanium.block_network.element.NetworkElement;
import com.hrznstudio.titanium.block_network.NetworkManager;
import com.buuz135.replication.network.DefaultMatterNetworkElement;
import com.hrznstudio.titanium.annotation.Save;
import com.hrznstudio.titanium.component.inventory.InventoryComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.repae2bridge.block.ModBlocks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.buuz135.replication.api.pattern.MatterPattern;
import com.buuz135.replication.block.tile.ChipStorageBlockEntity;
import com.buuz135.replication.api.task.IReplicationTask;
import com.buuz135.replication.api.task.ReplicationTask;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import appeng.core.definitions.AEItems;
import net.minecraft.world.item.Item;
import net.unfamily.repae2bridge.item.ModItems;
import com.buuz135.replication.ReplicationRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import appeng.me.helpers.MachineSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Queue;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.lang.StringBuilder;
import java.util.Objects;

/**
 * BlockEntity for the RepAE2Bridge that connects the AE2 network with the Replication matter network
 */
public class RepAE2BridgeBlockEntity extends ReplicationMachine<RepAE2BridgeBlockEntity>
        implements IInWorldGridNodeHost, ICraftingInventory, ICraftingProvider, IStorageProvider, IActionHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Constant for the number of ticks before processing accumulated requests
    private static final int REQUEST_ACCUMULATION_TICKS = 100;

    // Constant for initialization delay in ticks (3 seconds = 60 ticks)
    private static final int INITIALIZATION_DELAY = 60;

    // Variable to track if the networks have been initialized
    private byte initialized = 0;

    // Counter for initialization ticks
    private int initializationTicks = 0;

    // Queue of pending patterns
    private final Queue<IPatternDetails> pendingPatterns = new LinkedList<>();
    private final Map<IPatternDetails, KeyCounter[]> pendingInputs = new HashMap<>();

    // AE2 node for network connection
    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, new IGridNodeListener<RepAE2BridgeBlockEntity>() {
                @Override
                public void onSaveChanges(RepAE2BridgeBlockEntity nodeOwner, IGridNode node) {
                    nodeOwner.setChanged();
                }

                @Override
                public void onStateChanged(RepAE2BridgeBlockEntity nodeOwner, IGridNode node, IGridNodeListener.State state) {
                    // Update the BlockEntity state when the node state changes
                    if (nodeOwner.level != null) {
                        nodeOwner.level.sendBlockUpdated(nodeOwner.worldPosition, nodeOwner.getBlockState(),
                                nodeOwner.getBlockState(), 3);

                        // Also update the CONNECTED property state in the block
                        updateConnectedState();

                        // If the node is active, update patterns
                        if (state == IGridNodeListener.State.POWER && node.isActive()) {
                            // LOGGER.info("Bridge: AE2 node active, requesting pattern update");
                            ICraftingProvider.requestUpdate(mainNode);

                            // Also request a storage update to show matter items
                            IStorageProvider.requestUpdate(mainNode);
                        }
                    }
                }

                @Override
                public void onGridChanged(RepAE2BridgeBlockEntity nodeOwner, IGridNode node) {
                    // Update the BlockEntity state when the grid changes
                    if (nodeOwner.level != null) {
                        nodeOwner.level.sendBlockUpdated(nodeOwner.worldPosition, nodeOwner.getBlockState(),
                                nodeOwner.getBlockState(), 3);

                        // Also update the CONNECTED property state in the block
                        updateConnectedState();

                        // Force a pattern update when the grid changes
                        ICraftingProvider.requestUpdate(mainNode);

                        // Also request a storage update to show matter items
                        IStorageProvider.requestUpdate(mainNode);
                    }
                }
            })
            .setVisualRepresentation(ModBlocks.REPAE2BRIDGE.get())
            .setInWorldNode(true)
            .setFlags(GridFlags.REQUIRE_CHANNEL)
            .setExposedOnSides(EnumSet.allOf(Direction.class))
            .addService(ICraftingProvider.class, this)
            .addService(IStorageProvider.class, this)
            .setTagName("main");

    // Flag to track if the node has been created
    private boolean nodeCreated = false;

    // Flag to indicate if we should try to reconnect to networks
    private boolean shouldReconnect = false;

    // Terminal components
    @Save
    private InventoryComponent<RepAE2BridgeBlockEntity> output;
    @Save
    private int sortingTypeValue;
    @Save
    private int sortingDirection;
    @Save
    private int matterOpediaSortingTypeValue;
    @Save
    private int matterOpediaSortingDirection;
    private TerminalPlayerTracker terminalPlayerTracker;

    // Unique identifier for this block
    @Save
    private UUID blockId;

    // Map to track requests for patterns
    private final Map<UUID, Map<ItemStack, Integer>> patternRequests = new HashMap<>();
    // Map to track active tasks with source information
    private final Map<UUID, Map<String, TaskSourceInfo>> activeTasks = new HashMap<>();
    // Map to track requests by source block
    private final Map<UUID, Map<ItemStack, Integer>> patternRequestsBySource = new HashMap<>();
    // Temporary counters for crafting requests
    private final Map<UUID, Map<ItemWithSourceId, Integer>> requestCounters = new HashMap<>();
    private int requestCounterTicks = 0;

    // Timer for periodic pattern updates
    private int patternUpdateTicks = 0;
    private static final int PATTERN_UPDATE_INTERVAL = 100; // Update every 5 seconds (100 ticks)

    // Cache of matter insufficient warnings to avoid repetitions
    private Map<String, Long> lastMatterWarnings = new HashMap<>();
    // Minimum time between consecutive warnings for the same item (in ticks)
    private static final int WARNING_COOLDOWN = 600; // 30 seconds

    // Static flag to track world unloading state
    private static boolean worldUnloading = false;

    /**
     * Sets the world unloading state
     * Called from the mod main class when the server is stopping
     */
    public static void setWorldUnloading(boolean unloading) {
        worldUnloading = unloading;
        LOGGER.info("Bridge: World unloading state set to {}", unloading);
    }

    /**
     * Checks if the world is currently unloading
     */
    public static boolean isWorldUnloading() {
        return worldUnloading;
    }

    public RepAE2BridgeBlockEntity(BlockPos pos, BlockState blockState) {
        super((BasicTileBlock<RepAE2BridgeBlockEntity>) ModBlocks.REPAE2BRIDGE.get(),
                ModBlockEntities.REPAE2BRIDGE_BE.get(),
                pos,
                blockState);

        // Generate a unique identifier for this block
        this.blockId = UUID.randomUUID();

        // Initialize terminal component
        this.terminalPlayerTracker = new TerminalPlayerTracker();
        this.sortingTypeValue = 0;
        this.sortingDirection = 1;
        this.matterOpediaSortingTypeValue = 0;
        this.matterOpediaSortingDirection = 1;

        // Initialize the output inventory component with 18 slots (9x2)
        this.output = new InventoryComponent<RepAE2BridgeBlockEntity>("output", 11, 131, 9*2)
                .setRange(9, 2)
                .setComponentHarness(this)
                .setInputFilter((stack, slot) -> true); // Allows insertion of any item
        this.addInventory(this.output);
    }

    @NotNull
    @Override
    public RepAE2BridgeBlockEntity getSelf() {
        return this;
    }

    /**
     * Check if there is another bridge in the specified direction
     * @param direction The direction to check
     * @return true if there is another bridge in the specified direction
     */
    private boolean hasBridgeInDirection(Direction direction) {
        if (level != null) {
            BlockPos neighborPos = worldPosition.relative(direction);
            return level.getBlockEntity(neighborPos) instanceof RepAE2BridgeBlockEntity;
        }
        return false;
    }

    @Override
    protected NetworkElement createElement(Level level, BlockPos pos) {
        try {
            return new DefaultMatterNetworkElement(level, pos) {
                @Override
                public boolean canConnectFrom(Direction direction) {
                    BlockPos neighborPos = pos.relative(direction);
                    if (level.getBlockEntity(neighborPos) instanceof RepAE2BridgeBlockEntity) {
                        return false;
                    }
                    return super.canConnectFrom(direction);
                }
            };
        } catch (Exception e) {
            LOGGER.error("Failed to create Replication network element: {}", e.getMessage());
            return null; // O un fallback sicuro
        }
    }

    /**
     * Called when the BlockEntity is loaded or after placement
     */
    @Override
    public void onLoad() {
        // First initialize the Replication network (as done by the base class)
        super.onLoad();
        //LOGGER.info("Bridge: onLoad called at {}", worldPosition);

        // Initialize the AE2 node if it hasn't been done
        if (!nodeCreated && level != null && !level.isClientSide()) {
            try {
                mainNode.create(level, worldPosition);
                nodeCreated = true;
                forceNeighborUpdates();
                updateConnectedState();
                ICraftingProvider.requestUpdate(mainNode);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize AE2 node: {}", e.getMessage());
                shouldReconnect = true;
            }
        }
        // Reset the flag if it was loaded but the node no longer exists
        else if (nodeCreated && mainNode.getNode() == null) {
            // LOGGER.warn("Bridge: Existing node not found, requesting reconnection");
            nodeCreated = false;
            shouldReconnect = true;
        }
    }

    /**
     * Update the visual connection state of the block
     */
    private void updateConnectedState() {
        if (level != null && !level.isClientSide()) {
            BlockState currentState = level.getBlockState(worldPosition);
            if (currentState.getBlock() == ModBlocks.REPAE2BRIDGE.get()) {
                boolean isConnected = isActive() && getNetwork() != null;
                if (currentState.getValue(net.unfamily.repae2bridge.block.custom.RepAE2BridgeBl.CONNECTED) != isConnected) {
                    level.setBlock(worldPosition, currentState.setValue(
                            net.unfamily.repae2bridge.block.custom.RepAE2BridgeBl.CONNECTED, isConnected), 3);
                }
            }
        }
    }

    /**
     * Check if there is an AE2 controller in the network by checking if there are active AE2 cables adjacent
     * @return true if an active AE2 cable is found, which is probably connected to a controller
     */
    private boolean hasAE2NetworkConnection() {
        if (level != null && !level.isClientSide()) {
            // Check all adjacent blocks to see if there are active AE2 cables
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(direction);

                // If the block has a block entity and implements IInWorldGridNodeHost
                if (level.getBlockEntity(neighborPos) instanceof IInWorldGridNodeHost host) {
                    IGridNode node = host.getGridNode(direction.getOpposite());
                    if (node != null && node.isActive()) {
                        // If the node is active, it is probably connected to a controller
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Force updates to adjacent blocks
     */
    private void forceNeighborUpdates() {
        if (level != null && !level.isClientSide()) {
            // Force an update of the block itself first
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);

            // Then force updates to adjacent blocks
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (!neighborState.isAir()) {
                    // Notify the adjacent block first (to trigger its connections)
                    level.neighborChanged(neighborPos, getBlockState().getBlock(), worldPosition);
                }
            }
        }
    }

    /**
     * Handle when a neighboring block changes
     * Called from the RepAE2BridgeBl block
     * @param fromPos Position of the changed neighbor
     */
    public void handleNeighborChanged(BlockPos fromPos) {
        if (level != null && !level.isClientSide()) {
            // If the changed block is another bridge, ignore the update
            Direction directionToNeighbor = null;
            for (Direction dir : Direction.values()) {
                if (worldPosition.relative(dir).equals(fromPos)) {
                    directionToNeighbor = dir;
                    break;
                }
            }

            if (directionToNeighbor != null && level.getBlockEntity(fromPos) instanceof RepAE2BridgeBlockEntity) {
                // Debug log disabled for production
                // LOGGER.debug("Bridge: Ignored update from another bridge at {}", fromPos);
                return;
            }

            // Check if there is an AE2 controller in the network
            boolean hasAE2Connection = hasAE2NetworkConnection();

            // If there is an AE2 connection and the node is not created, initialize the node
            if (hasAE2Connection && !nodeCreated) {
                try {
                    // Debug log disabled for production
                    // LOGGER.debug("Bridge: Initializing AE2 node from handleNeighborChanged");
                    mainNode.create(level, worldPosition);
                    nodeCreated = true;

                    // Notify adjacent blocks
                    forceNeighborUpdates();

                    // Update the connection state visually
                    updateConnectedState();

                    // Force a pattern update
                    // Debug log disabled for production
                    // LOGGER.debug("Bridge: Requesting AE2 pattern update");
                    ICraftingProvider.requestUpdate(mainNode);
                }catch (Exception e) {
                    LOGGER.error("Failed to initialize AE2 node: {}", e.getMessage());
                    shouldReconnect = true;
                }
            }
            // If the node exists, update only adjacent blocks
            else if (mainNode.getNode() != null) {
                forceNeighborUpdates();
            }

            // Update the visual state
            updateConnectedState();
        }
    }

    /**
     * Explicitly disconnect this block from both networks
     * Called when the block is removed
     */
    public void disconnectFromNetworks() {
        // Disconnect from the AE2 network
        if (level != null && !level.isClientSide() && mainNode != null) {
            mainNode.destroy();
            nodeCreated = false;
        }

        // The disconnection from the Replication network is handled in super.setRemoved()
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            // Destroy the node when the block is removed
            mainNode.destroy();
            nodeCreated = false;
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide()) {
            // Destroy the node when the chunk is unloaded
            mainNode.destroy();
            nodeCreated = false;
            shouldReconnect = true; // Mark for reconnection when the chunk is reloaded
        }
        super.onChunkUnloaded();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Save the state of the AE2 node
        mainNode.saveToNBT(tag);
        // Also save the node creation flag
        tag.putBoolean("nodeCreated", nodeCreated);
        // Save the reconnection flag
        tag.putBoolean("shouldReconnect", shouldReconnect);
        // Save the block's unique identifier
        if (blockId != null) {
            tag.putUUID("blockId", blockId);
        }
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Load the state of the AE2 node
        mainNode.loadFromNBT(tag);
        // Load the node creation flag
        if (tag.contains("nodeCreated")) {
            nodeCreated = tag.getBoolean("nodeCreated");
        }
        // Load the reconnection flag
        if (tag.contains("shouldReconnect")) {
            shouldReconnect = tag.getBoolean("shouldReconnect");
        }
        // Load the block's unique identifier
        if (tag.contains("blockId")) {
            blockId = tag.getUUID("blockId");
        } else {
            // If no ID exists, generate a new one
            blockId = UUID.randomUUID();
        }
    }

    // =================== Implement IInWorldGridNodeHost ===================

    @Override
    @Nullable
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART; // Use SMART for compatibility with most AE2 cables
    }

    /**
     * Implementation of IActionHost interface
     * @return The actionable grid node for this block entity
     */
    @Override
    @Nullable
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    /**
     * Handles server tick for synchronizing the two networks
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state, RepAE2BridgeBlockEntity blockEntity) {
        super.serverTick(level, pos, state, blockEntity);

        //LOGGER.warn("Bridge: initialization: {}", initialized);

        // Check if the world is unloading
        if (worldUnloading && initialized == 1) {
            // Call onWorldUnload if the world is unloading and we're initialized
            onWorldUnload();
            return;
        }

        // Handle reinitialization after world reload
        if (shouldReconnect && initialized == 1 && !nodeCreated) {
            LOGGER.info("Bridge: Reconnecting after world reload");
            if (mainNode.getNode() == null) {
                try {
                    mainNode.create(level, worldPosition);
                    nodeCreated = true;

                    // Update connections
                    forceNeighborUpdates();
                    updateConnectedState();

                    // Force pattern and storage updates
                    ICraftingProvider.requestUpdate(mainNode);
                    IStorageProvider.requestUpdate(mainNode);
                    shouldReconnect = false;
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize AE2 node: {}", e.getMessage());
                    shouldReconnect = true;
                }
            }
        }

        // Delayed initialization handling
        if (initialized == 0) {
            initializationTicks++;
            if (initializationTicks >= INITIALIZATION_DELAY) {
                initialized = 1;
                // LOGGER.info("Bridge: Initialization completed after 60 ticks");

                // Force update of patterns and connections after initialization
                if (!level.isClientSide()) {
                    forceNeighborUpdates();
                    updateConnectedState();
                    ICraftingProvider.requestUpdate(mainNode);
                    IStorageProvider.requestUpdate(mainNode);
                }
            }
        }

        // Try to transfer items from local inventory to AE2 every 20 ticks (1 second)
        if (level.getGameTime() % 20 == 0 && initialized == 1) {
            transferItemsToAE2();
        }

        // Periodic pattern updates - remove check for matterUpdatesBlocked
        if (patternUpdateTicks >= PATTERN_UPDATE_INTERVAL) {
            if (isActive() && getNetwork() != null) {
                // LOGGER.info("Bridge: Periodic pattern update");
                ICraftingProvider.requestUpdate(mainNode);

                // Also update storage to show new matter quantities
                IStorageProvider.requestUpdate(mainNode);
            }
            patternUpdateTicks = 0;
        } else {
            patternUpdateTicks++;
        }

        // Periodically check if there are virtual matter items in the AE2 network
        // that shouldn't be there and remove them
        if (level.getGameTime() % 40 == 0 && isActive() && mainNode.getNode() != null) {
            IGrid grid = mainNode.getNode().getGrid();
            if (grid != null) {
                IStorageService storageService = grid.getStorageService();
                if (storageService != null) {
                    // Get all items in the network
                    KeyCounter items = storageService.getInventory().getAvailableStacks();

                    // Check if there are virtual matter items
                    items.forEach(entry -> {
                        AEKey key = entry.getKey();
                        if (key instanceof AEItemKey itemKey && isVirtualMatterItem(itemKey.getItem())) {
                            long amount = entry.getLongValue();
                            if (amount > 0) {
                                // LOGGER.info("Bridge: Detected {} virtual matter items {} in the network. Removal in progress...",
                                //     amount, itemKey.getItem().getDescriptionId());

                                // Extract all virtual matter to remove it
                                storageService.getInventory().extract(itemKey, amount, Actionable.MODULATE, null);

                                // LOGGER.info("Bridge: Removed {} virtual matter items {} from the network",
                                //     amount, itemKey.getItem().getDescriptionId());
                            }
                        }
                    });
                }
            }
        }

        // Temporary counter management
        if (requestCounterTicks >= REQUEST_ACCUMULATION_TICKS) {
            // Before resetting, create tasks for all items with pending requests
            MatterNetwork network = getNetwork();
            if (network != null && !requestCounters.isEmpty()) {
                // LOGGER.info("Bridge: Creating task for {} items with pending requests", requestCounters.size());

                // Calculate the total number of tasks that will be created
                int totalItems = 0;
                for (UUID sourceId : requestCounters.keySet()) {
                    Map<ItemWithSourceId, Integer> sourceCounters = requestCounters.get(sourceId);
                    for (int count : sourceCounters.values()) {
                        totalItems += count;
                    }
                }

                // For each source block
                for (UUID sourceId : requestCounters.keySet()) {
                    Map<ItemWithSourceId, Integer> sourceCounters = requestCounters.get(sourceId);

                    // For each item with pending requests from this source
                    for (Map.Entry<ItemWithSourceId, Integer> entry : sourceCounters.entrySet()) {
                        ItemWithSourceId key = entry.getKey();
                        ItemStack itemStack = key.getItemStack();
                        int count = entry.getValue();

                        if (count > 0) {
                            // Search for the corresponding pattern in Replication
                            for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                                var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                                if (tile instanceof ChipStorageBlockEntity chipStorage) {
                                    for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                                        if (pattern.getStack().getItem().equals(itemStack.getItem())) {
                                            // LOGGER.info("Bridge: Creating task for {} item of {} (requests accumulated in {} ticks)",
                                            //    count, itemStack.getItem().getDescriptionId(), REQUEST_ACCUMULATION_TICKS);

                                            // Create a replication task with the total quantity
                                            ReplicationTask task = new ReplicationTask(
                                                    pattern.getStack(),
                                                    count, // Use the total number of accumulated requests
                                                    IReplicationTask.Mode.MULTIPLE,
                                                    this.worldPosition
                                            );

                                            // Add the task to the network
                                            String taskId = task.getUuid().toString();
                                            network.getTaskManager().getPendingTasks().put(taskId, task);

                                            // Add to the active tasks map with source information
                                            TaskSourceInfo info = new TaskSourceInfo(itemStack, sourceId);

                                            // Initialize the map for this source if needed
                                            Map<String, TaskSourceInfo> sourceTasks = activeTasks.getOrDefault(sourceId, new HashMap<>());
                                            sourceTasks.put(taskId, info);
                                            activeTasks.put(sourceId, sourceTasks);

                                            // Update the request counter for the pattern by source
                                            Map<ItemStack, Integer> sourceRequests = patternRequestsBySource.getOrDefault(sourceId, new HashMap<>());
                                            int currentPatternRequests = sourceRequests.getOrDefault(itemStack, 0);
                                            sourceRequests.put(itemStack, currentPatternRequests + count);
                                            patternRequestsBySource.put(sourceId, sourceRequests);

                                            // Also update the global counter for backward compatibility
                                            Map<ItemStack, Integer> globalRequests = patternRequests.getOrDefault(sourceId, new HashMap<>());
                                            int currentGlobalRequests = globalRequests.getOrDefault(itemStack, 0);
                                            globalRequests.put(itemStack, currentGlobalRequests + count);
                                            patternRequests.put(sourceId, globalRequests);

                                            //LOGGER.info("Bridge: Task created with ID {}, total requests for this pattern: {}",
                                            //    taskId, currentPatternRequests + count);

                                            // Extract the necessary matter
                                            var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                            if (matterCompound != null) {
                                                // Extract each type of matter from the network
                                                for (MatterValue matterValue : matterCompound.getValues().values()) {
                                                    var matterType = matterValue.getMatter();
                                                    var matterAmount = (long)Math.ceil(matterValue.getAmount()) * count;

                                                    // Find the corresponding virtual item
                                                    Item matterItem = getItemForMatterType(matterType);
                                                    if (matterItem != null) {
                                                        AEItemKey matterKey = AEItemKey.of(matterItem);

                                                        // Note: now we extract real matter for replication
                                                        // LOGGER.info("Bridge: Extracting {} real matter {} for replication",
                                                        //    matterAmount, matterType.getName());

                                                        // Extract matter from the Replication network
                                                        // Decrease the matter available from the network
                                                        // In Replication, there is no direct method to extract matter from the network
                                                        // so here we simulate extraction by removing the count from the virtual display

                                                        // We consume virtual matter always to avoid pattern blockages
                                                        long extracted = extract(matterKey, matterAmount, Actionable.MODULATE);
                                                        // LOGGER.info("Bridge: Consumed virtual matter {}: {}", matterType.getName(), extracted);
                                                    }
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Now we can reset the counters
            requestCounters.clear();
            requestCounterTicks = 0;
            // LOGGER.info("Bridge: Reset request counters after {} ticks", REQUEST_ACCUMULATION_TICKS);
        } else {
            requestCounterTicks++;
        }

        // Check network connection to the Replication network
        if (getNetwork() == null) {
            // LOGGER.warn("Bridge: No Replication network found during tick");
            // If not connected to the Replication network, try to connect
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager != null && networkManager.getElement(pos) == null) {
                // LOGGER.info("Bridge: Attempting to reconnect to the Replication network");
                networkManager.addElement(createElement(level, pos));
            }
        }

        // Pattern queue management
        if (!pendingPatterns.isEmpty() && !isBusy()) {
            IPatternDetails pattern = pendingPatterns.poll();
            KeyCounter[] inputs = pendingInputs.remove(pattern);
            if (pattern != null && inputs != null) {
                // LOGGER.info("Bridge: Processing pending pattern from queue");
                pushPattern(pattern, inputs);
            }
        }


        // Check less frequently the state of the AE2 node
        if (level.getGameTime() % 40 == 0) { // Only every 2 seconds
            if (!isActive() && shouldReconnect) {
                try {
                    if (mainNode.getNode() == null && !nodeCreated) {
                        mainNode.create(level, worldPosition);
                        nodeCreated = true;
                        forceNeighborUpdates();
                        updateConnectedState();
                        ICraftingProvider.requestUpdate(mainNode);
                    } else {
                        forceNeighborUpdates();
                    }
                    shouldReconnect = false;
                } catch (Exception e) {
                    LOGGER.error("Failed to reconnect AE2 node: {}", e.getMessage());
                }
            }

            // Update the visual state
            updateConnectedState();
        }

        // Update the terminal player tracker
        this.terminalPlayerTracker.checkIfValid();
    }

    /**
     * Overridden to ensure the node is always powered
     * Similar to the ReplicationTerminal that is always active
     */
    public boolean isPowered() {
        // Always returns true regardless of the actual power state
        return true;
    }

    /**
     * Improved implementation to get the Replication network
     * with error handling and greater robustness
     */
    @Override
    public MatterNetwork getNetwork() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        try {
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager == null) {
                LOGGER.warn("NetworkManager not found");
                return null;
            }
            NetworkElement element = networkManager.getElement(worldPosition);
            if (element == null) {
                element = createElement(level, worldPosition);
                if (element != null) {
                    networkManager.addElement(element);
                    forceNeighborUpdates();
                }
            }
            if (element != null && element.getNetwork() instanceof MatterNetwork matterNetwork) {
                return matterNetwork;
            }
        } catch (Exception e) {
            LOGGER.error("Error accessing Replication network: {}", e.getMessage());
        }
        return null;
    }

    // =================== Utility methods ===================

    public boolean isActive() {
        return mainNode.isActive();
    }

    /**
     * Method called when the entity is saved or loaded from/to disk
     */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null && !level.isClientSide()) {
            try {
                GridHelper.onFirstTick(this, blockEntity -> {
                    if (shouldReconnect || !nodeCreated) {
                        mainNode.create(level, worldPosition);
                        nodeCreated = true;
                        forceNeighborUpdates();
                        updateConnectedState();
                        ICraftingProvider.requestUpdate(mainNode);
                        shouldReconnect = false;
                    }
                });
            } catch (Exception e) {
                LOGGER.error("Failed to schedule AE2 node initialization: {}", e.getMessage());
            }
        }
    }

    /**
     * Utility method to get the Replication network with consistent naming
     * @return The Replication matter network
     */
    private MatterNetwork getReplicationNetwork() {
        return getNetwork();
    }

    // =================== Terminal implementation ===================

    @Override
    public ItemInteractionResult onActivated(Player playerIn, InteractionHand hand, Direction facing, double hitX, double hitY, double hitZ) {
        // Do not call super.onActivated() that would open the GUI
        // Do not call openGui(playerIn) that would open the GUI

        // Keep only the part related to the AE2 pattern update
        if (!level.isClientSide() && playerIn instanceof ServerPlayer serverPlayer) {
            // Update the patterns in AE2
            ICraftingProvider.requestUpdate(mainNode);
            // LOGGER.info("Bridge: Updating AE2 patterns from onActivated");
        }
        return ItemInteractionResult.SUCCESS;
    }

    public TerminalPlayerTracker getTerminalPlayerTracker() {
        return terminalPlayerTracker;
    }

    public InventoryComponent<RepAE2BridgeBlockEntity> getOutput() {
        return output;
    }

    /**
     * Method to receive items from the Replicator
     * @param stack The item stack to insert
     * @return true if the insertion was successful, false otherwise
     */
    public boolean receiveItemFromReplicator(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        // Debug log disabled for production
        // LOGGER.debug("Bridge: Received item from replicator: " + stack.getDisplayName().getString());

        // First try to insert directly into the AE2 network if connected
        boolean insertedIntoAE2 = false;
        long remainingCount = stack.getCount();

        if (mainNode.isActive() && mainNode.getNode() != null) {
            IGrid grid = mainNode.getNode().getGrid();
            if (grid != null) {
                IStorageService storageService = grid.getStorageService();
                if (storageService != null) {
                    // Debug log disabled for production
                    // LOGGER.debug("Bridge: Attempting to insert into AE2 storage first");

                    // Try to insert the item into the AE2 storage with high priority
                    AEItemKey key = AEItemKey.of(stack);
                    long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, new MachineSource(this));

                    if (inserted > 0) {
                        // Debug log disabled for production
                        // LOGGER.debug("Bridge: Successfully inserted " + inserted + " items into AE2 storage");

                        // Remove the items from the output inventory since they were inserted into AE2
                        for (int i = 0; i < output.getSlots(); i++) {
                            ItemStack slotStack = output.getStackInSlot(i);
                            if (ItemStack.isSameItem(slotStack, stack)) {
                                int toRemove = (int) Math.min(inserted, slotStack.getCount());
                                slotStack.shrink(toRemove);
                                inserted -= toRemove;
                                if (inserted <= 0) break;
                            }
                        }

                        remainingCount -= inserted;
                        insertedIntoAE2 = true;

                        // If all items were inserted into AE2, return success
                        if (remainingCount <= 0) {
                            return true;
                        }
                    }
                }
            }
        }

        // If there are remaining items or AE2 insertion failed, try to insert into the output inventory
        if (remainingCount > 0) {
            ItemStack remainingStack = stack.copy();
            remainingStack.setCount((int)remainingCount);

            ItemStack notInserted = ItemHandlerHelper.insertItem(this.output, remainingStack, false);

            // If insertion was completely successful
            if (notInserted.isEmpty()) {
                // Notify the change
                this.setChanged();
                return true;
            }

            // If we inserted some items but not all
            if (notInserted.getCount() < remainingCount) {
                this.setChanged();
                return insertedIntoAE2 || (notInserted.getCount() < stack.getCount());
            }

            // If we couldn't insert any items and didn't insert into AE2 either
            return insertedIntoAE2;
        }

        return true;
    }

    /**
     * Handles a completed task from the Replication network
     * @param task The completed task
     * @param stack The produced item stack
     */
    public void handleCompletedTask(IReplicationTask task, ItemStack stack) {
        // Try to insert the item into the AE2 network or local inventory
        boolean inserted = receiveItemFromReplicator(stack);

        if (!inserted) {
            // Debug log disabled for production
            // LOGGER.debug("Bridge: Failed to insert item from completed task: {}", stack.getDisplayName().getString());
        }

        // Update request counters
        UUID sourceId = this.blockId;
        if (task.getSource().equals(this.worldPosition)) {
            // Decrement the counter for this pattern in source-specific requests
            Map<ItemStack, Integer> sourceRequests = patternRequestsBySource.getOrDefault(sourceId, new HashMap<>());
            int currentCount = sourceRequests.getOrDefault(stack, 0);
            if (currentCount > 0) {
                sourceRequests.put(stack, currentCount - 1);
                patternRequestsBySource.put(sourceId, sourceRequests);
            }

            // Also update the global counter for backward compatibility
            Map<ItemStack, Integer> globalRequests = patternRequests.getOrDefault(sourceId, new HashMap<>());
            int currentGlobalCount = globalRequests.getOrDefault(stack, 0);
            if (currentGlobalCount > 0) {
                globalRequests.put(stack, currentGlobalCount - 1);
                patternRequests.put(sourceId, globalRequests);
                //LOGGER.info("Bridge: Task completed for {}, remaining {} active requests",
                //    pattern.getItem().getDescriptionId(), currentGlobalCount - 1);
            }
        }
    }

    // =================== Utility methods ===================

    public static class TerminalPlayerTracker {
        private List<ServerPlayer> players;
        private List<UUID> uuidsToRemove;
        private List<ServerPlayer> playersToAdd;

        public TerminalPlayerTracker() {
            this.players = new ArrayList<>();
            this.uuidsToRemove = new ArrayList<>();
            this.playersToAdd = new ArrayList<>();
        }

        public void checkIfValid() {
            var output = new ArrayList<>(playersToAdd);
            var input = new ArrayList<>(players);
            for (ServerPlayer serverPlayer : input) {
                if (!this.uuidsToRemove.contains(serverPlayer.getUUID())) {
                    output.add(serverPlayer);
                }
            }
            this.players = output;
            this.uuidsToRemove = new ArrayList<>();
            this.playersToAdd = new ArrayList<>();
        }

        public void removePlayer(ServerPlayer serverPlayer) {
            this.uuidsToRemove.add(serverPlayer.getUUID());
        }

        public void addPlayer(ServerPlayer serverPlayer) {
            this.playersToAdd.add(serverPlayer);
        }

        public List<ServerPlayer> getPlayers() {
            return players;
        }
    }

    // Implementazione di ICraftingInventory
    @Override
    public void insert(AEKey what, long amount, Actionable mode) {
        if (mode == Actionable.MODULATE && what instanceof AEItemKey itemKey) {
            // When an item is inserted for crafting, check if it can be crafted with Replication
            MatterNetwork network = getNetwork();
            if (network != null) {
                // Check if the item can be crafted
                for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                    var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                    if (tile instanceof ChipStorageBlockEntity chipStorage) {
                        for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                            if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                // The item can be crafted, we can proceed
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode) {
        if (what instanceof AEItemKey) {
            AEItemKey itemKey = (AEItemKey) what;
            Item item = itemKey.getItem();
            if (isVirtualMatterItem(item)) {
                // For virtual matter, now allow extraction
                // the matterItemsStorage will handle the logic
                return matterItemsStorage.extract(what, amount, mode, null);
            }
        }
        MatterNetwork network = getNetwork();
        if (network != null && what instanceof AEItemKey) {
            AEItemKey itemKey = (AEItemKey) what;
            for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                if (tile instanceof ChipStorageBlockEntity chipStorage) {
                    for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                        if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                            // The item can be extracted
                            return amount;
                        }
                    }
                }
            }
        }
        return 0;
    }

    @Override
    public Iterable<AEKey> findFuzzyTemplates(AEKey input) {
        if (input instanceof AEItemKey itemKey) {
            MatterNetwork network = getNetwork();
            if (network != null) {
                List<AEKey> templates = new ArrayList<>();
                // Search for all items that can be crafted with Replication
                for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                    var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                    if (tile instanceof ChipStorageBlockEntity chipStorage) {
                        for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                            templates.add(AEItemKey.of(pattern.getStack().getItem()));
                        }
                    }
                }
                return templates;
            }
        }
        return List.of();
    }

    // Implementazione di ICraftingProvider
    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        // Don't show patterns if not initialized
        if (initialized != 1) {
            return List.of();
        }

        List<IPatternDetails> patterns = new ArrayList<>();
        MatterNetwork network = getNetwork();
        if (network != null) {
            // For each chip storage in the network
            for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                if (tile instanceof ChipStorageBlockEntity chipStorage) {
                    // For each pattern in the chip storage
                    for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                        if (!pattern.getStack().isEmpty() && pattern.getCompletion() == 1) {
                            try {
                                // Create an AE2 processing pattern
                                ItemStack patternStack = new ItemStack(AEItems.BLANK_PATTERN.asItem());
                                AEItemKey output = AEItemKey.of(pattern.getStack().getItem());

                                // Get the matter compound for this pattern
                                var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                if (matterCompound != null) {
                                    // Create the processing pattern with the actual matter requirements
                                    List<GenericStack> inputs = new ArrayList<>();

                                    // Add each type of matter required as input
                                    for (MatterValue matterValue : matterCompound.getValues().values()) {
                                        var matterType = matterValue.getMatter();
                                        var matterAmount = (long)Math.ceil(matterValue.getAmount());

                                        // Find the virtual item corresponding to the matter type
                                        Item matterItem = getItemForMatterType(matterType);
                                        if (matterItem != null) {
                                            // Add the matter as input
                                            inputs.add(new GenericStack(AEItemKey.of(matterItem), matterAmount));
                                            //LOGGER.info("Bridge: Pattern for {} requires {} of {} matter",
                                            //    pattern.getStack().getItem().getDescriptionId(),
                                            //    matterAmount,
                                            //    matterType.getName());
                                        }
                                    }

                                    List<GenericStack> outputs = new ArrayList<>();
                                    outputs.add(new GenericStack(output, 1)); // Output is the replicated item

                                    // Encode the pattern
                                    AEProcessingPattern.encode(patternStack, inputs, outputs);

                                    // Create the AE2 pattern
                                    AEProcessingPattern aePattern = new AEProcessingPattern(AEItemKey.of(patternStack));

                                    patterns.add(aePattern);
                                    //LOGGER.info("Bridge: Pattern added for {}", pattern.getStack().getItem().getDescriptionId());
                                }
                            } catch (Exception e) {
                                //LOGGER.error("Bridge: Error in pattern conversion: {}", e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        //LOGGER.info("Bridge: Total available patterns: {}", patterns.size());
        return patterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        // Don't accept patterns if not initialized
        if (initialized != 1) {
            return false;
        }

        MatterNetwork network = getNetwork();

        if (network != null && isActive() && patternDetails != null) {
            // Check if the pattern produces an item that can be replicated
            if (patternDetails.getOutputs().size() == 1) {
                var output = patternDetails.getOutputs().iterator().next();
                if (output.what() instanceof AEItemKey itemKey) {
                    //LOGGER.info("Bridge: Request to pushPattern for {}", itemKey.getItem().getDescriptionId());

                    // If we are busy, add the pattern to the queue
                    if (isBusy()) {
                        //LOGGER.info("Bridge: Bridge occupied, adding to queue");
                        pendingPatterns.add(patternDetails);
                        pendingInputs.put(patternDetails, inputHolder);
                        return true;
                    }

                    // Search for the pattern in all chip storage in the network
                    for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                        var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                        if (tile instanceof ChipStorageBlockEntity chipStorage) {
                            for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                                if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                    // Check if we have enough virtual matter in the inputs
                                    if (inputHolder != null && inputHolder.length > 0) {
                                        KeyCounter inputs = inputHolder[0];
                                        boolean hasAllMatter = true;

                                        // Get the matter compound for this pattern
                                        var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                        if (matterCompound != null) {
                                            // Check directly on the network if there is enough matter available
                                            // instead of checking the AE2 virtual inputs
                                            for (MatterValue matterValue : matterCompound.getValues().values()) {
                                                var matterType = matterValue.getMatter();
                                                var matterAmount = (long)Math.ceil(matterValue.getAmount());

                                                // Verify how much matter is available in the network
                                                long available = network.calculateMatterAmount(matterType);

                                                //LOGGER.info("Bridge: Verify availability for {}: {} {} (required: {}, available: {})",
                                                //    itemKey.getItem().getDescriptionId(),
                                                //    matterType.getName(),
                                                //    matterAmount,
                                                //    matterAmount,
                                                //    available);

                                                if (available < matterAmount) {
                                                    hasAllMatter = false;

                                                    // Create a unique key for this warning
                                                    String warningKey = itemKey.getItem().getDescriptionId() + ":" + matterType.getName();
                                                    long currentTime = level.getGameTime();

                                                    // Check if we have already shown this warning recently
                                                    if (!lastMatterWarnings.containsKey(warningKey) ||
                                                            currentTime - lastMatterWarnings.get(warningKey) > WARNING_COOLDOWN) {
                                                        // Show the warning and update the timestamp
                                                        //LOGGER.warn("Bridge: Matter {} insufficient for {}. Has: {}, Required: {}",
                                                        //    matterType.getName(),
                                                        //    itemKey.getItem().getDescriptionId(),
                                                        //    available,
                                                        //    matterAmount);
                                                        lastMatterWarnings.put(warningKey, currentTime);
                                                    }
                                                    break;
                                                }
                                            }

                                            // Extract each type of matter from the network if we have all
                                            if (hasAllMatter) {
                                                for (MatterValue matterValue : matterCompound.getValues().values()) {
                                                    var matterType = matterValue.getMatter();
                                                    var matterAmount = (long)Math.ceil(matterValue.getAmount());

                                                    // Find the virtual item corresponding to the matter type
                                                    Item matterItem = getItemForMatterType(matterType);
                                                    if (matterItem != null) {
                                                        AEItemKey matterKey = AEItemKey.of(matterItem);

                                                        // Note: now we extract the real matter for replication
                                                        //LOGGER.info("Bridge: Extraction of {} real matter {} for replication",
                                                        //    matterAmount, matterType.getName());

                                                        long extracted = extract(matterKey, matterAmount, Actionable.MODULATE);
                                                        //LOGGER.info("Bridge: Consumed virtual matter {}: {}", matterType.getName(), extracted);
                                                    }
                                                }
                                            } else {
                                                //LOGGER.warn("Bridge: Matter insufficient in the network to craft {}",
                                                //    itemKey.getItem().getDescriptionId());
                                                return false;
                                            }
                                        }
                                    }

                                    // Increment the counter for this item with source information
                                    ItemStack itemStack = pattern.getStack();
                                    ItemWithSourceId key = new ItemWithSourceId(itemStack, this.blockId);

                                    // Get or create the map for this source
                                    Map<ItemWithSourceId, Integer> sourceCounters = requestCounters.getOrDefault(this.blockId, new HashMap<>());
                                    int currentCount = sourceCounters.getOrDefault(key, 0);
                                    sourceCounters.put(key, currentCount + 1);
                                    requestCounters.put(this.blockId, sourceCounters);

                                    //LOGGER.info("Bridge: Pattern found for {}, total requests in the last 10 ticks: {}",
                                    //    itemKey.getItem().getDescriptionId(), currentCount + 1);

                                    // We don't create the task immediately, we wait for the next reset
                                    return true;
                                }
                            }
                        }
                    }
                    //LOGGER.warn("Bridge: Pattern not found in the Replication network");
                } else {
                    //LOGGER.warn("Bridge: No Replication network found");
                }
            }
        }
        return false;
    }

    @Override
    public boolean isBusy() {
        MatterNetwork network = getNetwork();
        if (network != null) {
            // Get all task IDs from the network
            Set<String> networkTaskIds = new HashSet<>(network.getTaskManager().getPendingTasks().keySet());

            // Check all sources
            for (UUID sourceId : activeTasks.keySet()) {
                Map<String, TaskSourceInfo> sourceTasks = activeTasks.get(sourceId);

                // Create a copy of the keys to avoid concurrent modification
                Set<String> taskIds = new HashSet<>(sourceTasks.keySet());

                for (String taskId : taskIds) {
                    // If the task is no longer in the network, it's completed
                    if (!networkTaskIds.contains(taskId)) {
                        // The task has been completed, remove it
                        TaskSourceInfo info = sourceTasks.remove(taskId);
                        if (info != null) {
                            ItemStack pattern = info.getItemStack();

                            // Decrement the counter for this pattern by source
                            Map<ItemStack, Integer> sourceRequests = patternRequestsBySource.getOrDefault(sourceId, new HashMap<>());
                            int currentCount = sourceRequests.getOrDefault(pattern, 0);
                            if (currentCount > 0) {
                                sourceRequests.put(pattern, currentCount - 1);
                                patternRequestsBySource.put(sourceId, sourceRequests);
                            }

                            // Also update the global counter for backward compatibility
                            Map<ItemStack, Integer> globalRequests = patternRequests.getOrDefault(sourceId, new HashMap<>());
                            int currentGlobalCount = globalRequests.getOrDefault(pattern, 0);
                            if (currentGlobalCount > 0) {
                                globalRequests.put(pattern, currentGlobalCount - 1);
                                patternRequests.put(sourceId, globalRequests);
                                //LOGGER.info("Bridge: Task completed for {}, remaining {} active requests",
                                //    pattern.getItem().getDescriptionId(), currentGlobalCount - 1);
                            }
                        }
                    }
                }

                // If this source has no more tasks, remove it from the map
                if (sourceTasks.isEmpty()) {
                    activeTasks.remove(sourceId);
                } else {
                    activeTasks.put(sourceId, sourceTasks);
                }
            }

            boolean busy = !network.getTaskManager().getPendingTasks().isEmpty();

            // If we are not busy but the updates are still blocked, unblock them
            if (!busy && requestCounters.isEmpty()) {
                // Remove this log message that no longer makes sense
                // LOGGER.info("Bridge: Updates of matter unlocked from isBusy");

                // Force an update of the storage to show the new quantities
                IStorageProvider.requestUpdate(mainNode);
            }

            /*if (busy) {
                LOGGER.info("Bridge: Occupied with {} pending tasks", network.getTaskManager().getPendingTasks().size());
                // Log delle richieste per pattern
                patternRequests.forEach((sourceId, patterns) ->
                    patterns.forEach((pattern, count) ->
                        LOGGER.info("Bridge: Pattern {} has {} active requests from source {}",
                            pattern.getItem().getDescriptionId(), count, sourceId)));
            }*/
            return busy;
        }
        return false;
    }

    @Override
    public int getPatternPriority() {
        // Priorità alta per assicurarci che i pattern di Replication vengano usati prima di altri
        return 100;
    }

    public Future<ICraftingPlan> beginCraftingCalculation(Level level,
                                                          ICraftingSimulationRequester simRequester,
                                                          AEKey what,
                                                          long amount,
                                                          CalculationStrategy strategy) {

        if (what instanceof AEItemKey itemKey) {
            //LOGGER.info("Bridge: Crafting calculation for {} x{}", itemKey.getItem().getDescriptionId(), amount);
            MatterNetwork network = getNetwork();
            if (network != null) {
                // Cerca il pattern corrispondente in Replication
                for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                    var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                    if (tile instanceof ChipStorageBlockEntity chipStorage) {
                        for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                            if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                // Verifica se c'è abbastanza matter per questa quantità
                                var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                if (matterCompound != null) {
                                    boolean hasEnoughMatter = true;
                                    Map<IMatterType, Long> missingMatter = new HashMap<>();

                                    // Calcola la matter necessaria e disponibile
                                    for (MatterValue matterValue : matterCompound.getValues().values()) {
                                        var matterType = matterValue.getMatter();
                                        var matterPerItem = matterValue.getAmount();
                                        long totalMatterNeeded = (long)(matterPerItem * amount);
                                        long available = network.calculateMatterAmount(matterType);

                                        //LOGGER.info("Bridge: Matter needed for {}: {} {} ({} per item)",
                                        //    itemKey.getItem().getDescriptionId(),
                                        //    totalMatterNeeded,
                                        //    matterType.toString(),
                                        //    matterPerItem);

                                        //LOGGER.info("Bridge: Matter available: {} {}",
                                        //    available,
                                        //    matterType.toString());

                                        if (available < totalMatterNeeded) {
                                            hasEnoughMatter = false;
                                            missingMatter.put(matterType, totalMatterNeeded - available);

                                            // Create a unique key for this warning
                                            String warningKey = itemKey.getItem().getDescriptionId() + ":" + matterType.getName();
                                            long currentTime = level.getGameTime();

                                            // Check if we have already shown this warning recently
                                            if (!lastMatterWarnings.containsKey(warningKey) ||
                                                    currentTime - lastMatterWarnings.get(warningKey) > WARNING_COOLDOWN) {
                                                //LOGGER.warn("Bridge: Matter {} insufficient for {}. Needed: {}, Available: {}",
                                                //    matterType.getName(),
                                                //    itemKey.getItem().getDescriptionId(),
                                                //    totalMatterNeeded,
                                                //    available);
                                                lastMatterWarnings.put(warningKey, currentTime);
                                            }
                                        }
                                    }

                                    if (!hasEnoughMatter) {
                                        StringBuilder errorMsg = new StringBuilder();
                                        errorMsg.append("Not enough matter available. Missing:\n");
                                        for (Map.Entry<IMatterType, Long> entry : missingMatter.entrySet()) {
                                            errorMsg.append("- ")
                                                    .append(entry.getValue())
                                                    .append(" ")
                                                    .append(entry.getKey().toString())
                                                    .append("\n");
                                        }

                                        //LOGGER.warn("Bridge: Request of {} x{} rejected due to lack of matter",
                                        //    itemKey.getItem().getDescriptionId(), amount);
                                        //LOGGER.warn("Bridge: Missing matter:\n{}", errorMsg);

                                        return CompletableFuture.failedFuture(
                                                new IllegalStateException(errorMsg.toString()));
                                    }

                                    //LOGGER.info("Bridge: Matter sufficient for crafting {} x{}",
                                    //    itemKey.getItem().getDescriptionId(), amount);

                                    // Se c'è abbastanza matter, crea un piano di crafting
                                    return CompletableFuture.completedFuture(new ICraftingPlan() {
                                        @Override
                                        public GenericStack finalOutput() {
                                            return new GenericStack(AEItemKey.of(itemKey.getItem()), amount);
                                        }

                                        @Override
                                        public long bytes() {
                                            return 0; // We don't use bytes
                                        }

                                        @Override
                                        public boolean simulation() {
                                            return false; // It's not a simulation
                                        }

                                        @Override
                                        public boolean multiplePaths() {
                                            return false; // There are no multiple paths
                                        }

                                        @Override
                                        public KeyCounter usedItems() {
                                            return new KeyCounter(); // We don't use items
                                        }

                                        @Override
                                        public KeyCounter emittedItems() {
                                            return new KeyCounter(); // We don't emit items
                                        }

                                        @Override
                                        public KeyCounter missingItems() {
                                            return new KeyCounter(); // There are no missing items
                                        }

                                        @Override
                                        public Map<IPatternDetails, Long> patternTimes() {
                                            return Map.of(); // We don't use patterns
                                        }
                                    });
                                } else {
                                    //LOGGER.error("Bridge: Impossible to calculate the required matter for {}",
                                    //    itemKey.getItem().getDescriptionId());
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException("Cannot calculate required matter"));
                                }
                            }
                        }
                    }
                }
                //LOGGER.warn("Bridge: No pattern found for {}", itemKey.getItem().getDescriptionId());
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No pattern found for this item"));
            } else {
                //LOGGER.error("Bridge: No Replication network found");
                return CompletableFuture.failedFuture(
                        new IllegalStateException("No Replication network found"));
            }
        }

        // If we get here, we can't craft this item
        //LOGGER.error("Bridge: Attempt to craft an invalid item");
        return CompletableFuture.failedFuture(
                new IllegalStateException("Cannot craft this item"));
    }

    // Map IMatterType to virtual item
    private Item getItemForMatterType(IMatterType type) {
        String name = type.getName();
        if (name.equalsIgnoreCase("earth")) return ModItems.EARTH_MATTER.get();
        if (name.equalsIgnoreCase("nether")) return ModItems.NETHER_MATTER.get();
        if (name.equalsIgnoreCase("organic")) return ModItems.ORGANIC_MATTER.get();
        if (name.equalsIgnoreCase("ender")) return ModItems.ENDER_MATTER.get();
        if (name.equalsIgnoreCase("metallic")) return ModItems.METALLIC_MATTER.get();
        if (name.equalsIgnoreCase("precious")) return ModItems.PRECIOUS_MATTER.get();
        if (name.equalsIgnoreCase("living")) return ModItems.LIVING_MATTER.get();
        if (name.equalsIgnoreCase("quantum")) return ModItems.QUANTUM_MATTER.get();
        return null;
    }

    // Utility to recognize virtual matter items
    private boolean isVirtualMatterItem(Item item) {
        return item == ModItems.EARTH_MATTER.get()
                || item == ModItems.NETHER_MATTER.get()
                || item == ModItems.ORGANIC_MATTER.get()
                || item == ModItems.ENDER_MATTER.get()
                || item == ModItems.METALLIC_MATTER.get()
                || item == ModItems.PRECIOUS_MATTER.get()
                || item == ModItems.LIVING_MATTER.get()
                || item == ModItems.QUANTUM_MATTER.get();
    }

    // Method to show virtual items in the AE2 terminal
    public void getAvailableItems(KeyCounter items) {
        // Don't show items if not initialized
        if (initialized != 1) {
            return;
        }

        //LOGGER.info("Bridge: Called getAvailableItems");
        MatterNetwork network = getNetwork();
        if (network != null) {
            // Recupera tutti i tipi di matter registrati
            List<IMatterType> matterTypes = List.of(
                    ReplicationRegistry.Matter.EMPTY.get(),
                    ReplicationRegistry.Matter.METALLIC.get(),
                    ReplicationRegistry.Matter.EARTH.get(),
                    ReplicationRegistry.Matter.NETHER.get(),
                    ReplicationRegistry.Matter.ORGANIC.get(),
                    ReplicationRegistry.Matter.ENDER.get(),
                    ReplicationRegistry.Matter.PRECIOUS.get(),
                    ReplicationRegistry.Matter.QUANTUM.get(),
                    ReplicationRegistry.Matter.LIVING.get()
            );

            for (IMatterType matterType : matterTypes) {
                long amount = network.calculateMatterAmount(matterType);
                //LOGGER.info("Bridge: " + matterType.getName() + " -> " + amount);
                if (amount > 0) {
                    Item item = getItemForMatterType(matterType);
                    if (item != null) {
                        //LOGGER.info("Bridge: Adding virtual item " + item + " quantity " + amount);
                        items.add(AEItemKey.of(item), amount);
                    } else {
                        //LOGGER.info("Bridge: No item associated with " + matterType.getName());
                    }
                }
            }
        } else {
            //LOGGER.info("Bridge: No Replication network found");
        }
    }

    @Override
    public void mountInventories(IStorageMounts storageMounts) {
        // Don't mount storage if not initialized
        if (initialized != 1) {
            return;
        }

        // Mount the virtual storage with high priority to be always visible
        storageMounts.mount(matterItemsStorage, 100);
    }

    /**
     * Method called when the entity is saved or loaded from/to disk
     */
    public class MatterItemsStorage implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            // Don't allow insertions if not initialized
            if (initialized != 1) {
                return 0;
            }

            // We don't allow insertions in this storage
            return 0;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            // Don't allow extractions if not initialized
            if (initialized != 1) {
                return 0;
            }

            // We allow extraction, but only for autocrafting operations
            if (what instanceof AEItemKey itemKey && isVirtualMatterItem(itemKey.getItem())) {
                // Get the network
                MatterNetwork network = getNetwork();
                if (network != null) {
                    // Find the corresponding matter type
                    IMatterType matterType = getMatterTypeForItem(itemKey.getItem());
                    if (matterType != null) {
                        // Check how much matter is available in the network
                        long available = network.calculateMatterAmount(matterType);

                        // Log for debugging availability
                        if (available < amount) {
                            //LOGGER.info("Bridge: Insufficient availability of {} - Requested: {}, Available: {}",
                            //    matterType.getName(), amount, available);
                        }

                        // Decide how much to extract (the minimum between the request and the available)
                        long toExtract = Math.min(amount, available);

                        // Se è una simulazione, restituisci solo la quantità che potremmo estrarre
                        if (mode == Actionable.SIMULATE) {
                            //LOGGER.debug("Bridge: Simulation extraction of {} {}", toExtract, matterType.getName());
                            return toExtract;
                        }

                        // Check if the source is an export bus or automatic interface (which we want to block)
                        /*if (source != null && isAutomationPart(source)) {
                            // Block requests from export bus
                            //LOGGER.warn("Bridge: Block extraction from export bus of {} {}",
                            //    amount, matterType.getName());
                            return 0;
                        }*/

                        // Allow all other operations
                        if (toExtract > 0) {
                            //LOGGER.info("Bridge: Virtual extraction of {} {}",
                            //    toExtract, matterType.getName());
                            return toExtract;
                        }
                    }
                }
            }
            return 0;
        }

        /**
         * Check if the source is an automation part (export bus, automatic interface, etc.)
         * @param source The source of the request
         * @return true if it is an automation part that we want to block
         */
        public boolean isAutomationPart(IActionSource source) {
            // If there is a machine, check if it is an automation part
            if (source.machine().isPresent()) {
                var machine = source.machine().get();
                String machineClass = machine.getClass().getName();

                // Block specifically automation parts
                return machineClass.contains("appeng.parts.autom");
            }

            // It's not an export bus
            return false;
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            return net.minecraft.network.chat.Component.literal("Replication Matter Storage");
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            // Don't show stacks if not initialized
            if (initialized != 1) {
                return;
            }

            MatterNetwork network = getNetwork();
            if (network != null) {
                // Get all registered matter types
                List<IMatterType> matterTypes = List.of(
                        ReplicationRegistry.Matter.EMPTY.get(),
                        ReplicationRegistry.Matter.METALLIC.get(),
                        ReplicationRegistry.Matter.EARTH.get(),
                        ReplicationRegistry.Matter.NETHER.get(),
                        ReplicationRegistry.Matter.ORGANIC.get(),
                        ReplicationRegistry.Matter.ENDER.get(),
                        ReplicationRegistry.Matter.PRECIOUS.get(),
                        ReplicationRegistry.Matter.QUANTUM.get(),
                        ReplicationRegistry.Matter.LIVING.get()
                );

                for (IMatterType matterType : matterTypes) {
                    long amount = network.calculateMatterAmount(matterType);
                    //LOGGER.info("Bridge: Matter {} available: {}", matterType.getName(), amount);
                    if (amount > 0) {
                        Item item = getItemForMatterType(matterType);
                        if (item != null) {
                            //LOGGER.info("Bridge: Adding virtual item {} quantity {}", item, amount);
                            out.add(AEItemKey.of(item), amount);
                        } else {
                            //LOGGER.info("Bridge: No item associated with {}", matterType.getName());
                        }
                    }
                }
            } else {
                //LOGGER.warn("Bridge: No Replication network found");
            }
        }
    }

    // Instance of the virtual matter item storage
    private final MatterItemsStorage matterItemsStorage = new MatterItemsStorage();

    // Method to react to Replication network events
    public void handleReplicationNetworkEvent() {
        if (isActive() && level != null && !level.isClientSide()) {
            // Force a pattern update
            //LOGGER.info("Bridge: Pattern update in response to Replication event");
            ICraftingProvider.requestUpdate(mainNode);
        }
    }

    // Map virtual item -> IMatterType
    private IMatterType getMatterTypeForItem(Item item) {
        if (item == ModItems.EARTH_MATTER.get()) return ReplicationRegistry.Matter.EARTH.get();
        if (item == ModItems.NETHER_MATTER.get()) return ReplicationRegistry.Matter.NETHER.get();
        if (item == ModItems.ORGANIC_MATTER.get()) return ReplicationRegistry.Matter.ORGANIC.get();
        if (item == ModItems.ENDER_MATTER.get()) return ReplicationRegistry.Matter.ENDER.get();
        if (item == ModItems.METALLIC_MATTER.get()) return ReplicationRegistry.Matter.METALLIC.get();
        if (item == ModItems.PRECIOUS_MATTER.get()) return ReplicationRegistry.Matter.PRECIOUS.get();
        if (item == ModItems.LIVING_MATTER.get()) return ReplicationRegistry.Matter.LIVING.get();
        if (item == ModItems.QUANTUM_MATTER.get()) return ReplicationRegistry.Matter.QUANTUM.get();
        return null;
    }

    /**
     * Metodo statico per cancellare tutte le operazioni pendenti su tutti i bridge
     * durante la chiusura del server
     */
    public static void cancelAllPendingOperations() {
        LOGGER.info("RepAE2Bridge: Cancelling all pending operations on all bridges");
        // Questo metodo non ha implementazione perché lo stato worldUnloading
        // è già sufficiente per terminare le operazioni in corso.
        // Il metodo esiste per mantenere l'API coerente con il codice nel RepAE2Bridge.java
    }

    // Method to handle world unload event
    public void onWorldUnload() {
        // Don't reset the initialized variable when the world unloads
        // This prevents essenze from flickering when re-entering the world
        // if (initialized == 1) {
        //     initialized = 0;
        //    // LOGGER.info("Bridge: Unloading Bridge...");
        // }

        // Pulizia rapida delle operazioni in corso
        LOGGER.info("Bridge: Cleaning up during world unload");
        
        // Reset counters to prevent lingering operations
        requestCounterTicks = 0;
        patternUpdateTicks = 0;
        
        // Clear pending operations that might cause blocks
        pendingPatterns.clear();
        pendingInputs.clear();
        
        // Interrompiamo eventuali richieste in corso per evitare blocchi durante lo shutdown
        requestCounters.clear();
        
        // Instead of resetting initialized, maintain the state but do other cleanup operations
        // Debug log disabled for production
        // LOGGER.debug("Bridge: World unloading, maintaining initialization state");

        // Make sure the AE2 node is properly destroyed
        if (level != null && !level.isClientSide() && mainNode != null) {
            mainNode.destroy();
            nodeCreated = false;
            shouldReconnect = true; // Mark for reconnection when the world is reloaded
        }
    }

    /**
     * Get the unique identifier for this block
     * @return the UUID of this block
     */
    public UUID getBlockId() {
        return blockId;
    }

    /**
     * Get the number of active requests for this specific block
     * @return the total number of active requests for this block
     */
    public int getActiveRequestsForThisBlock() {
        Map<ItemStack, Integer> requests = patternRequestsBySource.getOrDefault(this.blockId, new HashMap<>());
        return requests.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Get the number of active requests for a specific item from this block
     * @param item the item to check
     * @return the number of active requests for the item from this block
     */
    public int getActiveRequestsForItem(Item item) {
        Map<ItemStack, Integer> requests = patternRequestsBySource.getOrDefault(this.blockId, new HashMap<>());
        return requests.entrySet().stream()
                .filter(entry -> entry.getKey().getItem() == item)
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    /**
     * Get the total number of active requests across all blocks
     * @return the total number of active requests
     */
    public int getTotalActiveRequests() {
        int total = 0;
        for (Map<ItemStack, Integer> sourceRequests : patternRequestsBySource.values()) {
            total += sourceRequests.values().stream().mapToInt(Integer::intValue).sum();
        }
        return total;
    }

    /**
     * Helper class to track an item with its source block ID
     */
    public static class ItemWithSourceId {
        private final ItemStack itemStack;
        private final UUID sourceId;

        public ItemWithSourceId(ItemStack itemStack, UUID sourceId) {
            this.itemStack = itemStack.copy(); // Create a copy to avoid reference issues
            this.sourceId = sourceId;
        }

        public ItemStack getItemStack() {
            return itemStack;
        }

        public UUID getSourceId() {
            return sourceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemWithSourceId that = (ItemWithSourceId) o;
            return ItemStack.matches(itemStack, that.itemStack) &&
                    Objects.equals(sourceId, that.sourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemStack.getItem(), sourceId);
        }
    }

    /**
     * Helper class to track task information including source block
     */
    public static class TaskSourceInfo {
        private final ItemStack itemStack;
        private final UUID sourceId;

        public TaskSourceInfo(ItemStack itemStack, UUID sourceId) {
            this.itemStack = itemStack.copy(); // Create a copy to avoid reference issues
            this.sourceId = sourceId;
        }

        public ItemStack getItemStack() {
            return itemStack;
        }

        public UUID getSourceId() {
            return sourceId;
        }
    }

    /**
     * Periodically tries to transfer items from the local inventory to the AE2 network
     * This ensures that items are moved to the AE2 system as soon as possible
     */
    private void transferItemsToAE2() {
        // Only proceed if we have an active AE2 connection
        if (!mainNode.isActive() || mainNode.getNode() == null) {
            return;
        }

        IGrid grid = mainNode.getNode().getGrid();
        if (grid == null) {
            return;
        }

        IStorageService storageService = grid.getStorageService();
        if (storageService == null) {
            return;
        }

        // Check each slot in the output inventory
        boolean itemsMoved = false;

        for (int i = 0; i < output.getSlots(); i++) {
            ItemStack stack = output.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }

            // Try to insert the item into the AE2 storage
            AEItemKey key = AEItemKey.of(stack);
            long inserted = storageService.getInventory().insert(key, stack.getCount(), Actionable.MODULATE, new MachineSource(this));

            if (inserted > 0) {
                // Debug log disabled for production
                // LOGGER.debug("Bridge: Transferred {} items from local inventory to AE2", inserted);

                // Remove the inserted items from the output inventory
                stack.shrink((int)inserted);
                itemsMoved = true;
            }
        }

        // If we moved any items, mark the block as changed
        if (itemsMoved) {
            this.setChanged();
        }
    }
}