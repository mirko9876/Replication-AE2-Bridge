package net.unfamily.repae2bridge.block.entity;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.api.util.AECableType;
import com.buuz135.replication.api.matter_fluid.IMatterTank;
import com.buuz135.replication.api.matter_fluid.MatterStack;
import com.buuz135.replication.api.matter_fluid.component.MatterTankComponent;
import com.buuz135.replication.api.network.IMatterTanksConsumer;
import com.buuz135.replication.api.network.IMatterTanksSupplier;
import com.buuz135.replication.api.task.IReplicationTask;
import com.buuz135.replication.api.task.ReplicationTask;
import com.buuz135.replication.block.tile.ReplicationMachine;
import com.buuz135.replication.block.tile.ReplicatorBlockEntity;
import com.buuz135.replication.calculation.ReplicationCalculation;
import com.buuz135.replication.network.MatterNetwork;
import com.hrznstudio.titanium.block.BasicTileBlock;
import com.hrznstudio.titanium.block_network.element.NetworkElement;
import com.hrznstudio.titanium.block_network.NetworkManager;
import com.buuz135.replication.network.DefaultMatterNetworkElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.repae2bridge.block.ModBlocks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BlockEntity per il RepAE2Bridge che connette la rete AE2 con la rete di materia di Replication
 */
public class RepAE2BridgeBlockEntity extends ReplicationMachine<RepAE2BridgeBlockEntity> 
        implements IInWorldGridNodeHost, IMatterTanksSupplier, IMatterTanksConsumer {
    
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
                
                // Aggiorna anche lo stato della proprietà CONNECTED nel blocco
                updateConnectedState();
            }
        }
        
        @Override
        public void onGridChanged(RepAE2BridgeBlockEntity nodeOwner, IGridNode node) {
            // Aggiorniamo lo stato del BlockEntity quando la griglia cambia
            if (nodeOwner.level != null) {
                nodeOwner.level.sendBlockUpdated(nodeOwner.worldPosition, nodeOwner.getBlockState(), 
                        nodeOwner.getBlockState(), 3);
                
                // Aggiorna anche lo stato della proprietà CONNECTED nel blocco
                updateConnectedState();
            }
        }
    })
            .setVisualRepresentation(ModBlocks.REPAE2BRIDGE.get()) // Usiamo il blocco stesso come rappresentazione visiva
            .setInWorldNode(true)
            .setFlags(GridFlags.REQUIRE_CHANNEL) // Richiedi un canale come i dispositivi standard di AE2
            .setExposedOnSides(EnumSet.allOf(Direction.class)) // Espone il nodo su tutti i lati
            .setTagName("main");
    
    // Flag per tenere traccia se il nodo è stato creato
    private boolean nodeCreated = false;
    
    // Flag per indicare se dovremmo cercare di riconnetterci alle reti
    private boolean shouldReconnect = false;
    
    // Serbatoio per la materia di Replication
    private final MatterTankComponent<RepAE2BridgeBlockEntity> matterTank;

    public RepAE2BridgeBlockEntity(BlockPos pos, BlockState blockState) {
        super((BasicTileBlock<RepAE2BridgeBlockEntity>) ModBlocks.REPAE2BRIDGE.get(), 
              ModBlockEntities.REPAE2BRIDGE_BE.get(), 
              pos, 
              blockState);
              
        // Aggiungi un serbatoio di materia per la connessione con Replication
        this.matterTank = new MatterTankComponent<>("matter_tank", 8000, 132, 20);
        this.addMatterTank(matterTank);
    }
    
    @NotNull
    @Override
    public RepAE2BridgeBlockEntity getSelf() {
        return this;
    }

    @Override
    protected NetworkElement createElement(Level level, BlockPos pos) {
        // Inizialmente usiamo l'implementazione base, ma stampiamo un log
        System.out.println("Creating network element for RepAE2Bridge at " + pos);
        return new DefaultMatterNetworkElement(level, pos);
    }

    /**
     * Chiamato quando la BlockEntity viene caricata o dopo il piazzamento
     */
    @Override
    public void onLoad() {
        // Primo inizializza la rete di Replication (come fa la classe base)
        super.onLoad();
        
        // Non creiamo il nodo qui - verrà fatto in onReady()
        // Reimpostiamo il flag se è stato caricato ma il nodo non esiste più
        if (nodeCreated && mainNode.getNode() == null) {
            nodeCreated = false;
            shouldReconnect = true;
        }
    }
    
    /**
     * Implementato come in AENetworkedInvBlockEntity
     * Questo metodo viene chiamato dopo che la BlockEntity è stata aggiunta al mondo
     * ed è il momento giusto per creare il nodo della rete
     */
    public void onReady() {
        if (level != null && !level.isClientSide()) {
            // Crea il nodo AE2
            mainNode.create(level, worldPosition);
            nodeCreated = true;
            
            // Notifica ai blocchi adiacenti
            forceNeighborUpdates();
            
            // Aggiorna lo stato di connessione visivamente
            updateConnectedState();
        }
    }
    
    /**
     * Aggiorna lo stato visivo di connessione del blocco
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
                onReady();
            } 
            // Se il nodo esiste già, aggiorniamo solo i blocchi vicini
            else if (mainNode.getNode() != null) {
                forceNeighborUpdates();
            }
            
            // Aggiorna lo stato visivo
            updateConnectedState();
        }
    }
    
    /**
     * Disconnette esplicitamente questo blocco da entrambe le reti
     * Chiamato quando il blocco viene rimosso
     */
    public void disconnectFromNetworks() {
        // Disconnette dalla rete AE2
        if (level != null && !level.isClientSide() && mainNode != null) {
            mainNode.destroy();
            nodeCreated = false;
        }
        
        // La disconnessione dalla rete Replication viene gestita in super.setRemoved()
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
            shouldReconnect = true; // Segna per la riconnessione quando il chunk viene ricaricato
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
        // Salva il flag di riconnessione
        tag.putBoolean("shouldReconnect", shouldReconnect);
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
        // Carica il flag di riconnessione
        if (tag.contains("shouldReconnect")) {
            shouldReconnect = tag.getBoolean("shouldReconnect");
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

    
    /**
     * Gestisce il tick del server per sincronizzare le due reti
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state, RepAE2BridgeBlockEntity blockEntity) {
        super.serverTick(level, pos, state, blockEntity);
        
        // Controlla la connessione alla rete di Replication
        if (getNetwork() == null) {
            // Se non siamo connessi alla rete di Replication, prova a connettersi
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager != null && networkManager.getElement(pos) == null) {
                networkManager.addElement(createElement(level, pos));
            }
        }
        
        // Controlla meno frequentemente lo stato del nodo AE2
        if (level.getGameTime() % 40 == 0) { // Solo ogni 2 secondi
            if (!isActive() && shouldReconnect) {
                // Se il nodo non è attivo e abbiamo il flag shouldReconnect, tenta la riconnessione
                if (mainNode.getNode() == null && !nodeCreated) {
                    onReady();
                } else {
                    forceNeighborUpdates();
                }
                shouldReconnect = false;
            }
            
            // Aggiorna lo stato visivo
            updateConnectedState();
        }
        
        // Se entrambe le reti sono disponibili, gestisci il trasferimento di energia
        if (mainNode.getNode() != null && getNetwork() != null) {
            handleNetworkTransfer();
        }
    }

    /**
     * Sovrascritto per garantire che il nodo sia sempre alimentato
     * Simile al ReplicationTerminal che è sempre attivo
     */

    public boolean isPowered() {
        // Ritorna sempre true indipendentemente dallo stato energetico reale
        return true;
    }
    
    // =================== Metodi per trasferire dati tra AE2 e Replication ===================
    
    /**
     * Migliore implementazione per ottenere il network di Replication
     * con gestione degli errori e maggiore robustezza
     */
    @Override
    public MatterNetwork getNetwork() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        
        try {
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager == null) {
                return null;
            }
            
            NetworkElement element = networkManager.getElement(worldPosition);
            if (element == null) {
                // L'elemento non esiste, prova a crearlo
                element = createElement(level, worldPosition);
                networkManager.addElement(element);
                // Log di debug
                System.out.println("Replication network element created at " + worldPosition);
                
                // Forza un aggiornamento dei blocchi vicini
                forceNeighborUpdates();
            }
            
            if (element.getNetwork() instanceof MatterNetwork matterNetwork) {
                // Verifico se la rete esiste ma non contiene l'elemento
                var elements = matterNetwork.getMatterStacksHolders();
                if (elements != null && !elements.contains(element)) {
                    matterNetwork.addElement(element);
                    // Log di debug
                    System.out.println("Added element to Replication network manually");
                }
                return matterNetwork;
            } else {
                System.out.println("Network is not a MatterNetwork: " + 
                    (element.getNetwork() != null ? element.getNetwork().getClass().getName() : "null"));
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error accessing Replication network: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    // =================== Metodi di utilità ===================
    
    public boolean isActive() {
        return mainNode.isActive();
    }
    
    // =================== Implementazione di IMatterTanksSupplier e IMatterTanksConsumer ===================
    
    @Override
    public List<IMatterTank> getTanks() {
        List<IMatterTank> tanks = new ArrayList<>();
        tanks.add(matterTank);
        return tanks;
    }
    
    // =================== Integrazione per l'autocrafting tra AE2 e Replication ===================
    
    /**
     * Aggiorna la connessione tra le reti AE2 e Replication
     * Gestisce solo il trasferimento di energia tra le due reti
     */
    private void handleNetworkTransfer() {
        if (!isActive() || !isPowered() || getNetwork() == null || mainNode.getNode() == null) {
            return; // Rete non pronta
        }
        
        try {
            // Ottieni la rete AE2
            var grid = mainNode.getNode().getGrid();
            if (grid == null) {
                return;
            }
            
            // Ottieni la rete Replication
            MatterNetwork replicationNetwork = getNetwork();
            if (replicationNetwork == null) {
                return;
            }
            
            // Ogni 20 tick (circa 1 secondo), sincronizza lo stato tra le reti
            if (level.getGameTime() % 20 == 0) {
                // Aggiorna lo stato visivo del blocco per mostrare che è attivo
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                
                // Invia energia dalla rete AE2 alla rete Replication se necessario
                if (isPowered() && replicationNetwork.getEnergyStorage().getEnergyStored() < replicationNetwork.getEnergyStorage().getMaxEnergyStored() / 2) {
                    // In un'implementazione reale, qui ci sarebbe la logica di trasferimento energetico
                    getEnergyStorage().receiveEnergy(100, false); // Esempio: ricevi energia nella componente energia
                }
            }
            
            // Verifica periodicamente il serbatoio di materia
            if (level.getGameTime() % 40 == 0) {
                // Sincronizza lo stato del serbatoio di materia
                syncObject(matterTank);
                
                // Invia aggiornamenti alla rete
                if (getNetwork() != null) {
                    // Notifica la rete Replication di eventuali cambiamenti nel serbatoio
                    MatterStack matterStack = matterTank.getMatter();
                    if (!matterStack.isEmpty()) {
                        getNetwork().onTankValueChanged(matterStack.getMatterType());
                    }
                }
            }
        } catch (Exception e) {
            // Gestisce eventuali errori durante il trasferimento
            System.err.println("Errore durante il trasferimento di dati tra le reti: " + e.getMessage());
        }
    }

    /**
     * Metodo chiamato quando l'entità viene salvata o caricata da/verso il disco
     */
    @Override
    public void clearRemoved() {
        super.clearRemoved();
        
        // Programma l'inizializzazione del nodo AE2
        if (level != null && !level.isClientSide()) {
            // Usa GridHelper.onFirstTick per programmare l'inizializzazione
            GridHelper.onFirstTick(this, blockEntity -> {
                // Se il flag di riconnessione è attivo o il nodo non è ancora creato, inizializza
                if (shouldReconnect || !nodeCreated) {
                    onReady();
                    shouldReconnect = false;
                }
            });
        }
    }

    /**
     * Metodo utile per ottenere la rete Replication con coerenza di nomi
     * @return La rete di materia Replication
     */
    private MatterNetwork getReplicationNetwork() {
        return getNetwork();
    }
}
