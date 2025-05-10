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
 * BlockEntity per il RepAE2Bridge che connette la rete AE2 con la rete di materia di Replication
 */
public class RepAE2BridgeBlockEntity extends ReplicationMachine<RepAE2BridgeBlockEntity> 
        implements IInWorldGridNodeHost, ICraftingInventory, ICraftingProvider {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Costante per il numero di tick prima di processare le richieste accumulate
    private static final int REQUEST_ACCUMULATION_TICKS = 100;
    
    // Coda di pattern pendenti
    private final Queue<IPatternDetails> pendingPatterns = new LinkedList<>();
    private final Map<IPatternDetails, KeyCounter[]> pendingInputs = new HashMap<>();
    
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

                // Se il nodo è attivo, aggiorna i pattern
                if (state == IGridNodeListener.State.POWER && node.isActive()) {
                    LOGGER.info("Bridge: Nodo AE2 attivo, richiesta aggiornamento pattern");
                    ICraftingProvider.requestUpdate(mainNode);
                }
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

                // Forza un aggiornamento dei pattern quando la griglia cambia
                ICraftingProvider.requestUpdate(mainNode);
            }
        }
    })
            .setVisualRepresentation(ModBlocks.REPAE2BRIDGE.get())
            .setInWorldNode(true)
            .setFlags(GridFlags.REQUIRE_CHANNEL)
            .setExposedOnSides(EnumSet.allOf(Direction.class))
            .addService(ICraftingProvider.class, this)
            .setTagName("main");
    
    // Flag per tenere traccia se il nodo è stato creato
    private boolean nodeCreated = false;
    
    // Flag per indicare se dovremmo cercare di riconnetterci alle reti
    private boolean shouldReconnect = false;

    // Componenti del terminale
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

    // Mappa per tenere traccia delle richieste per pattern
    private final Map<ItemStack, Integer> patternRequests = new HashMap<>();
    // Mappa per tenere traccia dei task attivi
    private final Map<String, ItemStack> activeTasks = new HashMap<>();
    // Contatori temporanei per le richieste di crafting
    private final Map<ItemStack, Integer> requestCounters = new HashMap<>();
    private int requestCounterTicks = 0;

    public RepAE2BridgeBlockEntity(BlockPos pos, BlockState blockState) {
        super((BasicTileBlock<RepAE2BridgeBlockEntity>) ModBlocks.REPAE2BRIDGE.get(), 
              ModBlockEntities.REPAE2BRIDGE_BE.get(), 
              pos, 
              blockState);
              
        // Inizializza i componenti del terminale
        this.terminalPlayerTracker = new TerminalPlayerTracker();
        this.sortingTypeValue = 0;
        this.sortingDirection = 1;
        this.matterOpediaSortingTypeValue = 0;
        this.matterOpediaSortingDirection = 1;
        this.output = new InventoryComponent<RepAE2BridgeBlockEntity>("output", 11, 131, 9*2)
                .setRange(9,2);
        this.addInventory(this.output);
    }
    
    @NotNull
    @Override
    public RepAE2BridgeBlockEntity getSelf() {
        return this;
    }

    @Override
    protected NetworkElement createElement(Level level, BlockPos pos) {
        // Inizialmente usiamo l'implementazione base, ma stampiamo un log
        LOGGER.info("Bridge: Creazione elemento di rete a {}", pos);
        return new DefaultMatterNetworkElement(level, pos);
    }

    /**
     * Chiamato quando la BlockEntity viene caricata o dopo il piazzamento
     */
    @Override
    public void onLoad() {
        // Primo inizializza la rete di Replication (come fa la classe base)
        super.onLoad();
        LOGGER.info("Bridge: onLoad chiamato a {}", worldPosition);
        
        // Inizializza il nodo AE2 se non è già stato fatto
        if (!nodeCreated && level != null && !level.isClientSide()) {
            LOGGER.info("Bridge: Inizializzazione nodo AE2");
            mainNode.create(level, worldPosition);
            nodeCreated = true;
            
            // Notifica ai blocchi adiacenti
            forceNeighborUpdates();
            
            // Aggiorna lo stato di connessione visivamente
            updateConnectedState();

            // Forza un aggiornamento dei pattern disponibili
            LOGGER.info("Bridge: Richiesta aggiornamento pattern AE2");
            ICraftingProvider.requestUpdate(mainNode);
        }
        // Reimpostiamo il flag se è stato caricato ma il nodo non esiste più
        else if (nodeCreated && mainNode.getNode() == null) {
            LOGGER.warn("Bridge: Nodo esistente non trovato, richiesta riconnessione");
            nodeCreated = false;
            shouldReconnect = true;
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
                LOGGER.info("Bridge: Inizializzazione nodo AE2 da handleNeighborChanged");
                mainNode.create(level, worldPosition);
                nodeCreated = true;
                
                // Notifica ai blocchi adiacenti
                forceNeighborUpdates();
                
                // Aggiorna lo stato di connessione visivamente
                updateConnectedState();

                // Forza un aggiornamento dei pattern disponibili
                LOGGER.info("Bridge: Richiesta aggiornamento pattern AE2");
                ICraftingProvider.requestUpdate(mainNode);
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
    
    /**
     * Gestisce il tick del server per sincronizzare le due reti
     */
    @Override
    public void serverTick(Level level, BlockPos pos, BlockState state, RepAE2BridgeBlockEntity blockEntity) {
        super.serverTick(level, pos, state, blockEntity);
        
        // Gestione dei contatori temporanei
        if (requestCounterTicks >= REQUEST_ACCUMULATION_TICKS) {
            // Prima di resettare, crea i task per tutti gli item con richieste pendenti
            MatterNetwork network = getNetwork();
            if (network != null && !requestCounters.isEmpty()) {
                LOGGER.info("Bridge: Creazione task per {} item con richieste pendenti", requestCounters.size());
                
                // Per ogni item con richieste pendenti
                for (Map.Entry<ItemStack, Integer> entry : requestCounters.entrySet()) {
                    ItemStack itemStack = entry.getKey();
                    int count = entry.getValue();
                    
                    if (count > 0) {
                        // Cerca il pattern corrispondente in Replication
                        for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                            var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                            if (tile instanceof ChipStorageBlockEntity chipStorage) {
                                for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                                    if (pattern.getStack().getItem().equals(itemStack.getItem())) {
                                        LOGGER.info("Bridge: Creazione task per {} item di {} (richieste accumulate in {} tick)", 
                                            count, itemStack.getItem().getDescriptionId(), REQUEST_ACCUMULATION_TICKS);
                                        
                                        // Crea un task di replicazione con la quantità totale
                                        ReplicationTask task = new ReplicationTask(
                                            pattern.getStack(), 
                                            count, // Usa il numero totale di richieste accumulate
                                            IReplicationTask.Mode.MULTIPLE, 
                                            chipStorage.getBlockPos()
                                        );
                                        
                                        // Aggiungi il task alla rete
                                        network.getTaskManager().getPendingTasks().put(task.getUuid().toString(), task);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Ora possiamo resettare i contatori
            requestCounters.clear();
            requestCounterTicks = 0;
            LOGGER.info("Bridge: Reset contatori richieste dopo {} tick", REQUEST_ACCUMULATION_TICKS);
        } else {
            requestCounterTicks++;
        }
        
        // Controlla la connessione alla rete di Replication
        if (getNetwork() == null) {
            LOGGER.warn("Bridge: Nessuna rete Replication trovata durante il tick");
            // Se non siamo connessi alla rete di Replication, prova a connettersi
            NetworkManager networkManager = NetworkManager.get(level);
            if (networkManager != null && networkManager.getElement(pos) == null) {
                LOGGER.info("Bridge: Tentativo di riconnessione alla rete Replication");
                networkManager.addElement(createElement(level, pos));
            }
        }
        
        // Gestione della coda di pattern
        if (!pendingPatterns.isEmpty() && !isBusy()) {
            IPatternDetails pattern = pendingPatterns.poll();
            KeyCounter[] inputs = pendingInputs.remove(pattern);
            if (pattern != null && inputs != null) {
                LOGGER.info("Bridge: Elaborazione pattern pendente dalla coda");
                pushPattern(pattern, inputs);
            }
        }
        
        // Controlla meno frequentemente lo stato del nodo AE2
        if (level.getGameTime() % 40 == 0) { // Solo ogni 2 secondi
            if (!isActive() && shouldReconnect) {
                LOGGER.info("Bridge: Tentativo di riconnessione al nodo AE2");
                // Se il nodo non è attivo e abbiamo il flag shouldReconnect, tenta la riconnessione
                if (mainNode.getNode() == null && !nodeCreated) {
                    LOGGER.info("Bridge: Inizializzazione nodo AE2 da serverTick");
                    mainNode.create(level, worldPosition);
                    nodeCreated = true;
                    
                    // Notifica ai blocchi adiacenti
                    forceNeighborUpdates();
                    
                    // Aggiorna lo stato di connessione visivamente
                    updateConnectedState();

                    // Forza un aggiornamento dei pattern disponibili
                    LOGGER.info("Bridge: Richiesta aggiornamento pattern AE2");
                    ICraftingProvider.requestUpdate(mainNode);
                } else {
                    forceNeighborUpdates();
                }
                shouldReconnect = false;
            }
            
            // Aggiorna lo stato visivo
            updateConnectedState();
        }
        
        // Aggiorna il terminal player tracker
        this.terminalPlayerTracker.checkIfValid();
    }

    /**
     * Sovrascritto per garantire che il nodo sia sempre alimentato
     * Simile al ReplicationTerminal che è sempre attivo
     */
    public boolean isPowered() {
        // Ritorna sempre true indipendentemente dallo stato energetico reale
        return true;
    }
    
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
                LOGGER.warn("Bridge: NetworkManager non trovato");
                return null;
            }
            
            NetworkElement element = networkManager.getElement(worldPosition);
            if (element == null) {
                // L'elemento non esiste, prova a crearlo
                LOGGER.info("Bridge: Elemento di rete non trovato, creazione in corso");
                element = createElement(level, worldPosition);
                networkManager.addElement(element);
                LOGGER.info("Bridge: Elemento di rete creato e aggiunto");
                
                // Forza un aggiornamento dei blocchi vicini
                forceNeighborUpdates();
            }
            
            if (element.getNetwork() instanceof MatterNetwork matterNetwork) {
                // Verifico se la rete esiste ma non contiene l'elemento
                var elements = matterNetwork.getMatterStacksHolders();
                if (elements != null && !elements.contains(element)) {
                    LOGGER.info("Bridge: Aggiunta manuale dell'elemento alla rete");
                    matterNetwork.addElement(element);
                }
                return matterNetwork;
            } else {
                LOGGER.warn("Bridge: Rete non è una MatterNetwork: {}", 
                    (element.getNetwork() != null ? element.getNetwork().getClass().getName() : "null"));
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("Bridge: Errore nell'accesso alla rete Replication: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    // =================== Metodi di utilità ===================
    
    public boolean isActive() {
        return mainNode.isActive();
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
                    LOGGER.info("Bridge: Inizializzazione nodo AE2 da clearRemoved");
                    mainNode.create(level, worldPosition);
                    nodeCreated = true;
                    
                    // Notifica ai blocchi adiacenti
                    forceNeighborUpdates();
                    
                    // Aggiorna lo stato di connessione visivamente
                    updateConnectedState();

                    // Forza un aggiornamento dei pattern disponibili
                    LOGGER.info("Bridge: Richiesta aggiornamento pattern AE2");
                    ICraftingProvider.requestUpdate(mainNode);
                    
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

    // =================== Implementazione del terminale ===================

    @Override
    public ItemInteractionResult onActivated(Player playerIn, InteractionHand hand, Direction facing, double hitX, double hitY, double hitZ) {
        if (super.onActivated(playerIn, hand, facing, hitX, hitY, hitZ) == ItemInteractionResult.SUCCESS) {
            return ItemInteractionResult.SUCCESS;
        }
        if (playerIn instanceof ServerPlayer serverPlayer) {
            // Invia tutti i pattern disponibili
            for (NetworkElement chipSupplier : this.getNetwork().getChipSuppliers()) {
                var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                if (tile instanceof ChipStorageBlockEntity chipStorage) {
                    this.getNetwork().sendPatternSyncPacket(serverPlayer, chipStorage, tile.getBlockPos());
                }
            }
            
            // Invia lo stato della materia
            this.getNetwork().getTaskManager().getPendingTasks().values().forEach(task -> {
                this.getNetwork().sendTaskSyncPacket(serverPlayer, task);
            });
        }
        return ItemInteractionResult.SUCCESS;
    }

    public TerminalPlayerTracker getTerminalPlayerTracker() {
        return terminalPlayerTracker;
    }

    public InventoryComponent<RepAE2BridgeBlockEntity> getOutput() {
        return output;
    }

    // =================== Classe TerminalPlayerTracker ===================

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
            // Quando un item viene inserito per il crafting, verifichiamo se può essere craftato con Replication
            MatterNetwork network = getNetwork();
            if (network != null) {
                // Verifica se l'item può essere craftato
                for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                    var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                    if (tile instanceof ChipStorageBlockEntity chipStorage) {
                        for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                            if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                // L'item può essere craftato, possiamo procedere
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
        if (what instanceof AEItemKey itemKey) {
            MatterNetwork network = getNetwork();
            if (network != null) {
                // Verifica se l'item può essere estratto (già craftato)
                for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                    var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                    if (tile instanceof ChipStorageBlockEntity chipStorage) {
                        for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                            if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                // L'item può essere estratto
                                return amount;
                            }
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
                // Cerca tutti gli item che possono essere craftati con Replication
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
            // Per ogni chip storage nella rete
            for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                if (tile instanceof ChipStorageBlockEntity chipStorage) {
                    // Per ogni pattern nel chip storage
                    for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                        if (!pattern.getStack().isEmpty() && pattern.getCompletion() == 1) {
                            try {
                                // Crea un pattern di processing AE2
                                ItemStack patternStack = new ItemStack(AEItems.BLANK_PATTERN.asItem());
                                AEItemKey output = AEItemKey.of(pattern.getStack().getItem());
                                
                                // Crea il pattern di processing
                                List<GenericStack> inputs = new ArrayList<>();
                                inputs.add(new GenericStack(AEItemKey.of(Items.STRUCTURE_VOID), 1)); // Input è structure_void
                                
                                List<GenericStack> outputs = new ArrayList<>();
                                outputs.add(new GenericStack(output, 1)); // Output è l'item replicato
                                
                                // Codifica il pattern
                                AEProcessingPattern.encode(patternStack, inputs, outputs);
                                
                                // Crea il pattern AE2
                                AEProcessingPattern aePattern = new AEProcessingPattern(AEItemKey.of(patternStack));
                                
                                patterns.add(aePattern);
                                LOGGER.info("Bridge: Pattern aggiunto per {}", pattern.getStack().getItem().getDescriptionId());
                            } catch (Exception e) {
                                LOGGER.error("Bridge: Errore nella conversione del pattern: {}", e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info("Bridge: Totale pattern disponibili: {}", patterns.size());
        return patterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (patternDetails instanceof AEProcessingPattern aePattern) {
            // Prendi il primo output del pattern (l'item da replicare)
            List<GenericStack> outputs = aePattern.getOutputs();
            if (!outputs.isEmpty()) {
                GenericStack output = outputs.get(0);
                if (output.what() instanceof AEItemKey itemKey) {
                    LOGGER.info("Bridge: Tentativo di avviare replicazione per {}", itemKey.getItem().getDescriptionId());
                    MatterNetwork network = getNetwork();
                    if (network != null) {
                        // Cerca il pattern corrispondente in Replication
                        for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                            var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                            if (tile instanceof ChipStorageBlockEntity chipStorage) {
                                for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                                    if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                        // Incrementa il contatore per questo item
                                        ItemStack itemStack = pattern.getStack();
                                        int currentCount = requestCounters.getOrDefault(itemStack, 0);
                                        requestCounters.put(itemStack, currentCount + 1);
                                        
                                        LOGGER.info("Bridge: Pattern trovato per {}, richieste totali negli ultimi 10 tick: {}", 
                                            itemKey.getItem().getDescriptionId(), currentCount + 1);
                                        
                                        // Non creiamo subito il task, aspettiamo il prossimo reset
                                        return true;
                                    }
                                }
                            }
                        }
                        LOGGER.warn("Bridge: Pattern non trovato nella rete Replication");
                    } else {
                        LOGGER.warn("Bridge: Nessuna rete Replication trovata");
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean isBusy() {
        MatterNetwork network = getNetwork();
        if (network != null) {
            // Rimuovi i task completati dalla mappa
            network.getTaskManager().getPendingTasks().keySet().forEach(taskId -> {
                if (!activeTasks.containsKey(taskId)) {
                    // Il task è stato completato, rimuovilo
                    ItemStack pattern = activeTasks.remove(taskId);
                    if (pattern != null) {
                        // Decrementa il contatore per questo pattern
                        int currentCount = patternRequests.getOrDefault(pattern, 0);
                        if (currentCount > 0) {
                            patternRequests.put(pattern, currentCount - 1);
                            LOGGER.info("Bridge: Task completato per {}, rimangono {} richieste attive", 
                                pattern.getItem().getDescriptionId(), currentCount - 1);
                        }
                    }
                }
            });

            boolean busy = !network.getTaskManager().getPendingTasks().isEmpty();
            if (busy) {
                LOGGER.info("Bridge: Occupato con {} task pendenti", network.getTaskManager().getPendingTasks().size());
                // Log delle richieste per pattern
                patternRequests.forEach((pattern, count) -> 
                    LOGGER.info("Bridge: Pattern {} ha {} richieste attive", 
                        pattern.getItem().getDescriptionId(), count));
            }
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
            LOGGER.info("Bridge: Calcolo crafting per {} x{}", itemKey.getItem().getDescriptionId(), amount);
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
                                        
                                        LOGGER.info("Bridge: Matter necessaria per {}: {} {} ({} per item)", 
                                            itemKey.getItem().getDescriptionId(),
                                            totalMatterNeeded,
                                            matterType.toString(),
                                            matterPerItem);
                                            
                                        LOGGER.info("Bridge: Matter disponibile: {} {}", 
                                            available,
                                            matterType.toString());
                                        
                                        if (available < totalMatterNeeded) {
                                            hasEnoughMatter = false;
                                            missingMatter.put(matterType, totalMatterNeeded - available);
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
                                        
                                        LOGGER.warn("Bridge: Richiesta di {} x{} rifiutata per mancanza di matter", 
                                            itemKey.getItem().getDescriptionId(), amount);
                                        LOGGER.warn("Bridge: Matter mancante:\n{}", errorMsg);
                                        
                                        return CompletableFuture.failedFuture(
                                            new IllegalStateException(errorMsg.toString()));
                                    }
                                    
                                    LOGGER.info("Bridge: Matter sufficiente per craftare {} x{}", 
                                        itemKey.getItem().getDescriptionId(), amount);
                                    
                                    // Se c'è abbastanza matter, crea un piano di crafting
                                    return CompletableFuture.completedFuture(new ICraftingPlan() {
                                        @Override
                                        public GenericStack finalOutput() {
                                            return new GenericStack(AEItemKey.of(itemKey.getItem()), amount);
                                        }

                                        @Override
                                        public long bytes() {
                                            return 0; // Non usiamo bytes
                                        }

                                        @Override
                                        public boolean simulation() {
                                            return false; // Non è una simulazione
                                        }

                                        @Override
                                        public boolean multiplePaths() {
                                            return false; // Non ci sono percorsi multipli
                                        }

                                        @Override
                                        public KeyCounter usedItems() {
                                            return new KeyCounter(); // Non usiamo item
                                        }

                                        @Override
                                        public KeyCounter emittedItems() {
                                            return new KeyCounter(); // Non emettiamo item
                                        }

                                        @Override
                                        public KeyCounter missingItems() {
                                            return new KeyCounter(); // Non ci sono item mancanti
                                        }

                                        @Override
                                        public Map<IPatternDetails, Long> patternTimes() {
                                            return Map.of(); // Non usiamo pattern
                                        }
                                    });
                                } else {
                                    LOGGER.error("Bridge: Impossibile calcolare la matter necessaria per {}", 
                                        itemKey.getItem().getDescriptionId());
                                    return CompletableFuture.failedFuture(
                                        new IllegalStateException("Cannot calculate required matter"));
                                }
                            }
                        }
                    }
                }
                LOGGER.warn("Bridge: Nessun pattern trovato per {}", itemKey.getItem().getDescriptionId());
                return CompletableFuture.failedFuture(
                    new IllegalStateException("No pattern found for this item"));
            } else {
                LOGGER.error("Bridge: Nessuna rete Replication trovata");
                return CompletableFuture.failedFuture(
                    new IllegalStateException("No Replication network found"));
            }
        }
        
        // Se arriviamo qui, non possiamo craftare questo item
        LOGGER.error("Bridge: Tentativo di craftare un item non valido");
        return CompletableFuture.failedFuture(
            new IllegalStateException("Cannot craft this item"));
    }
}