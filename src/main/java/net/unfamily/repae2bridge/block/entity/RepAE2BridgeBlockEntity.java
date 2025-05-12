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
 * BlockEntity per il RepAE2Bridge che connette la rete AE2 con la rete di materia di Replication
 */
public class RepAE2BridgeBlockEntity extends ReplicationMachine<RepAE2BridgeBlockEntity> 
        implements IInWorldGridNodeHost, ICraftingInventory, ICraftingProvider, IStorageProvider {
    
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
                    
                    // Richiede anche un aggiornamento dello storage per mostrare gli item di materia
                    IStorageProvider.requestUpdate(mainNode);
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
                
                // Richiede anche un aggiornamento dello storage per mostrare gli item di materia
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

    // Timer per l'aggiornamento periodico dei pattern
    private int patternUpdateTicks = 0;
    private static final int PATTERN_UPDATE_INTERVAL = 100; // Aggiorna ogni 5 secondi (100 tick)
    

    // Cache dei warning di materia insufficiente per evitare ripetizioni
    private Map<String, Long> lastMatterWarnings = new HashMap<>();
    // Tempo minimo tra warning consecutivi per lo stesso item (in tick)
    private static final int WARNING_COOLDOWN = 600; // 30 secondi

    public RepAE2BridgeBlockEntity(BlockPos pos, BlockState blockState) {
        super((BasicTileBlock<RepAE2BridgeBlockEntity>) ModBlocks.REPAE2BRIDGE.get(), 
              ModBlockEntities.REPAE2BRIDGE_BE.get(), 
              pos, 
              blockState);
              
        // Inizializza i componenti del terminale
        this.terminalPlayerTracker = new TerminalPlayerTracker();
        /*this.sortingTypeValue = 0;
        this.sortingDirection = 1;
        this.matterOpediaSortingTypeValue = 0;
        this.matterOpediaSortingDirection = 1;*/
        
        // Rimuovo la creazione dell'inventario per evitare la GUI
        // this.output = new InventoryComponent<RepAE2BridgeBlockEntity>("output", 11, 131, 9*2)
        //        .setRange(9,2);
        // this.addInventory(this.output);
    }
    
    @NotNull
    @Override
    public RepAE2BridgeBlockEntity getSelf() {
        return this;
    }

    /**
     * Controlla se è presente un altro bridge nella direzione specificata
     * @param direction La direzione da controllare
     * @return true se c'è un altro bridge nella direzione specificata
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
        // Creiamo un elemento di rete personalizzato che non si connette ad altri bridge
        LOGGER.info("Bridge: Creazione elemento di rete a {}", pos);
        return new DefaultMatterNetworkElement(level, pos) {
            @Override
            public boolean canConnectFrom(Direction direction) {
                // Controlla se nella direzione specificata c'è un altro bridge
                BlockPos neighborPos = pos.relative(direction);
                if (level.getBlockEntity(neighborPos) instanceof RepAE2BridgeBlockEntity) {
                    LOGGER.info("Bridge: Evitata connessione con altro bridge a {}", neighborPos);
                    return false;
                }
                // Altrimenti usiamo il comportamento predefinito
                return super.canConnectFrom(direction);
            }
        };
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
            // Se il blocco che è cambiato è un altro bridge, ignoriamo l'aggiornamento
            Direction directionToNeighbor = null;
            for (Direction dir : Direction.values()) {
                if (worldPosition.relative(dir).equals(fromPos)) {
                    directionToNeighbor = dir;
                    break;
                }
            }
            
            if (directionToNeighbor != null && level.getBlockEntity(fromPos) instanceof RepAE2BridgeBlockEntity) {
                LOGGER.info("Bridge: Ignorato aggiornamento da altro bridge a {}", fromPos);
                return;
            }
            
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
        
        // Aggiornamento periodico dei pattern - rimuovi controllo per matterUpdatesBlocked
        if (patternUpdateTicks >= PATTERN_UPDATE_INTERVAL) {
            if (isActive() && getNetwork() != null) {
                LOGGER.info("Bridge: Aggiornamento periodico dei pattern");
                ICraftingProvider.requestUpdate(mainNode);
                
                // Aggiorna anche lo storage per aggiornare le quantità di materia visualizzate
                IStorageProvider.requestUpdate(mainNode);
            }
            patternUpdateTicks = 0;
        } else {
            patternUpdateTicks++;
        }
        
        // Controlla periodicamente se ci sono item di materia virtuale nella rete AE2
        // che non dovrebbero essere lì e li rimuove
        if (level.getGameTime() % 40 == 0 && isActive() && mainNode.getNode() != null) {
            IGrid grid = mainNode.getNode().getGrid();
            if (grid != null) {
                IStorageService storageService = grid.getStorageService();
                if (storageService != null) {
                    // Ottieni tutti gli item nella rete
                    KeyCounter items = storageService.getInventory().getAvailableStacks();
                    
                    // Verifica se ci sono item di materia virtuale
                    items.forEach(entry -> {
                        AEKey key = entry.getKey();
                        if (key instanceof AEItemKey itemKey && isVirtualMatterItem(itemKey.getItem())) {
                            long amount = entry.getLongValue();
                            if (amount > 0) {
                                LOGGER.info("Bridge: Rilevati {} item virtuali di materia {} nella rete. Rimozione in corso...",
                                    amount, itemKey.getItem().getDescriptionId());
                                
                                // Estrai tutta la materia virtuale per rimuoverla
                                storageService.getInventory().extract(itemKey, amount, Actionable.MODULATE, null);
                                
                                LOGGER.info("Bridge: Rimossi {} item virtuali di materia {} dalla rete",
                                    amount, itemKey.getItem().getDescriptionId());
                            }
                        }
                    });
                }
            }
        }
        
        // Gestione dei contatori temporanei
        if (requestCounterTicks >= REQUEST_ACCUMULATION_TICKS) {
            // Prima di resettare, crea i task per tutti gli item con richieste pendenti
            MatterNetwork network = getNetwork();
            if (network != null && !requestCounters.isEmpty()) {
                LOGGER.info("Bridge: Creazione task per {} item con richieste pendenti", requestCounters.size());
                
                // Calcola il totale dei task che verranno creati
                int totalItems = 0;
                for (int count : requestCounters.values()) {
                    totalItems += count;
                }
                
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
                                        String taskId = task.getUuid().toString();
                                        network.getTaskManager().getPendingTasks().put(taskId, task);
                                        
                                        // Aggiungi alla mappa dei task attivi
                                        activeTasks.put(taskId, itemStack);
                                        
                                        // Aggiorna il contatore delle richieste per pattern
                                        int currentPatternRequests = patternRequests.getOrDefault(itemStack, 0);
                                        patternRequests.put(itemStack, currentPatternRequests + count);
                                        
                                        LOGGER.info("Bridge: Task creato con ID {}, richieste totali per questo pattern: {}", 
                                            taskId, currentPatternRequests + count);
                                        
                                        // Estrai la materia necessaria
                                        var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                        if (matterCompound != null) {
                                            // Estrai ogni tipo di materia dalla rete
                                            for (MatterValue matterValue : matterCompound.getValues().values()) {
                                                var matterType = matterValue.getMatter();
                                                var matterAmount = (long)Math.ceil(matterValue.getAmount()) * count;
                                                
                                                // Trova l'item virtuale corrispondente
                                                Item matterItem = getItemForMatterType(matterType);
                                                if (matterItem != null) {
                                                    AEItemKey matterKey = AEItemKey.of(matterItem);
                                                    
                                                    // Nota: ora estraiamo la materia reale per la replicazione
                                                    LOGGER.info("Bridge: Estrazione di {} materia reale {} per replicazione", 
                                                        matterAmount, matterType.getName());
                                                    
                                                    // Estrai la materia dalla rete Replication
                                                    // Diminuisci la materia disponibile dalla rete
                                                    // In Replication non esiste un metodo diretto per estrarre materia dalla rete
                                                    // quindi qui simuliamo l'estrazione rimuovendo il conteggio dalla visualizzazione virtuale
                                                    
                                                    // Consumiamo sempre la materia virtuale per evitare blocchi del pattern
                                                    long extracted = extract(matterKey, matterAmount, Actionable.MODULATE);
                                                    LOGGER.info("Bridge: Consumata materia virtuale {}: {}", matterType.getName(), extracted);
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
        // Non chiamare super.onActivated() che aprirebbe la GUI
        // Non chiamare openGui(playerIn) che aprirebbe la GUI
        
        // Manteniamo solo la parte relativa all'aggiornamento dei pattern AE2
        if (!level.isClientSide() && playerIn instanceof ServerPlayer serverPlayer) {
            // Aggiorna i pattern in AE2
            ICraftingProvider.requestUpdate(mainNode);
            LOGGER.info("Bridge: Aggiornamento pattern AE2 da onActivated");
        }
        return ItemInteractionResult.SUCCESS;
    }

    public TerminalPlayerTracker getTerminalPlayerTracker() {
        return terminalPlayerTracker;
    }

    public InventoryComponent<RepAE2BridgeBlockEntity> getOutput() {
        // Restituisco null per evitare errori
        return null;
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
        if (what instanceof AEItemKey) {
            AEItemKey itemKey = (AEItemKey) what;
            Item item = itemKey.getItem();
            if (isVirtualMatterItem(item)) {
                // Per la matter virtuale, ora permettiamo l'estrazione
                // il matterItemsStorage gestirà la logica
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
                            // L'item può essere estratto
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
                                
                                // Ottieni il composto di materia per questo pattern
                                var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                if (matterCompound != null) {
                                    // Crea il pattern di processing con i requisiti reali di materia
                                    List<GenericStack> inputs = new ArrayList<>();
                                    
                                    // Aggiungi ogni tipo di materia richiesto come input
                                    for (MatterValue matterValue : matterCompound.getValues().values()) {
                                        var matterType = matterValue.getMatter();
                                        var matterAmount = (long)Math.ceil(matterValue.getAmount());
                                        
                                        // Trova l'item virtuale corrispondente al tipo di materia
                                        Item matterItem = getItemForMatterType(matterType);
                                        if (matterItem != null) {
                                            // Aggiungi la materia come input
                                            inputs.add(new GenericStack(AEItemKey.of(matterItem), matterAmount));
                                            LOGGER.info("Bridge: Pattern per {} richiede {} di {} matter", 
                                                pattern.getStack().getItem().getDescriptionId(), 
                                                matterAmount,
                                                matterType.getName());
                                        }
                                    }
                                    
                                    List<GenericStack> outputs = new ArrayList<>();
                                    outputs.add(new GenericStack(output, 1)); // Output è l'item replicato
                                    
                                    // Codifica il pattern
                                    AEProcessingPattern.encode(patternStack, inputs, outputs);
                                    
                                    // Crea il pattern AE2
                                    AEProcessingPattern aePattern = new AEProcessingPattern(AEItemKey.of(patternStack));
                                    
                                    patterns.add(aePattern);
                                    LOGGER.info("Bridge: Pattern aggiunto per {}", pattern.getStack().getItem().getDescriptionId());
                                }
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
        MatterNetwork network = getNetwork();
        
        if (network != null && isActive() && patternDetails != null) {
            // Verifica se il pattern produce un item che possiamo replicare
            if (patternDetails.getOutputs().size() == 1) {
                var output = patternDetails.getOutputs().iterator().next();
                if (output.what() instanceof AEItemKey itemKey) {
                    LOGGER.info("Bridge: Richiesta di pushPattern per {}", itemKey.getItem().getDescriptionId());
                    
                    // Se siamo occupati, metti in coda il pattern
                    if (isBusy()) {
                        LOGGER.info("Bridge: Bridge occupato, mettendo in coda il pattern");
                        pendingPatterns.add(patternDetails);
                        pendingInputs.put(patternDetails, inputHolder);
                        return true;
                    }
                    
                    // Cerca il pattern in tutti i chip storage della rete
                    for (NetworkElement chipSupplier : network.getChipSuppliers()) {
                        var tile = chipSupplier.getLevel().getBlockEntity(chipSupplier.getPos());
                        if (tile instanceof ChipStorageBlockEntity chipStorage) {
                            for (MatterPattern pattern : chipStorage.getPatterns(level, chipStorage)) {
                                if (pattern.getStack().getItem().equals(itemKey.getItem())) {
                                    // Controlla se abbiamo abbastanza matter virtuale negli input
                                    if (inputHolder != null && inputHolder.length > 0) {
                                        KeyCounter inputs = inputHolder[0];
                                        boolean hasAllMatter = true;
                                        
                                        // Ottieni il composto di materia per questo pattern
                                        var matterCompound = ClientReplicationCalculation.getMatterCompound(pattern.getStack());
                                        if (matterCompound != null) {
                                            // Verifica direttamente sulla rete se c'è abbastanza materia disponibile
                                            // invece di controllare gli inputs virtuali di AE2
                                            for (MatterValue matterValue : matterCompound.getValues().values()) {
                                                var matterType = matterValue.getMatter();
                                                var matterAmount = (long)Math.ceil(matterValue.getAmount());
                                                
                                                // Verifica quanta materia è disponibile nella rete
                                                long available = network.calculateMatterAmount(matterType);
                                                
                                                LOGGER.info("Bridge: Verifica disponibilità per {}: {} {} (richiesto: {}, disponibile: {})", 
                                                    itemKey.getItem().getDescriptionId(),
                                                    matterType.getName(),
                                                    matterAmount,
                                                    matterAmount,
                                                    available);
                                                
                                                if (available < matterAmount) {
                                                    hasAllMatter = false;
                                                    
                                                    // Crea una chiave unica per questo warning
                                                    String warningKey = itemKey.getItem().getDescriptionId() + ":" + matterType.getName();
                                                    long currentTime = level.getGameTime();
                                                    
                                                    // Controlla se abbiamo già mostrato questo warning di recente
                                                    if (!lastMatterWarnings.containsKey(warningKey) || 
                                                            currentTime - lastMatterWarnings.get(warningKey) > WARNING_COOLDOWN) {
                                                        // Mostra il warning e aggiorna il timestamp
                                                        LOGGER.warn("Bridge: Matter {} insufficiente per {}. Ha: {}, Necessita: {}", 
                                                            matterType.getName(),
                                                            itemKey.getItem().getDescriptionId(),
                                                            available,
                                                            matterAmount);
                                                        lastMatterWarnings.put(warningKey, currentTime);
                                                    }
                                                    break;
                                                }
                                            }
                                            
                                            // Estrai ogni tipo di materia dalla rete se abbiamo tutto
                                            if (hasAllMatter) {
                                                for (MatterValue matterValue : matterCompound.getValues().values()) {
                                                    var matterType = matterValue.getMatter();
                                                    var matterAmount = (long)Math.ceil(matterValue.getAmount());
                                                    
                                                    // Trova l'item virtuale corrispondente
                                                    Item matterItem = getItemForMatterType(matterType);
                                                    if (matterItem != null) {
                                                        AEItemKey matterKey = AEItemKey.of(matterItem);
                                                        
                                                        // Nota: ora estraiamo la materia reale per la replicazione
                                                        LOGGER.info("Bridge: Estrazione di {} materia reale {} per replicazione", 
                                                            matterAmount, matterType.getName());
                                                        
                                                        long extracted = extract(matterKey, matterAmount, Actionable.MODULATE);
                                                        LOGGER.info("Bridge: Consumata materia virtuale {}: {}", matterType.getName(), extracted);
                                                    }
                                                }
                                            } else {
                                                LOGGER.warn("Bridge: Matter insufficiente nella rete per craftare {}", 
                                                    itemKey.getItem().getDescriptionId());
                                                return false;
                                            }
                                        }
                                    }
                                    
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
            
            // Se non siamo occupati ma gli aggiornamenti sono ancora bloccati, sblocchiamoli
            if (!busy && requestCounters.isEmpty()) {
                // Rimuoviamo questo messaggio di log che non ha più senso
                // LOGGER.info("Bridge: Aggiornamenti di materia sbloccati da isBusy");
                
                // Forza un aggiornamento dello storage per mostrare le nuove quantità
                IStorageProvider.requestUpdate(mainNode);
            }
            
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
                                            
                                            // Crea una chiave unica per questo warning
                                            String warningKey = itemKey.getItem().getDescriptionId() + ":" + matterType.getName();
                                            long currentTime = level.getGameTime();
                                            
                                            // Controlla se abbiamo già mostrato questo warning di recente
                                            if (!lastMatterWarnings.containsKey(warningKey) || 
                                                    currentTime - lastMatterWarnings.get(warningKey) > WARNING_COOLDOWN) {
                                                LOGGER.warn("Bridge: Matter {} insufficiente per {}. Necessario: {}, Disponibile: {}", 
                                                    matterType.getName(), 
                                                    itemKey.getItem().getDescriptionId(), 
                                                    totalMatterNeeded, 
                                                    available);
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

    // Mappa IMatterType -> Item virtuale
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

    // Utility per riconoscere gli item virtuali di matter
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

    // Metodo per mostrare gli item virtuali nel terminale AE2
    public void getAvailableItems(KeyCounter items) {
        System.out.println("DEBUG: Chiamato getAvailableItems");
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
                System.out.println("DEBUG: " + matterType.getName() + " -> " + amount);
                if (amount > 0) {
                    Item item = getItemForMatterType(matterType);
                    if (item != null) {
                        System.out.println("DEBUG: Aggiungo item virtuale " + item + " quantità " + amount);
                        items.add(AEItemKey.of(item), amount);
                    } else {
                        System.out.println("DEBUG: Nessun item associato a " + matterType.getName());
                    }
                }
            }
        } else {
            System.out.println("DEBUG: Nessuna rete Replication trovata");
        }
    }

    @Override
    public void mountInventories(IStorageMounts storageMounts) {
        // Montiamo lo storage virtuale con priorità alta per essere sempre visibile
        storageMounts.mount(matterItemsStorage, 100);
    }

    /**
     * Implementazione di MEStorage per esporre gli item virtuali di materia
     */
    private class MatterItemsStorage implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            // Non permettiamo inserimenti in questo storage
            return 0;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            // Consentiamo l'estrazione, ma solo per operazioni di autocrafting
            if (what instanceof AEItemKey itemKey && isVirtualMatterItem(itemKey.getItem())) {
                // Ottieni la rete
                MatterNetwork network = getNetwork();
                if (network != null) {
                    // Trova il tipo di materia corrispondente
                    IMatterType matterType = getMatterTypeForItem(itemKey.getItem());
                    if (matterType != null) {
                        // Verifica quanta materia è disponibile nella rete
                        long available = network.calculateMatterAmount(matterType);
                        
                        // Log per debugging della disponibilità
                        if (available < amount) {
                            LOGGER.info("Bridge: Disponibilità insufficiente di {} - Richiesto: {}, Disponibile: {}",
                                matterType.getName(), amount, available);
                        }
                        
                        // Decidi quanto estrarre (il minimo tra la richiesta e il disponibile)
                        long toExtract = Math.min(amount, available);
                        
                        // Se è una simulazione, restituisci solo la quantità che potremmo estrarre
                        if (mode == Actionable.SIMULATE) {
                            LOGGER.debug("Bridge: Simulazione estrazione di {} {}", toExtract, matterType.getName());
                            return toExtract;
                        }
                        
                        // Verifica se la fonte è una export bus o interface automatica (che vogliamo bloccare)
                        if (source != null && isAutomationPart(source)) {
                            // Blocca le richieste da export bus
                            LOGGER.warn("Bridge: Blocco estrazione da bus di esportazione di {} {}", 
                                amount, matterType.getName());
                            return 0;
                        }
                        
                        // Permette tutte le altre operazioni
                        if (toExtract > 0) {
                            LOGGER.info("Bridge: Estrazione virtuale di {} {}", 
                                toExtract, matterType.getName());
                            return toExtract;
                        }
                    }
                }
            }
            return 0;
        }

        /**
         * Verifica se la fonte è una parte di automazione (export bus, interface automatica, ecc.)
         * @param source La fonte della richiesta
         * @return true se è una parte di automazione che vogliamo bloccare
         */
        private boolean isAutomationPart(IActionSource source) {
            // Se c'è una macchina, verifica se è una parte di automazione
            if (source.machine().isPresent()) {
                var machine = source.machine().get();
                String machineClass = machine.getClass().getName();
                
                // Blocca specificamente le parti di automazione
                return machineClass.contains("appeng.parts.autom");
            }
            
            // Non è una export bus
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
                    LOGGER.info("Bridge: Materia {} disponibile: {}", matterType.getName(), amount);
                    if (amount > 0) {
                        Item item = getItemForMatterType(matterType);
                        if (item != null) {
                            LOGGER.info("Bridge: Aggiungo item virtuale {} quantità {}", item, amount);
                            out.add(AEItemKey.of(item), amount);
                        } else {
                            LOGGER.info("Bridge: Nessun item associato a {}", matterType.getName());
                        }
                    }
                }
            } else {
                LOGGER.warn("Bridge: Nessuna rete Replication trovata");
            }
        }
    }
    
    // Istanza dello storage virtuale degli item di materia
    private final MatterItemsStorage matterItemsStorage = new MatterItemsStorage();

    // Metodo per reagire agli eventi della rete Replication
    public void handleReplicationNetworkEvent() {
        if (isActive() && level != null && !level.isClientSide()) {
            // Forza un aggiornamento dei pattern
            LOGGER.info("Bridge: Aggiornamento pattern in risposta a evento Replication");
            ICraftingProvider.requestUpdate(mainNode);
        }
    }

    // Mappa Item virtuale -> IMatterType
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