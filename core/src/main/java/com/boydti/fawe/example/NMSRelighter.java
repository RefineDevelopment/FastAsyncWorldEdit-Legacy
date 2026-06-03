package com.boydti.fawe.example;

import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.RunnableVal;
import com.boydti.fawe.util.MathMan;
import com.boydti.fawe.util.TaskManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generic scheduler for NMS-backed relighting.
 *
 * <p>This class deliberately does not perform server-version lighting itself.
 * It groups affected chunks and blocks, then hands actual light rebuilds to the
 * queue's {@link RelightDelegate}. That keeps the lighting algorithm close to
 * NMS while preserving the shared queue lifecycle in core.</p>
 */
public class NMSRelighter implements Relighter {

    private static final int DISPATCH_SIZE = 64;
    private static final int CHUNK_PACKET_MASK = 65535;

    private final NMSMappedFaweQueue queue;
    private final Map<Long, RelightChunk> chunksToRelight;
    private final Map<Long, Integer> chunksToSend;
    private final ConcurrentLinkedQueue<RelightChunk> queuedChunks;
    private final Map<Long, long[][][]> blockLightQueue;
    private final ConcurrentHashMap<Long, long[][][]> concurrentBlockLightQueue;
    private final AtomicBoolean blockLightQueueLock = new AtomicBoolean(false);
    private final int maxY;

    public NMSRelighter(NMSMappedFaweQueue queue) {
        this.queue = queue;
        this.chunksToRelight = new Long2ObjectOpenHashMap<>();
        this.chunksToSend = new Long2ObjectOpenHashMap<>();
        this.queuedChunks = new ConcurrentLinkedQueue<>();
        this.blockLightQueue = new Long2ObjectOpenHashMap<>();
        this.concurrentBlockLightQueue = new ConcurrentHashMap<>();
        this.maxY = queue.getMaxY();
    }

    @Override
    public boolean isEmpty() {
        return chunksToRelight.isEmpty()
                && queuedChunks.isEmpty()
                && chunksToSend.isEmpty()
                && blockLightQueue.isEmpty()
                && concurrentBlockLightQueue.isEmpty();
    }

    @Override
    public synchronized void removeAndRelight(boolean sky) {
        removeLighting();
        fixLightingSafe(sky);
    }

    @Override
    public boolean addChunk(int cx, int cz, byte[] skipReason, int bitmask) {
        queuedChunks.add(new RelightChunk(cx, cz, skipReason, bitmask));
        return true;
    }

    @Override
    public void addLightUpdate(int x, int y, int z) {
        if (y < 0 || y > maxY) {
            return;
        }

        long chunkKey = MathMan.pairInt(x >> 4, z >> 4);
        if (blockLightQueueLock.compareAndSet(false, true)) {
            synchronized (blockLightQueue) {
                try {
                    markBlock(blockLightQueue, chunkKey, x & 15, y, z & 15);
                } finally {
                    blockLightQueueLock.set(false);
                }
            }
        } else {
            markBlock(concurrentBlockLightQueue, chunkKey, x & 15, y, z & 15);
        }
    }

    @Override
    public synchronized void clear() {
        queuedChunks.clear();
        chunksToRelight.clear();
        chunksToSend.clear();
        blockLightQueue.clear();
        concurrentBlockLightQueue.clear();
    }

    @Override
    public synchronized void removeLighting() {
        Iterator<Map.Entry<Long, RelightChunk>> iterator = getChunkMap().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, RelightChunk> entry = iterator.next();
            RelightChunk chunk = entry.getValue();
            rememberChunkPacket(entry.getKey(), chunk.bitmask);

            queue.ensureChunkLoaded(chunk.x, chunk.z);
            Object sections = queue.getCachedSections(queue.getWorld(), chunk.x, chunk.z);
            if (sections != null) {
                queue.removeLighting(sections, FaweQueue.RelightMode.ALL, queue.hasSky());
            }
            iterator.remove();
        }
    }

    @Override
    public void fixLightingSafe(boolean sky) {
        if (isEmpty()) {
            return;
        }
        try {
            fixChunkLighting(sky);
            fixBlockLighting();
            sendChunks();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void fixBlockLighting() {
        synchronized (blockLightQueue) {
            while (!blockLightQueueLock.compareAndSet(false, true));
            try {
                mergeConcurrentBlockQueue();
                flushBlockQueue(blockLightQueue);
            } finally {
                blockLightQueueLock.set(false);
            }
        }
    }

    @Override
    public synchronized void fixSkyLighting() {
        fixChunkLighting(true);
    }

    /**
     * Flush complete chunk relights through the active NMS delegate.
     */
    public synchronized void fixChunkLighting(boolean sky) {
        Map<Long, RelightChunk> map = getChunkMap();
        ArrayList<RelightChunk> chunks = new ArrayList<>(map.size());
        Iterator<Map.Entry<Long, RelightChunk>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, RelightChunk> entry = iterator.next();
            rememberChunkPacket(entry.getKey(), entry.getValue().bitmask);
            chunks.add(entry.getValue());
            iterator.remove();
        }

        Collections.sort(chunks);
        for (int start = 0; start < chunks.size(); start += DISPATCH_SIZE) {
            int end = Math.min(chunks.size(), start + DISPATCH_SIZE);
            relightChunkBatch(chunks.subList(start, end), sky);
        }
    }

    /**
     * Send all chunks whose lighting changed.
     */
    public synchronized void sendChunks() {
        RunnableVal<Object> runnable = new RunnableVal<Object>() {
            @Override
            public void run(Object value) {
                Iterator<Map.Entry<Long, Integer>> iterator = chunksToSend.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Long, Integer> entry = iterator.next();
                    int x = MathMan.unpairIntX(entry.getKey());
                    int z = MathMan.unpairIntY(entry.getKey());
                    queue.sendChunk(x, z, entry.getValue());
                    iterator.remove();
                }
            }
        };
        if (Settings.IMP.LIGHTING.ASYNC) {
            runnable.run();
        } else {
            TaskManager.IMP.sync(runnable);
        }
    }

    private synchronized Map<Long, RelightChunk> getChunkMap() {
        RelightChunk chunk;
        while ((chunk = queuedChunks.poll()) != null) {
            long key = MathMan.pairInt(chunk.x, chunk.z);
            RelightChunk existing = chunksToRelight.put(key, chunk);
            if (existing != null) {
                chunk.merge(existing);
            }
        }
        return chunksToRelight;
    }

    private void relightChunkBatch(List<RelightChunk> chunks, boolean sky) {
        RelightDelegate delegate = queue.getRelightDelegate();
        RelightDelegate.RelightScope scope = RelightDelegate.RelightScope.of(sky, true);

        for (RelightChunk chunk : chunks) {
            if (!delegate.relightChunk(chunk.x, chunk.z, scope)) {
                Object sections = queue.getCachedSections(queue.getWorld(), chunk.x, chunk.z);
                if (sections != null) {
                    queue.removeLighting(sections, FaweQueue.RelightMode.ALL, sky);
                }
            }
            rememberChunkAndNeighbors(chunk.x, chunk.z);
        }
    }

    private void flushBlockQueue(Map<Long, long[][][]> map) {
        if (map.isEmpty()) {
            return;
        }

        RelightDelegate delegate = queue.getRelightDelegate();
        Iterator<Map.Entry<Long, long[][][]>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, long[][][]> entry = iterator.next();
            int chunkX = MathMan.unpairIntX(entry.getKey());
            int chunkZ = MathMan.unpairIntY(entry.getKey());
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            long[][][] blocks = entry.getValue();

            for (int localZ = 0; localZ < blocks.length; localZ++) {
                long[][] byX = blocks[localZ];
                if (byX == null) {
                    continue;
                }
                for (int localX = 0; localX < byX.length; localX++) {
                    long[] byY = byX[localX];
                    if (byY == null) {
                        continue;
                    }
                    flushBlockColumn(delegate, baseX + localX, baseZ + localZ, byY);
                }
            }

            rememberChunkAndNeighbors(chunkX, chunkZ);
            iterator.remove();
        }
    }

    private void flushBlockColumn(RelightDelegate delegate, int x, int z, long[] byY) {
        for (int word = 0; word < byY.length; word++) {
            long bits = byY[word];
            while (bits != 0) {
                int bit = Long.numberOfTrailingZeros(bits);
                int y = (word << 6) + bit;
                if (y <= maxY) {
                    delegate.relightBlock(x, y, z);
                }
                bits &= bits - 1;
            }
        }
    }

    private void mergeConcurrentBlockQueue() {
        if (concurrentBlockLightQueue.isEmpty()) {
            return;
        }

        for (Map.Entry<Long, long[][][]> entry : concurrentBlockLightQueue.entrySet()) {
            long[][][] target = blockLightQueue.get(entry.getKey());
            if (target == null) {
                blockLightQueue.put(entry.getKey(), entry.getValue());
            } else {
                mergeBlockBits(target, entry.getValue());
            }
        }
        concurrentBlockLightQueue.clear();
    }

    private void mergeBlockBits(long[][][] target, long[][][] source) {
        for (int z = 0; z < source.length; z++) {
            long[][] sourceX = source[z];
            if (sourceX == null) {
                continue;
            }
            long[][] targetX = target[z];
            if (targetX == null) {
                targetX = target[z] = new long[16][];
            }
            for (int x = 0; x < sourceX.length; x++) {
                long[] sourceY = sourceX[x];
                if (sourceY == null) {
                    continue;
                }
                long[] targetY = targetX[x];
                if (targetY == null) {
                    targetY = targetX[x] = new long[4];
                }
                for (int i = 0; i < sourceY.length; i++) {
                    targetY[i] |= sourceY[i];
                }
            }
        }
    }

    private void markBlock(Map<Long, long[][][]> map, long chunkKey, int x, int y, int z) {
        long[][][] blocks = map.get(chunkKey);
        if (blocks == null) {
            blocks = new long[16][][];
            map.put(chunkKey, blocks);
        }

        long[][] byX = blocks[z];
        if (byX == null) {
            byX = blocks[z] = new long[16][];
        }

        long[] byY = byX[x];
        if (byY == null) {
            byY = byX[x] = new long[4];
        }
        byY[y >> 6] |= 1L << y;
    }

    private void rememberChunkAndNeighbors(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                rememberChunkPacket(MathMan.pairInt(cx + dx, cz + dz), CHUNK_PACKET_MASK);
            }
        }
    }

    private void rememberChunkPacket(long key, int bitmask) {
        Integer existing = chunksToSend.get(key);
        chunksToSend.put(key, existing == null ? bitmask : existing | bitmask);
    }

    private class RelightChunk implements Comparable<RelightChunk> {
        private final int x;
        private final int z;
        private final byte[] skipReason;
        private int bitmask;

        private RelightChunk(int x, int z, byte[] skipReason, int bitmask) {
            this.x = x;
            this.z = z;
            this.skipReason = skipReason == null ? null : Arrays.copyOf(skipReason, skipReason.length);
            this.bitmask = bitmask;
        }

        private void merge(RelightChunk other) {
            bitmask |= other.bitmask;
            if (skipReason != null && other.skipReason != null) {
                for (int i = 0; i < skipReason.length && i < other.skipReason.length; i++) {
                    skipReason[i] &= other.skipReason[i];
                }
            }
        }

        @Override
        public int compareTo(RelightChunk other) {
            if (x != other.x) {
                return x < other.x ? -1 : 1;
            }
            if (z != other.z) {
                return z < other.z ? -1 : 1;
            }
            return 0;
        }
    }
}
