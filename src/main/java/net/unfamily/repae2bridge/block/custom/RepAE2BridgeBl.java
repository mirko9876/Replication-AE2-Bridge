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
import com.buuz135.replication.block.MatterPipeBlock;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Block;
import com.buuz135.replication.Replication;

public class RepAE2BridgeBl extends BasicTileBlock<RepAE2BridgeBlockEntity> implements INetworkDirectionalConnection {
    public static final VoxelShape SHAPE = box(0, 0, 0, 16, 16, 16);
    public static final MapCodec<RepAE2BridgeBl> CODEC = simpleCodec(RepAE2BridgeBl::new);
    
    // Aggiungiamo una proprietà per visualizzare lo stato di connessione
    public static final BooleanProperty CONNECTED = BooleanProperty.create("connected");

    public RepAE2BridgeBl(Properties properties) {
        super(properties, RepAE2BridgeBlockEntity.class);
        // Imposta lo stato predefinito con connessione a false
        registerDefaultState(this.getStateDefinition().any().setValue(CONNECTED, false));
        
        // Registra il blocco come connettibile con i tubi Replication
        // (Aggiunto in caso il modulo statico in ModBlocks non venga eseguito in tempo)
        registerWithReplicationMod();
    }
    
    /**
     * Registra questo blocco come connettibile dai tubi Replication
     */
    private void registerWithReplicationMod() {
        try {
            // Verifica se la classe MatterPipeBlock esiste già
            if (MatterPipeBlock.ALLOWED_CONNECTION_BLOCKS != null) {
                // Aggiungi un predicate per questo blocco specifico
                MatterPipeBlock.ALLOWED_CONNECTION_BLOCKS.add(block -> block instanceof RepAE2BridgeBl);
                System.out.println("Registered RepAE2BridgeBl with Replication mod pipes");
            }
        } catch (Exception e) {
            System.err.println("Failed to register block with Replication: " + e.getMessage());
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
        
        // Forza un aggiornamento ai blocchi vicini quando il bridge viene piazzato
        if (!level.isClientSide()) {
            // Inizializza immediatamente la BlockEntity
            if (level.getBlockEntity(pos) instanceof RepAE2BridgeBlockEntity blockEntity) {
                blockEntity.onReady();
            }
            
            // Notifica i blocchi vicini
            updateNeighbors(level, pos, state);
        }
    }
    
    /**
     * Metodo utile per aggiornare i blocchi vicini
     */
    private void updateNeighbors(Level level, BlockPos pos, BlockState state) {
        // Aggiorna il blocco stesso
        level.sendBlockUpdated(pos, state, state, 3);
        
        // Aggiorna tutti i blocchi adiacenti per garantire che rilevino la connessione
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            
            // Notifica il blocco vicino del cambiamento
            level.neighborChanged(neighborPos, state.getBlock(), pos);
            
            // Se il blocco vicino è un tubo della rete di Replication, forzalo ad aggiornarsi
            if (neighborState.getBlock() instanceof MatterPipeBlock) {
                // Aggiorna lo stato visivo del tubo
                level.sendBlockUpdated(neighborPos, neighborState, neighborState, 3);
                
                // Forza un ricalcolo più aggressivo della connessione
                if (neighborState.hasProperty(MatterPipeBlock.DIRECTIONS.get(direction.getOpposite()))) {
                    // Questo forza un ricalcolo sia tramite block event sia tramite cambio di stato
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
        
        // Se un blocco vicino cambia, informiamo la BlockEntity
        if (!level.isClientSide()) {
            // Verifica se il blocco adiacente è un tubo di Replication
            Direction directionToPipe = null;
            for (Direction direction : Direction.values()) {
                if (pos.relative(direction).equals(fromPos) && 
                    level.getBlockState(fromPos).getBlock() instanceof MatterPipeBlock) {
                    directionToPipe = direction;
                    break;
                }
            }
            
            // Notifica la BlockEntity dell'aggiornamento
            if (level.getBlockEntity(pos) instanceof RepAE2BridgeBlockEntity blockEntity) {
                // Utilizziamo il metodo handleNeighborChanged
                blockEntity.handleNeighborChanged(fromPos);
                
                // Aggiorniamo lo stato visivo del blocco in base alle connessioni
                boolean isConnected = blockEntity.isActive() && blockEntity.getNetwork() != null;
                if (state.getValue(CONNECTED) != isConnected) {
                    level.setBlock(pos, state.setValue(CONNECTED, isConnected), 3);
                }
                
                // Se è stato trovato un tubo adiacente, forza l'aggiornamento del tubo
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
        // Se il blocco è stato rimosso o sostituito
        if (!state.is(newState.getBlock())) {
            // Assicurati che la BlockEntity venga rimossa correttamente
            if (level.getBlockEntity(pos) instanceof RepAE2BridgeBlockEntity blockEntity) {
                // Forziamo la disconnessione esplicita da entrambe le reti
                blockEntity.disconnectFromNetworks();
            }
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
