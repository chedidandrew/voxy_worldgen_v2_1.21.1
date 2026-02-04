package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
//import com.ethan.voxyworldgenv2.mixin.MinecraftServerAccess;
import com.ethan.voxyworldgenv2.mixin.ServerChunkCacheMixin;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkGenerationManager {
    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();
    
    // sets - we use primitive sets to reduce gc pressure
    private final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
    private final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
    
    // state
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicInteger remainingInRadius = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);
    
    // components
    private final TpsMonitor tpsMonitor = new TpsMonitor();
    private final DistanceGraph distanceGraph = new DistanceGraph();
    private final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
    private final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private ChunkPos lastPlayerPos = null;
    private java.util.function.BooleanSupplier pauseCheck = () -> false;
    private boolean tellusActive = false;
    private final Map<Long, com.ethan.voxyworldgenv2.integration.TellusIntegration.TellusChunkData> tellusPendingHeights = new ConcurrentHashMap<>();
    
    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    private ChunkGenerationManager() {}
    
    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
    }
    
    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);
        // unpaused by default
        this.pauseCheck = () -> false; 
        Config.load();
        this.throttle = new Semaphore(Config.DATA.maxActiveTasks);
        startWorker();
        VoxyWorldGenV2.LOGGER.info("voxy world gen initialized");
    }
    
    public void shutdown() {
        running.set(false);
        stopWorker();
        
        if (currentLevel != null && currentDimensionKey != null) {
            ChunkPersistence.save(currentLevel, currentDimensionKey, completedChunks);
        }
        
        trackedChunks.clear();
        completedChunks.clear();
        trackedBatches.clear();
        batchCounters.clear();
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        remainingInRadius.set(0);
        tpsMonitor.reset();
        currentDimensionKey = null;
        currentLevel = null;
    }

    private void startWorker() {
        if (workerRunning.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "Voxy-WorldGen-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void workerLoop() {
        while (workerRunning.get() && running.get()) {
            try {
                if (!Config.DATA.enabled || server == null || currentLevel == null) {
                    Thread.sleep(100);
                    continue;
                }

                if (tpsMonitor.isThrottled() || pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }
                
                var players = PlayerTracker.getInstance().getPlayers();
                if (players.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }
                ChunkPos center = players.iterator().next().chunkPosition();

                int radius = tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;

                // get next batch
                List<ChunkPos> batch = distanceGraph.findWork(center, radius, trackedBatches);
                
                if (batch == null) {
                    Thread.sleep(100);
                    continue;
                }
                
                long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
                batchCounters.put(batchKey, new AtomicInteger(batch.size()));

                // skip if already tracked locally
                List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
                for (ChunkPos pos : batch) {
                    long key = pos.toLong();
                    if (completedChunks.contains(key) || trackedChunks.contains(key)) {
                        onSuccess(pos);
                    } else {
                        preFiltered.add(pos);
                    }
                }

                if (preFiltered.isEmpty()) {
                    trackedBatches.remove(batchKey);
                    batchCounters.remove(batchKey);
                    continue;
                }

                // dispatch tasks
                List<ChunkPos> readyToGenerate = new ArrayList<>();
                for (ChunkPos pos : preFiltered) {
                    if (!workerRunning.get()) break;
                    
                    throttle.acquire();
                    
                    if (trackedChunks.add(pos.toLong())) {
                        activeTaskCount.incrementAndGet();
                        stats.incrementQueued();
                        
                        if (tellusActive) {
                            // sample data for tellus on worker thread
                            var data = com.ethan.voxyworldgenv2.integration.TellusIntegration.sampleData(currentLevel, pos);
                            if (data != null) {
                                tellusPendingHeights.put(pos.toLong(), data);
                            }
                        }
                        
                        readyToGenerate.add(pos);
                    } else {
                        throttle.release();
                        onFailure(pos);
                    }
                }

                if (!readyToGenerate.isEmpty()) {
                    server.execute(() -> {
                        if (currentLevel == null) {
                            for (ChunkPos p : readyToGenerate) completeTask(p);
                            return;
                        }
                        
                        ServerChunkCache cache = currentLevel.getChunkSource();
                        List<ChunkPos> actuallyGenerate = new ArrayList<>();
                        
                        for (ChunkPos pos : readyToGenerate) {
                            if (currentLevel.hasChunk(pos.x, pos.z)) {
                                onSuccess(pos);
                                completeTask(pos);
                            } else if (tellusActive) {
                                // fast path for tellus
                                var data = tellusPendingHeights.remove(pos.toLong());
                                if (data != null) {
                                    com.ethan.voxyworldgenv2.integration.TellusIntegration.generateFromHeights(currentLevel, pos, data);
                                    onSuccess(pos);
                                } else {
                                    onFailure(pos);
                                }
                                completeTask(pos);
                            } else {
                                cache.addRegionTicket(TicketType.FORCED, pos, 0, pos);
                                actuallyGenerate.add(pos);
                            }
                        }
                        
                        if (!actuallyGenerate.isEmpty()) {
                            ((ServerChunkCacheMixin) cache).invokeRunDistanceManagerUpdates();
                            
                            for (ChunkPos pos : actuallyGenerate) {
                                ((ServerChunkCacheMixin) cache).invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                                    .whenCompleteAsync((result, throwable) -> {
                                        if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                            onSuccess(pos);
                                            if (!chunk.isEmpty()) {
                                                VoxyIntegration.ingestChunk(chunk);
                                            }
                                        } else {
                                            onFailure(pos);
                                        }
                                        server.execute(() -> cleanupTask(cache, pos));
                                    }, server);
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    public void tick() {
        if (!running.get() || server == null) return;
        
        if (configReloadScheduled.compareAndSet(true, false)) {
            Config.load();
            updateThrottleCapacity();
            var players = PlayerTracker.getInstance().getPlayers();
            if (!players.isEmpty()) {
                restartScan(players.iterator().next().chunkPosition());
            }
        }
        
        tpsMonitor.tick();
        stats.tick();
        checkPlayerMovement();
    }
    
    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;

        ServerPlayer player = players.iterator().next();
        ChunkPos currentPos = player.chunkPosition();
        
        if (player.level() != currentLevel) {
             setupLevel((ServerLevel) player.level());
        }

        // rescan if player moved significantly
        if (lastPlayerPos == null || distSq(lastPlayerPos, currentPos) >= 4) {
            lastPlayerPos = currentPos;
            restartScan(currentPos);
        }
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return (double) dx * dx + dz * dz;
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            ChunkPersistence.save(currentLevel, currentDimensionKey, completedChunks);
        }
        
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        tellusActive = com.ethan.voxyworldgenv2.integration.TellusIntegration.isTellusWorld(newLevel);
        
        if (tellusActive) {
            VoxyWorldGenV2.LOGGER.info("tellus world detected, enabling fast generation");
        }
        
        completedChunks.clear();
        trackedChunks.clear();
        trackedBatches.clear();
        batchCounters.clear();
        ChunkPersistence.load(newLevel, currentDimensionKey, completedChunks);
        
        for (long pos : completedChunks) {
            distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
        }
        
        var players = PlayerTracker.getInstance().getPlayers();
        if (!players.isEmpty()) {
            restartScan(players.iterator().next().chunkPosition());
        }
    }
    
    private void restartScan(ChunkPos center) {
        int radius = tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
        remainingInRadius.set(distanceGraph.countMissingInRange(center, radius));
    }

    private void updateThrottleCapacity() {
        int target = Config.DATA.maxActiveTasks;
        int current = throttle.availablePermits() + activeTaskCount.get();
        if (current != target) {
             int diff = target - current;
             if (diff > 0) throttle.release(diff);
             else throttle.tryAcquire(-diff);
        }
    }
    
    private void cleanupTask(ServerChunkCache cache, ChunkPos pos) {
        server.execute(() -> {
            cache.removeRegionTicket(TicketType.FORCED, pos, 0, pos);
            ((MinecraftServerExtension) server).voxyworldgen$markHousekeeping();
            //((MinecraftServerAccess) server).setEmptyTicks(0);
            completeTask(pos);
        });
    }
    
    private void onSuccess(ChunkPos pos) {
        long key = pos.toLong();
        if (completedChunks.add(key)) {
            stats.incrementCompleted();
            distanceGraph.markChunkCompleted(pos.x, pos.z);
            remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
        }
        decrementBatch(pos);
    }
    
    private void onFailure(ChunkPos pos) {
        stats.incrementFailed();
        remainingInRadius.decrementAndGet();
        decrementBatch(pos);
    }

    private void decrementBatch(ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(pos.x, pos.z);
        AtomicInteger counter = batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            trackedBatches.remove(batchKey);
            batchCounters.remove(batchKey);
        }
    }
    
    private void completeTask(ChunkPos pos) {
        if (trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }
    
    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() { return remainingInRadius.get(); }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; }
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
