package net.unfamily.repae2bridge.block.entity;

import appeng.api.networking.GridHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.util.AECableType;
import com.buuz135.replication.network.MatterNetwork;
import com.hrznstudio.titanium.block.BasicTileBlock;
import com.hrznstudio.titanium.block_network.element.NetworkElement;
import com.hrznstudio.titanium.block_network.NetworkManager;
import com.buuz135.replication.block.tile.NetworkBlockEntity;
import com.buuz135.replication.network.DefaultMatterNetworkElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.repae2bridge.block.ModBlocks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * BlockEntity per il RepAE2Bridge che connette la rete AE2 con la rete di materia di Replication
 */
public class RepAE2BridgeBlockEntity extends NetworkBlockEntity<RepAE2BridgeBlockEntity> implements IInWorldGridNodeHost {
    
    // Nodo AE2 per connessione alla rete
    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, new IGridNodeListener<RepAE2BridgeBlockEntity>() {
        @Override
        public void onSaveChanges(RepAE2BridgeBlockEntity nodeOwner, IGridNode node) {
            nodeOwner.setChanged();
        }
        
        @Override
        public void onStateChanged(RepAE2BridgeBlockEntity nodeOwner, IGridNode node, IGridNodeListener.State state) {
            // Aggiorniamo lo stato del BlockEntity quando lo stato del nodo cambia
            if (nodeOwner.level != null) {
                nodeOwner.level.sendBlockUpdated(nodeOwner.worldPosition, nodeOwner.getBlockState(), 
                        nodeOwner.getBlockState(), 3);
            }
        }
        
        @Override
        public void onGridChanged(RepAE2BridgeBlockEntity nodeOwner, IGridNode node) {
            // Aggiorniamo lo stato del BlockEntity quando la griglia cambia
            if (nodeOwner.level != null) {
                nodeOwner.level.sendBlockUpdated(nodeOwner.worldPosition, nodeOwner.getBlockState(), 
                        nodeOwner.getBlockState(), 3);
            }
        }
    })
            .setIdlePowerUsage(1.0) // Consumo energetico al minimo
            .setVisualRepresentation(ModBlocks.REPAE2BRIDGE.get()) // Usiamo il blocco stesso come rappresentazione visiva
            .setInWorldNode(true)
            .setFlags(GridFlags.REQUIRE_CHANNEL) // Imposta il flag per richiedere un canale
            .setExposedOnSides(EnumSet.allOf(Direction.class)) // Espone il nodo su tutti i lati
            .setTagName("main");
    
    // Flag per tenere traccia se il nodo è stato creato
    private boolean nodeCreated = false;

    public RepAE2BridgeBlockEntity(BlockPos pos, BlockState blockState) {
        super((BasicTileBlock<RepAE2BridgeBlockEntity>) ModBlocks.REPAE2BRIDGE.get(), 
              ModBlockEntities.REPAE2BRIDGE_BE.get(), 
              pos, 
              blockState);
    }
    
    @NotNull
    @Override
    public RepAE2BridgeBlockEntity getSelf() {
        return this;
    }

    @Override
    protected NetworkElement createElement(Level level, BlockPos pos) {
        return new DefaultMatterNetworkElement(level, pos);
    }

    /**
     * Chiamato quando la BlockEntity viene caricata o dopo il piazzamento
     */
    @Override
    public void onLoad() {
        // Inizializza prima la rete di Replication
        if (level != null && !level.isClientSide()) {
            // Inizializza manualmente l'elemento di rete di Replication
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager.getElement(worldPosition) == null) {
                networkManager.addElement(createElement(level, worldPosition));
            }
            
            // Usa il sistema di onFirstTick di AE2 per inizializzare il nodo quando appropriato
            GridHelper.onFirstTick(this, tile -> {
                // Crea il nodo AE2
                initializeAE2Node();
            });
        }
    }
    
    /**
     * Inizializza il nodo AE2 e forza gli aggiornamenti necessari
     */
    private void initializeAE2Node() {
        if (level != null && !level.isClientSide()) {
            // Verifica se il nodo è già stato creato
            if (mainNode.getNode() == null && !nodeCreated) {
                // Crea il nodo AE2
                mainNode.create(level, worldPosition);
                nodeCreated = true;
                
                // Forza un aggiornamento ai blocchi vicini dopo aver creato il nodo
                forceNeighborUpdates();
            }
        }
    }
    
    /**
     * Controlla se è presente un controller AE2 nella rete verificando se ci sono cavi AE2 adiacenti attivi
     * @return true se viene trovato un cavo AE2 attivo, che probabilmente è collegato a un controller
     */
    private boolean hasAE2NetworkConnection() {
        if (level != null && !level.isClientSide()) {
            // Controlla tutti i blocchi vicini per vedere se ci sono cavi AE2 attivi
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(direction);
                
                // Se il blocco ha un'entità blocco e implementa IInWorldGridNodeHost
                if (level.getBlockEntity(neighborPos) instanceof IInWorldGridNodeHost host) {
                    IGridNode node = host.getGridNode(direction.getOpposite());
                    if (node != null && node.isActive()) {
                        // Se il nodo è attivo, significa che è probabilmente collegato a un controller
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Forza gli aggiornamenti ai blocchi vicini
     */
    private void forceNeighborUpdates() {
        if (level != null && !level.isClientSide()) {
            // Forza un aggiornamento del blocco stesso prima
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            
            // Poi forza aggiornamenti ai blocchi vicini
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (!neighborState.isAir()) {
                    // Notifica prima il blocco vicino (per triggerare le sue connessioni)
                    level.neighborChanged(neighborPos, getBlockState().getBlock(), worldPosition);
                }
            }
        }
    }

    /**
     * Gestisce i cambiamenti dei blocchi vicini
     * Chiamato dal blocco RepAE2BridgeBl
     */
    public void handleNeighborChanged(BlockPos fromPos) {
        if (level != null && !level.isClientSide()) {
            // Controlla se c'è un controller AE2 nella rete
            boolean hasAE2Connection = hasAE2NetworkConnection();
            
            // Se c'è una connessione AE2 e il nodo non è creato, inizializza il nodo
            if (hasAE2Connection && !nodeCreated) {
                initializeAE2Node();
            } 
            // Se il nodo esiste già, aggiorniamo solo i blocchi vicini
            else if (mainNode.getNode() != null) {
                forceNeighborUpdates();
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            // Distruggi il nodo quando il blocco viene rimosso
            mainNode.destroy();
            nodeCreated = false;
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide()) {
            // Distruggi il nodo quando il chunk viene scaricato
            mainNode.destroy();
            nodeCreated = false;
        }
        super.onChunkUnloaded();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Salva lo stato del nodo AE2
        mainNode.saveToNBT(tag);
        // Salva anche il flag di creazione del nodo
        tag.putBoolean("nodeCreated", nodeCreated);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Carica lo stato del nodo AE2
        mainNode.loadFromNBT(tag);
        // Carica il flag di creazione del nodo
        if (tag.contains("nodeCreated")) {
            nodeCreated = tag.getBoolean("nodeCreated");
        }
    }

    // =================== Implementazione di IInWorldGridNodeHost ===================
    
    @Override
    @Nullable
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART; // Usa SMART per compatibilità con la maggior parte dei cavi AE2
    }
    
    // =================== Implementazione per la sincronizzazione con Replication ===================
    
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state, RepAE2BridgeBlockEntity blockEntity) {
        super.serverTick(level, pos, state, blockEntity);
        
        // Controlla la connessione alla rete di Replication
        if (getNetwork() == null) {
            // Se non siamo connessi alla rete di Replication, prova a connettersi
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager.getElement(pos) == null) {
                networkManager.addElement(createElement(level, pos));
            }
        }
        
        // Verifica periodicamente se è presente una connessione AE2 e se il nodo deve essere inizializzato
        if (!nodeCreated && mainNode.getNode() == null) {
            // Controlla se c'è una connessione alla rete AE2
            boolean hasAE2Connection = hasAE2NetworkConnection();
            
            // Se c'è una connessione, inizializza il nodo
            if (hasAE2Connection) {
                initializeAE2Node();
            }
        }
    }
    
    // =================== Metodi per trasferire dati tra AE2 e Replication ===================
    
    /**
     * Ottiene il network di Replication a cui questo bridge è connesso
     * @return Il MatterNetwork o null se non connesso
     */
    @Nullable
    public MatterNetwork getReplicationNetwork() {
        return getNetwork();
    }
    
    // =================== Metodi di utilità ===================
    
    public boolean isActive() {
        return mainNode.isActive();
    }
    
    public boolean isPowered() {
        return mainNode.isPowered();
    }
}
