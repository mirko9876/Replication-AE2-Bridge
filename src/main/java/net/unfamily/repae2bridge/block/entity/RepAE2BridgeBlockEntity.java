package net.unfamily.repae2bridge.block.entity;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
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
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.api.util.AECableType;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ICraftingSimulationState;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.MEStorage;
import com.buuz135.replication.api.network.IMatterTanksConsumer;
import com.buuz135.replication.api.network.IMatterTanksSupplier;
import com.buuz135.replication.block.tile.ReplicationMachine;
import com.buuz135.replication.block.tile.ReplicatorBlockEntity;
import com.buuz135.replication.calculation.ReplicationCalculation;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.repae2bridge.block.ModBlocks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.buuz135.replication.api.pattern.IMatterPatternHolder;
import com.buuz135.replication.api.pattern.MatterPattern;
import com.buuz135.replication.block.tile.ChipStorageBlockEntity;
import com.buuz135.replication.block.tile.NetworkBlockEntity;
import com.buuz135.replication.api.task.IReplicationTask;
import com.buuz135.replication.api.task.ReplicationTask;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import appeng.core.definitions.AEItems;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.unfamily.repae2bridge.item.ModItems;
import com.buuz135.replication.ReplicationRegistry;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.Queue;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.lang.StringBuilder;

/**
 * BlockEntity for the RepAE2Bridge that connects the AE2 network with the Replication matter network
 */
public class RepAE2BridgeBlockEntity extends ReplicationMachine<RepAE2BridgeBlockEntity> 
        implements IInWorldGridNodeHost, ICraftingInventory, ICraftingProvider, IStorageProvider {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Constant for the number of ticks before processing accumulated requests
    private static final int REQUEST_ACCUMULATION_TICKS = 100;
    
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

    // Map to track requests for patterns
    private final Map<ItemStack, Integer> patternRequests = new HashMap<>();
    // Map to track active tasks
    private final Map<String, ItemStack> activeTasks = new HashMap<>();
    // Temporary counters for crafting requests
    private final Map<ItemStack, Integer> requestCounters = new HashMap<>();
    private int requestCounterTicks = 0;

    // Timer for periodic pattern updates
    private int patternUpdateTicks = 0;
    private static final int PATTERN_UPDATE_INTERVAL = 100; // Update every 5 seconds (100 ticks)
    

    // Cache of matter insufficient warnings to avoid repetitions
    private Map<String, Long> lastMatterWarnings = new HashMap<>();
    // Minimum time between consecutive warnings for the same item (in ticks)
    private static final int WARNING_COOLDOWN = 600; // 30 seconds

    public RepAE2BridgeBlockEntity(BlockPos pos, BlockState blockState) {
        super((BasicTileBlock<RepAE2BridgeBlockEntity>) ModBlocks.REPAE2BRIDGE.get(), 
              ModBlockEntities.REPAE2BRIDGE_BE.get(), 
              pos, 
              blockState);
              
        // Initialize terminal component
        this.terminalPlayerTracker = new TerminalPlayerTracker();
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
        // Create a custom network element that does not connect to other bridges
        // LOGGER.info("Bridge: Creating network element at {}", pos);
        return new DefaultMatterNetworkElement(level, pos) {
            @Override
            public boolean canConnectFrom(Direction direction) {
                // Check if there is another bridge in the specified direction
                BlockPos neighborPos = pos.relative(direction);
                if (level.getBlockEntity(neighborPos) instanceof RepAE2BridgeBlockEntity) {
                    // LOGGER.info("Bridge: Avoided connection with another bridge at {}", neighborPos);
                    return false;
                }
                // Otherwise use default behavior
                return super.canConnectFrom(direction);
            }
        };
    }

    /**
     * Called when the BlockEntity is loaded or after placement
     */
    @Override
    public void onLoad() {
        // First initialize the Replication network (as done by the base class)
        super.onLoad();
        // LOGGER.info("Bridge: onLoad called at {}", worldPosition);
        
        // Initialize the AE2 node if it hasn't been done
        if (!nodeCreated && level != null && !level.isClientSide()) {
            // LOGGER.info("Bridge: Initializing AE2 node");
            mainNode.create(level, worldPosition);
            nodeCreated = true;
            
            // Notify adjacent blocks
            forceNeighborUpdates();
            
            // Update the connection state visually
            updateConnectedState();

            // Force a pattern update
            // LOGGER.info("Bridge: Requesting AE2 pattern update");
            ICraftingProvider.requestUpdate(mainNode);
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
     * Handles block changes
     * Called from the RepAE2BridgeBl block
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
                // LOGGER.info("Bridge: Ignored update from another bridge at {}", fromPos);
                return;
            }
            
            // Check if there is an AE2 controller in the network
            boolean hasAE2Connection = hasAE2NetworkConnection();
            
            // If there is an AE2 connection and the node is not created, initialize the node
            if (hasAE2Connection && !nodeCreated) {
                // LOGGER.info("Bridge: Initializing AE2 node from handleNeighborChanged");
                mainNode.create(level, worldPosition);
                nodeCreated = true;
                
                // Notify adjacent blocks
                forceNeighborUpdates();
                
                // Update the connection state visually
                updateConnectedState();

                // Force a pattern update
                // LOGGER.info("Bridge: Requesting AE2 pattern update");
                ICraftingProvider.requestUpdate(mainNode);
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
     * Handles server tick for synchronizing the two networks
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state, RepAE2BridgeBlockEntity blockEntity) {
        super.serverTick(level, pos, state, blockEntity);
        
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
                for (int count : requestCounters.values()) {
                    totalItems += count;
                }
                
                // For each item with pending requests
                for (Map.Entry<ItemStack, Integer> entry : requestCounters.entrySet()) {
                    ItemStack itemStack = entry.getKey();
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
                                            chipStorage.getBlockPos()
                                        );
                                        
                                        // Add the task to the network
                                        String taskId = task.getUuid().toString();
                                        network.getTaskManager().getPendingTasks().put(taskId, task);
                                        
                                        // Add to the active tasks map
                                        activeTasks.put(taskId, itemStack);
                                        
                                        // Update the request counter for the pattern
                                        int currentPatternRequests = patternRequests.getOrDefault(itemStack, 0);
                                        patternRequests.put(itemStack, currentPatternRequests + count);
                                        
                                        // LOGGER.info("Bridge: Task created with ID {}, total requests for this pattern: {}", 
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
                // LOGGER.info("Bridge: Attempting to reconnect to the AE2 node");
                // If the node is not active and we have the shouldReconnect flag, try to reconnect
                if (mainNode.getNode() == null && !nodeCreated) {
                    // LOGGER.info("Bridge: Initializing AE2 node from serverTick");
                    mainNode.create(level, worldPosition);
                    nodeCreated = true;
                    
                    // Notify adjacent blocks
                    forceNeighborUpdates();
                    
                    // Update the connection state visually
                    updateConnectedState();

                    // Force an update of the available patterns
                    // LOGGER.info("Bridge: Requesting AE2 pattern update");
                    ICraftingProvider.requestUpdate(mainNode);
                } else {
                    forceNeighborUpdates();
                }
                shouldReconnect = false;
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
                // LOGGER.warn("Bridge: NetworkManager not found");
                return null;
            }
            
            NetworkElement element = networkManager.getElement(worldPosition);
            if (element == null) {
                // The element does not exist, try to create it
                // LOGGER.info("Bridge: Network element not found, creation in progress");
                element = createElement(level, worldPosition);
                networkManager.addElement(element);
                // LOGGER.info("Bridge: Network element created and added");
                
                // Force an update of the adjacent blocks
                forceNeighborUpdates();
            }
            
            if (element.getNetwork() instanceof MatterNetwork matterNetwork) {
                // Check if the network exists but does not contain the element
                var elements = matterNetwork.getMatterStacksHolders();
                if (elements != null && !elements.contains(element)) {
                    // LOGGER.info("Bridge: Adding element manually to the network");
                    matterNetwork.addElement(element);
                }
                return matterNetwork;
            } else {
                // LOGGER.warn("Bridge: Network is not a MatterNetwork: {}", 
                //    (element.getNetwork() != null ? element.getNetwork().getClass().getName() : "null"));
                return null;
            }  
        } catch (Exception e) {
            // LOGGER.error("Bridge: Error accessing the Replication network: {}", e.getMessage());
            // e.printStackTrace();
            return null;
        }
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
        
        // Schedule the AE2 node initialization
        if (level != null && !level.isClientSide()) {
            // Use GridHelper.onFirstTick to schedule the initialization
            GridHelper.onFirstTick(this, blockEntity -> {
                // If the reconnect flag is active or the node is not yet created, initialize
                if (shouldReconnect || !nodeCreated) {
                    // LOGGER.info("Bridge: Initializing AE2 node from clearRemoved");
                    mainNode.create(level, worldPosition);
                    nodeCreated = true;
                    
                    // Notify adjacent blocks
                    forceNeighborUpdates();
                    
                    // Update the visual connection state
                    updateConnectedState();

                    // Force an update of available patterns
                    // LOGGER.info("Bridge: Requesting AE2 pattern update");
                    ICraftingProvider.requestUpdate(mainNode);
                    
                    shouldReconnect = false;
                }
            });
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
        // Return null to avoid errors
        return null;
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
                                    
                                    // Increment the counter for this item
                                    ItemStack itemStack = pattern.getStack();
                                    int currentCount = requestCounters.getOrDefault(itemStack, 0);
                                    requestCounters.put(itemStack, currentCount + 1);
                                    
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
            // Remove completed tasks from the map
            network.getTaskManager().getPendingTasks().keySet().forEach(taskId -> {
                if (!activeTasks.containsKey(taskId)) {
                    // The task has been completed, remove it
                    ItemStack pattern = activeTasks.remove(taskId);
                    if (pattern != null) {
                        // Decrement the counter for this pattern
                        int currentCount = patternRequests.getOrDefault(pattern, 0);
                        if (currentCount > 0) {
                            patternRequests.put(pattern, currentCount - 1);
                            //LOGGER.info("Bridge: Task completed for {}, remaining {} active requests", 
                            //    pattern.getItem().getDescriptionId(), currentCount - 1);
                        }
                    }
                }
            });

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
                patternRequests.forEach((pattern, count) -> 
                    LOGGER.info("Bridge: Pattern {} has {} active requests", 
                       pattern.getItem().getDescriptionId(), count));
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
        // Mount the virtual storage with high priority to be always visible
        storageMounts.mount(matterItemsStorage, 100);
    }

    /**
     * Method called when the entity is saved or loaded from/to disk
     */
    private class MatterItemsStorage implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            // We don't allow insertions in this storage
            return 0;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
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
                        if (source != null && isAutomationPart(source)) {
                            // Block requests from export bus
                            //LOGGER.warn("Bridge: Block extraction from export bus of {} {}", 
                            //    amount, matterType.getName());
                            return 0;
                        }
                        
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
        private boolean isAutomationPart(IActionSource source) {
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
}