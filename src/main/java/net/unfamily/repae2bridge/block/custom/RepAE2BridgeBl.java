package net.unfamily.repae2bridge.block.custom;

import com.mojang.serialization.MapCodec;
import net.unfamily.repae2bridge.block.entity.RepAE2BridgeBlockEntity;
import net.unfamily.repae2bridge.block.entity.ModBlockEntities;
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

public class RepAE2BridgeBl extends BasicTileBlock<RepAE2BridgeBlockEntity> implements INetworkDirectionalConnection {
    public static final VoxelShape SHAPE = box(0, 0, 0, 16, 16, 16);
    public static final MapCodec<RepAE2BridgeBl> CODEC = simpleCodec(RepAE2BridgeBl::new);

    public RepAE2BridgeBl(Properties properties) {
        super(properties, RepAE2BridgeBlockEntity.class);
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
        
        // Forza un aggiornamento ai blocchi vicini quando il bridge viene piazzato
        if (!level.isClientSide()) {
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = pos.relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                level.neighborChanged(neighborPos, state.getBlock(), pos);
            }
            
            // Forza un aggiornamento del blocco stesso
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }
    
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block blockIn, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, blockIn, fromPos, isMoving);
        
        // Se un blocco vicino cambia, informiamo la BlockEntity
        if (!level.isClientSide()) {
            // Notifica la BlockEntity dell'aggiornamento
            RepAE2BridgeBlockEntity blockEntity = (RepAE2BridgeBlockEntity) level.getBlockEntity(pos);
            if (blockEntity != null) {
                // Utilizziamo il nuovo metodo handleNeighborChanged
                blockEntity.handleNeighborChanged(fromPos);
            }
        }
    }
    
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        // Se il blocco è stato rimosso o sostituito
        if (!state.is(newState.getBlock())) {
            // Assicurati che la BlockEntity venga rimossa correttamente
            level.removeBlockEntity(pos);
        }
        
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public boolean canConnect(Level level, BlockPos pos, BlockState state, Direction direction) {
        // Permettiamo la connessione da tutte le direzioni per la rete di Replication
        return true;
    }
}
