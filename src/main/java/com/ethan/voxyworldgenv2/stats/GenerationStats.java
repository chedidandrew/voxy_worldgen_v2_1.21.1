package com.ethan.voxyworldgenv2.stats;

import java.util.concurrent.atomic.AtomicLong;

public class GenerationStats {
    private final AtomicLong chunksQueued = new AtomicLong(0);
    private final AtomicLong chunksCompleted = new AtomicLong(0);
    private final AtomicLong chunksFailed = new AtomicLong(0);
    private final AtomicLong chunksSkipped = new AtomicLong(0);
    
    // rolling average over 10s
    private final long[] rollingHistory = new long[10];
    private int historyIndex = 0;
    private long lastCompletedCount = 0;
    private long lastTickTime = 0;

    public void incrementQueued() { chunksQueued.incrementAndGet(); }
    public void incrementCompleted() { chunksCompleted.incrementAndGet(); }
    public void incrementFailed() { chunksFailed.incrementAndGet(); }
    public void incrementSkipped() { chunksSkipped.incrementAndGet(); }
    
    public long getQueued() { return chunksQueued.get(); }
    public long getCompleted() { return chunksCompleted.get(); }
    public long getFailed() { return chunksFailed.get(); }
    public long getSkipped() { return chunksSkipped.get(); }
    
    // update rolling average, call every tick
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        // shift history once per second
        if (now - lastTickTime >= 1000) {
            long currentTotal = chunksCompleted.get() + chunksSkipped.get();
            long delta = currentTotal - lastCompletedCount;
            
            rollingHistory[historyIndex] = delta;
            historyIndex = (historyIndex + 1) % rollingHistory.length;
            
            lastCompletedCount = currentTotal;
            lastTickTime = now;
        }
    }

    public synchronized double getChunksPerSecond() {
        long sum = 0;
        for (long val : rollingHistory) {
            sum += val;
        }
        return sum / 10.0;
    }
    
    public void reset() {
        chunksQueued.set(0);
        chunksCompleted.set(0);
        chunksFailed.set(0);
        chunksSkipped.set(0);
        synchronized (this) {
            for (int i = 0; i < rollingHistory.length; i++) rollingHistory[i] = 0;
            lastCompletedCount = 0;
            lastTickTime = System.currentTimeMillis();
        }
    }
}
