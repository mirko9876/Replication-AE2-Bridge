package net.unfamily.repae2bridge.block.custom;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.unfamily.repae2bridge.block.entity.RepAE2BridgeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.hrznstudio.titanium.block.BasicTileBlock;
import com.hrznstudio.titanium.block_network.INetworkDirectionalConnection;
import com.buuz135.replication.block.MatterPipeBlock;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Containers;
import org.slf4j.Logger;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.Explosion;


public class RepAE2BridgeBl extends BasicTileBlock<RepAE2BridgeBlockEntity> implements INetworkDirectionalConnection {
    public static final VoxelShape SHAPE = box(0, 0, 0, 16, 16, 16);
    public static final MapCodec<RepAE2BridgeBl> CODEC = simpleCodec(RepAE2BridgeBl::new);

    private static final Logger LOGGER = LogUtils.getLogger();

    // Add a property to display the connection status
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    public RepAE2BridgeBl(Properties properties) {
        super(properties, RepAE2BridgeBlockEntity.class);
        // Set the default state with connection to false
        registerDefaultState(this.getStateDefinition().any().setValue(CONNECTED, false));
        
        // Register the block as connectable with Replication pipes
        // (Added in case the static module in ModBlocks is not executed in time)
        registerWithReplicationMod();
    }
    
    /**
     * Register this block as connectable by Replication pipes
     */
    private void registerWithReplicationMod() {
        try {
            // Check if the MatterPipeBlock class already exists
            if (MatterPipeBlock.ALLOWED_CONNECTION_BLOCKS != null) {
                // Add a predicate for this specific block
                MatterPipeBlock.ALLOWED_CONNECTION_BLOCKS.add(block -> block instanceof RepAE2BridgeBl);
                // Debug log disabled for production
                // LOGGER.debug("Registered RepAE2BridgeBl with Replication mod pipes");
            }
        } catch (Exception e) {
            // Keep error logs for critical failures
            LOGGER.error("Failed to register block with Replication: " + e.getMessage());
        }
    }
    
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CONNECTED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected MapCodec<? extends BasicTileBlock<RepAE2BridgeBlockEntity>> codec() {
        return CODEC;
    }

    /* BLOCK ENTITY */

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntityType.BlockEntitySupplier<?> getTileEntityFactory() {
        return (pos, state) -> new RepAE2BridgeBlockEntity(pos, state);
    }
    
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @org.jetbrains.annotations.Nullable net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, entity, stack);
        
        // Force an update to neighboring blocks when the bridge is placed
        if (!level.isClientSide()) {
            // Initialize the BlockEntity immediately
            if (level.getBlockEntity(pos) instanceof RepAE2BridgeBlockEntity blockEntity) {
                // Initialize the AE2 node
                blockEntity.handleNeighborChanged(pos);
            }
            
            // Notify neighboring blocks
            updateNeighbors(level, pos, state);
        }
    }
    
    /**
     * Useful method to update neighboring blocks
     */
    private void updateNeighbors(Level level, BlockPos pos, BlockState state) {
        // Update the block itself
        level.sendBlockUpdated(pos, state, state, 3);
        
        // Update all adjacent blocks to ensure they detect the connection
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            
            // Notify the neighboring block of the change
            level.neighborChanged(neighborPos, state.getBlock(), pos);
            
            // If the neighboring block is a Replication network pipe, force it to update
            if (neighborState.getBlock() instanceof MatterPipeBlock) {
                // Update the visual state of the pipe
                level.sendBlockUpdated(neighborPos, neighborState, neighborState, 3);
                
                // Force a more aggressive recalculation of the connection
                if (neighborState.hasProperty(MatterPipeBlock.DIRECTIONS.get(direction.getOpposite()))) {
                    // This forces a recalculation both via block event and via state change
                    level.setBlock(neighborPos, neighborState.setValue(
                            MatterPipeBlock.DIRECTIONS.get(direction.getOpposite()), 
                            true), 3);
                }
            }
        }
    }
    
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block blockIn, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, blockIn, fromPos, isMoving);
        
        // If a neighboring block changes, inform the BlockEntity
        if (!level.isClientSide()) {
            // Check if the adjacent block is a Replication pipe
            Direction directionToPipe = null;
            for (Direction direction : Direction.values()) {
                if (pos.relative(direction).equals(fromPos) && 
                    level.getBlockState(fromPos).getBlock() instanceof MatterPipeBlock) {
                    directionToPipe = direction;
                    break;
                }
            }
            
            // Notify the BlockEntity of the update
            if (level.getBlockEntity(pos) instanceof RepAE2BridgeBlockEntity blockEntity) {
                // Use the handleNeighborChanged method
                blockEntity.handleNeighborChanged(fromPos);
                
                // Update the visual state of the block based on connections
                boolean isConnected = blockEntity.isActive() && blockEntity.getNetwork() != null;
                if (state.getValue(CONNECTED) != isConnected) {
                    level.setBlock(pos, state.setValue(CONNECTED, isConnected), 3);
                }
                
                // If an adjacent pipe was found, force the pipe to update
                if (directionToPipe != null) {
                    BlockState pipeState = level.getBlockState(fromPos);
                    if (pipeState.getBlock() instanceof MatterPipeBlock) {
                        level.sendBlockUpdated(fromPos, pipeState, pipeState, 3);
                    }
                }
            }
        }
    }
    
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        // If the block has been removed or replaced
        if (!state.is(newState.getBlock())) {
            // Drop the block itself if not in creative mode
            if (!level.isClientSide) {
                ItemStack itemStack = new ItemStack(this);
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), itemStack);
            }
            
            // Make sure the BlockEntity is properly removed
            if (level.getBlockEntity(pos) instanceof RepAE2BridgeBlockEntity blockEntity) {
                // Drop all items in the inventory
                var inventory = blockEntity.getOutput();
                for (int i = 0; i < inventory.getSlots(); i++) {
                    ItemStack stack = inventory.getStackInSlot(i);
                    if (!stack.isEmpty()) {
                        Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                    }
                }
                
                // Force explicit disconnection from both networks
                blockEntity.disconnectFromNetworks();
            }
            level.removeBlockEntity(pos);
        }
        
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public boolean canConnect(Level level, BlockPos pos, BlockState state, Direction direction) {
        // Allow connection from all directions for the Replication network
        return true;
    }

    // Ensure the block can always be harvested with any tool
    @Override
    public boolean canHarvestBlock(BlockState state, BlockGetter level, BlockPos pos, Player player) {
        return true;
    }
}
