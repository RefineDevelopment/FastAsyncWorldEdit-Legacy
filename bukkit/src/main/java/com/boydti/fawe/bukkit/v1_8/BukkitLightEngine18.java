package com.boydti.fawe.bukkit.v1_8;

import com.boydti.fawe.FaweCache;
import com.boydti.fawe.example.RelightDelegate;
import java.util.Arrays;
import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.NibbleArray;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.World;

/**
 * Minecraft 1.8 lighting delegate used by FAWE's generic relight queue.
 *
 * <p>The implementation rebuilds the center chunk from a padded, cached 3x3
 * chunk view. Sky light is seeded from height-map columns and block light from
 * emitting blocks, then both channels are propagated through bucketed light
 * queues before writing only the center chunk back to NMS.</p>
 */
final class BukkitLightEngine18 implements RelightDelegate {

    private final BukkitQueue18R3 queue;
    private final ChunkLightRebuilder rebuilder = new ChunkLightRebuilder();
    private final SingleBlockPropagator propagator = new SingleBlockPropagator();

    BukkitLightEngine18(BukkitQueue18R3 queue) {
        this.queue = queue;
    }

    @Override
    public synchronized boolean relightChunk(int cx, int cz, RelightScope scope) {
        World world = queue.getWorld();
        if (world == null) {
            return false;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                queue.loadChunk(world, cx + dx, cz + dz, true);
            }
        }

        Chunk chunk = queue.getCachedChunk(world, cx, cz);
        if (chunk == null) {
            chunk = queue.loadChunk(world, cx, cz, true);
        }
        return chunk != null && rebuilder.rebuild((WorldServer) chunk.getWorld(), chunk, scope);
    }

    @Override
    public synchronized void relightBlock(int x, int y, int z) {
        propagator.propagate(queue, LightChannel.BLOCK, x, y, z);
    }

    @Override
    public synchronized void relightSky(int x, int y, int z) {
        propagator.propagate(queue, LightChannel.SKY, x, y, z);
    }

    /**
     * Rebuilds chunk light using reusable NMS scratch holders.
     */
    private static final class ChunkLightRebuilder {
        private final BlockStateLightCache stateLightCache = new BlockStateLightCache();
        private final PaddedLightVolume volume = new PaddedLightVolume();

        private boolean rebuild(WorldServer world, Chunk center, RelightScope scope) {
            volume.window.capture(world, center);
            if (scope.hasSky() && !world.worldProvider.o() && !volume.window.isComplete()) {
                volume.window.clear();
                return false;
            }

            if (scope.hasSky() && !world.worldProvider.o()) {
                rebuildSky(center);
            }
            if (scope.hasBlock()) {
                rebuildBlock(center);
            }

            volume.window.clear();
            return true;
        }

        private void rebuildSky(Chunk center) {
            volume.reset();
            cacheOpacity();
            seedSkyColumns();
            spreadQueuedLight();
            clearLight(center, LightChannel.SKY);
            writeCenter(center, LightChannel.SKY);
        }

        private void rebuildBlock(Chunk center) {
            volume.reset();
            cacheOpacityAndBlockSources();
            spreadQueuedLight();
            clearLight(center, LightChannel.BLOCK);
            writeCenter(center, LightChannel.BLOCK);
        }

        private void cacheOpacity() {
            for (int paddedX = 0; paddedX < PaddedLightVolume.WIDTH; paddedX++) {
                for (int paddedZ = 0; paddedZ < PaddedLightVolume.WIDTH; paddedZ++) {
                    Chunk chunk = volume.window.chunkAt(paddedX, paddedZ);
                    int base = volume.columnBase(paddedX, paddedZ);
                    if (chunk == null) {
                        volume.fillColumnOpacity(base, 15);
                    } else {
                        copyColumnOpacity(chunk, paddedX, paddedZ, base, false);
                    }
                }
            }
        }

        private void cacheOpacityAndBlockSources() {
            for (int paddedX = 0; paddedX < PaddedLightVolume.WIDTH; paddedX++) {
                for (int paddedZ = 0; paddedZ < PaddedLightVolume.WIDTH; paddedZ++) {
                    Chunk chunk = volume.window.chunkAt(paddedX, paddedZ);
                    int base = volume.columnBase(paddedX, paddedZ);
                    if (chunk == null) {
                        volume.fillColumnOpacity(base, 15);
                    } else {
                        copyColumnOpacity(chunk, paddedX, paddedZ, base, true);
                    }
                }
            }
        }

        private void copyColumnOpacity(Chunk chunk, int paddedX, int paddedZ, int base, boolean queueSources) {
            int localX = volume.mapping.localX[paddedX];
            int localZ = volume.mapping.localZ[paddedZ];
            int columnIndex = (localZ << 4) | localX;
            ChunkSection[] sections = chunk.getSections();

            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                ChunkSection section = sections[sectionIndex];
                if (section == null) {
                    continue;
                }

                char[] ids = section.getIdArray();
                int sectionY = sectionIndex << 4;
                for (int localY = 0; localY < 16; localY++) {
                    int stateId = ids[(localY << 8) | columnIndex];
                    int index = base | (sectionY + localY);
                    volume.opacity[index] = (byte) stateLightCache.opacity(stateId);
                    if (queueSources) {
                        int emission = stateLightCache.emission(stateId);
                        if (emission > 0) {
                            volume.light[index] = (byte) emission;
                            volume.queue.add(emission, index);
                        }
                    }
                }
            }
        }

        private void seedSkyColumns() {
            for (int paddedX = 0; paddedX < PaddedLightVolume.WIDTH; paddedX++) {
                for (int paddedZ = 0; paddedZ < PaddedLightVolume.WIDTH; paddedZ++) {
                    Chunk chunk = volume.window.chunkAt(paddedX, paddedZ);
                    int localX = volume.mapping.localX[paddedX];
                    int localZ = volume.mapping.localZ[paddedZ];
                    int skyTop = chunk.heightMap[(localZ << 4) | localX];
                    int base = volume.columnBase(paddedX, paddedZ);

                    for (int y = skyTop; y < PaddedLightVolume.HEIGHT; y++) {
                        volume.light[base | y] = 15;
                    }

                    int queueTop = skySeedLimit(paddedX, paddedZ, skyTop);
                    for (int y = skyTop; y < queueTop; y++) {
                        volume.queue.add(15, base | y);
                    }
                }
            }
        }

        private int skySeedLimit(int paddedX, int paddedZ, int skyTop) {
            if (paddedX == 0 || paddedX == PaddedLightVolume.WIDTH - 1
                    || paddedZ == 0 || paddedZ == PaddedLightVolume.WIDTH - 1) {
                return PaddedLightVolume.HEIGHT;
            }

            int limit = skyTop + 1;
            limit = Math.max(limit, skyHeight(paddedX - 1, paddedZ));
            limit = Math.max(limit, skyHeight(paddedX + 1, paddedZ));
            limit = Math.max(limit, skyHeight(paddedX, paddedZ - 1));
            limit = Math.max(limit, skyHeight(paddedX, paddedZ + 1));
            return Math.min(limit, PaddedLightVolume.HEIGHT);
        }

        private int skyHeight(int paddedX, int paddedZ) {
            Chunk chunk = volume.window.chunkAt(paddedX, paddedZ);
            int localX = volume.mapping.localX[paddedX];
            int localZ = volume.mapping.localZ[paddedZ];
            return chunk.heightMap[(localZ << 4) | localX];
        }

        private void spreadQueuedLight() {
            for (int level = 15; level > 1; level--) {
                int size = volume.queue.size(level);
                int[] bucket = volume.queue.bucket(level);
                for (int head = 0; head < size; head++) {
                    int index = bucket[head];
                    if ((volume.light[index] & 15) != level) {
                        continue;
                    }
                    spreadFrom(index, level);
                }
            }
        }

        private void spreadFrom(int index, int level) {
            int y = index & 255;
            int column = index >> 8;

            if (volume.mapping.hasNegativeX[column]) offer(index - PaddedLightVolume.X_STEP, level);
            if (volume.mapping.hasPositiveX[column]) offer(index + PaddedLightVolume.X_STEP, level);
            if (y > 0) offer(index - 1, level);
            if (y < PaddedLightVolume.HEIGHT - 1) offer(index + 1, level);
            if (volume.mapping.hasNegativeZ[column]) offer(index - PaddedLightVolume.Z_STEP, level);
            if (volume.mapping.hasPositiveZ[column]) offer(index + PaddedLightVolume.Z_STEP, level);
        }

        private void offer(int index, int level) {
            int next = level - (volume.opacity[index] & 0xFF);
            if (next <= 0 || (volume.light[index] & 15) >= next) {
                return;
            }
            volume.light[index] = (byte) next;
            volume.queue.add(next, index);
        }

        private void clearLight(Chunk chunk, LightChannel channel) {
            for (ChunkSection section : chunk.getSections()) {
                if (section == null) {
                    continue;
                }
                if (channel == LightChannel.SKY) {
                    section.b(new NibbleArray());
                } else {
                    section.a(new NibbleArray());
                }
            }
        }

        private void writeCenter(Chunk chunk, LightChannel channel) {
            ChunkSection[] sections = chunk.getSections();
            boolean hasSky = !chunk.getWorld().worldProvider.o();

            for (int localX = 0; localX < 16; localX++) {
                int paddedX = localX + PaddedLightVolume.RADIUS;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int paddedZ = localZ + PaddedLightVolume.RADIUS;
                    int skyTop = chunk.heightMap[(localZ << 4) | localX];
                    int base = volume.columnBase(paddedX, paddedZ);
                    writeColumn(chunk, sections, channel, hasSky, localX, localZ, skyTop, base);
                }
            }
        }

        private void writeColumn(Chunk chunk, ChunkSection[] sections, LightChannel channel, boolean hasSky,
                                 int localX, int localZ, int skyTop, int base) {
            for (int y = 0; y < PaddedLightVolume.HEIGHT; y++) {
                int light = volume.light[base | y] & 15;
                if (light == 0) {
                    continue;
                }

                int sectionIndex = y >> 4;
                ChunkSection section = sections[sectionIndex];
                if (section == null) {
                    if (channel == LightChannel.SKY && y >= skyTop) {
                        continue;
                    }
                    section = sections[sectionIndex] = new ChunkSection(sectionIndex << 4, hasSky);
                    if (channel == LightChannel.BLOCK && hasSky) {
                        fillCreatedSectionSky(chunk, section, sectionIndex);
                    }
                }

                if (channel == LightChannel.SKY) {
                    section.a(localX, y & 15, localZ, light);
                } else {
                    section.b(localX, y & 15, localZ, light);
                }
            }
        }

        private void fillCreatedSectionSky(Chunk chunk, ChunkSection section, int sectionIndex) {
            int sectionY = sectionIndex << 4;
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int skyTop = chunk.heightMap[(localZ << 4) | localX];
                    for (int localY = 0; localY < 16; localY++) {
                        if (sectionY + localY >= skyTop) {
                            section.a(localX, localY, localZ, 15);
                        }
                    }
                }
            }
        }
    }

    /**
     * Local single-block propagation engine.
     *
     * <p>Rather than calling {@code WorldServer#updateLight}, this recomputes the
     * expected light at each changed position and relaxes neighboring positions
     * until the local area is stable. A 5x5 chunk window is cached because a
     * one-block change can affect light up to 15 blocks away and may cross two
     * chunk borders when it starts near an edge.</p>
     */
    private static final class SingleBlockPropagator {
        private static final int MAX_DISTANCE = 17;
        private static final int QUEUE_CAPACITY = 32768;

        private final BlockStateLightCache stateLightCache = new BlockStateLightCache();
        private final PropagationWindow window = new PropagationWindow();
        private final int[] queue = new int[QUEUE_CAPACITY];
        private int originX;
        private int originY;
        private int originZ;
        private int tail;

        private void propagate(BukkitQueue18R3 queue, LightChannel channel, int x, int y, int z) {
            if (y < 0 || y >= PaddedLightVolume.HEIGHT) {
                return;
            }

            World bukkitWorld = queue.getWorld();
            if (bukkitWorld == null) {
                return;
            }

            int centerChunkX = x >> 4;
            int centerChunkZ = z >> 4;
            loadWindow(queue, bukkitWorld, centerChunkX, centerChunkZ);

            Chunk center = queue.getCachedChunk(bukkitWorld, centerChunkX, centerChunkZ);
            if (center == null) {
                window.clear();
                return;
            }

            WorldServer world = (WorldServer) center.getWorld();
            if (channel == LightChannel.SKY && world.worldProvider.o()) {
                window.clear();
                return;
            }

            window.capture(world, centerChunkX, centerChunkZ);
            originX = x;
            originY = y;
            originZ = z;
            seed(channel, x, y, z);
            if (tail == 0) {
                window.clear();
                return;
            }

            drain(channel);
            window.clear();
        }

        private void loadWindow(BukkitQueue18R3 queue, World world, int centerChunkX, int centerChunkZ) {
            for (int dx = -PropagationWindow.RADIUS; dx <= PropagationWindow.RADIUS; dx++) {
                for (int dz = -PropagationWindow.RADIUS; dz <= PropagationWindow.RADIUS; dz++) {
                    queue.loadChunk(world, centerChunkX + dx, centerChunkZ + dz, true);
                }
            }
        }

        private int seed(LightChannel channel, int x, int y, int z) {
            tail = 0;
            int current = light(channel, x, y, z);
            int expected = expectedLight(channel, x, y, z);
            if (expected > current) {
                offer(x, y, z);
                return 0;
            }
            if (expected < current) {
                offer(x, y, z, current);
                return drainDecreases(channel);
            }
            return 0;
        }

        private int drainDecreases(LightChannel channel) {
            int head = 0;
            while (head < tail) {
                int packed = queue[head++];
                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);
                int level = unpackLight(packed);

                if (light(channel, x, y, z) == level) {
                    setLight(channel, x, y, z, 0);
                    if (level > 0 && distance(x, y, z) < MAX_DISTANCE) {
                        offerDependentNeighbors(channel, x, y, z, level);
                    }
                }
            }
            return 0;
        }

        private void drain(LightChannel channel) {
            int head = 0;
            while (head < tail) {
                int packed = queue[head++];
                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);

                int current = light(channel, x, y, z);
                int expected = expectedLight(channel, x, y, z);
                if (expected != current) {
                    setLight(channel, x, y, z, expected);
                    if (expected > current && distance(x, y, z) < MAX_DISTANCE) {
                        offerDimmerNeighbors(channel, x, y, z, expected);
                    }
                }
            }
        }

        private int expectedLight(LightChannel channel, int x, int y, int z) {
            if (y < 0 || y >= PaddedLightVolume.HEIGHT) {
                return channel == LightChannel.SKY && y >= PaddedLightVolume.HEIGHT && !window.isNether() ? 15 : 0;
            }

            if (channel == LightChannel.SKY) {
                return expectedSkyLight(x, y, z);
            }
            return expectedBlockLight(x, y, z);
        }

        private int expectedSkyLight(int x, int y, int z) {
            if (window.isNether()) {
                return 0;
            }

            Chunk chunk = window.chunkAtBlock(x, z);
            if (chunk == null) {
                return strongestNeighbor(LightChannel.SKY, x, y, z, 1, 0);
            }

            int localX = x & 15;
            int localZ = z & 15;
            if (y >= chunk.heightMap[(localZ << 4) | localX]) {
                return 15;
            }

            int opacity = opacity(x, y, z);
            if (opacity >= 15) {
                return 0;
            }
            return strongestNeighbor(LightChannel.SKY, x, y, z, opacity, 0);
        }

        private int expectedBlockLight(int x, int y, int z) {
            int source = emission(x, y, z);
            if (source >= 15) {
                return source;
            }

            int opacity = opacity(x, y, z);
            if (opacity >= 15 && source == 0) {
                return 0;
            }
            return strongestNeighbor(LightChannel.BLOCK, x, y, z, opacity, source);
        }

        private int strongestNeighbor(LightChannel channel, int x, int y, int z, int opacity, int base) {
            int light = base;
            light = Math.max(light, light(channel, x - 1, y, z) - opacity);
            light = Math.max(light, light(channel, x + 1, y, z) - opacity);
            light = Math.max(light, light(channel, x, y - 1, z) - opacity);
            light = Math.max(light, light(channel, x, y + 1, z) - opacity);
            light = Math.max(light, light(channel, x, y, z - 1) - opacity);
            light = Math.max(light, light(channel, x, y, z + 1) - opacity);
            return Math.max(0, Math.min(15, light));
        }

        private int light(LightChannel channel, int x, int y, int z) {
            if (y < 0) {
                y = 0;
            }
            if (y >= PaddedLightVolume.HEIGHT) {
                return channel == LightChannel.SKY ? 15 : 0;
            }

            Chunk chunk = window.chunkAtBlock(x, z);
            if (chunk == null) {
                return channel == LightChannel.SKY ? 15 : 0;
            }

            ChunkSection section = chunk.getSections()[y >> 4];
            if (section == null) {
                if (channel == LightChannel.SKY && !window.isNether()) {
                    int column = ((z & 15) << 4) | (x & 15);
                    return y >= chunk.heightMap[column] ? 15 : 0;
                }
                return 0;
            }

            if (channel == LightChannel.SKY) {
                return section.d(x & 15, y & 15, z & 15);
            }
            return section.e(x & 15, y & 15, z & 15);
        }

        private void setLight(LightChannel channel, int x, int y, int z, int value) {
            if (y < 0 || y >= PaddedLightVolume.HEIGHT) {
                return;
            }

            Chunk chunk = window.chunkAtBlock(x, z);
            if (chunk == null) {
                return;
            }

            ChunkSection[] sections = chunk.getSections();
            int sectionIndex = y >> 4;
            ChunkSection section = sections[sectionIndex];
            if (section == null) {
                if (value == 0) {
                    return;
                }
                section = sections[sectionIndex] = new ChunkSection(sectionIndex << 4, !window.isNether());
                if (channel == LightChannel.BLOCK && !window.isNether()) {
                    fillCreatedSectionSky(chunk, section, sectionIndex);
                }
            }

            if (channel == LightChannel.SKY) {
                section.a(x & 15, y & 15, z & 15, value);
            } else {
                section.b(x & 15, y & 15, z & 15, value);
            }
        }

        private void fillCreatedSectionSky(Chunk chunk, ChunkSection section, int sectionIndex) {
            int sectionY = sectionIndex << 4;
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int skyTop = chunk.heightMap[(localZ << 4) | localX];
                    for (int localY = 0; localY < 16; localY++) {
                        if (sectionY + localY >= skyTop) {
                            section.a(localX, localY, localZ, 15);
                        }
                    }
                }
            }
        }

        private int opacity(int x, int y, int z) {
            return stateLightCache.opacity(stateId(x, y, z));
        }

        private int emission(int x, int y, int z) {
            return stateLightCache.emission(stateId(x, y, z));
        }

        private int stateId(int x, int y, int z) {
            if (y < 0 || y >= PaddedLightVolume.HEIGHT) {
                return 0;
            }

            Chunk chunk = window.chunkAtBlock(x, z);
            if (chunk == null) {
                return 0;
            }

            ChunkSection section = chunk.getSections()[y >> 4];
            if (section == null) {
                return 0;
            }

            int localX = x & 15;
            int localY = y & 15;
            int localZ = z & 15;
            return section.getIdArray()[(localY << 8) | (localZ << 4) | localX];
        }

        private void offerDependentNeighbors(LightChannel channel, int x, int y, int z, int level) {
            offerDependent(channel, x - 1, y, z, level);
            offerDependent(channel, x + 1, y, z, level);
            offerDependent(channel, x, y - 1, z, level);
            offerDependent(channel, x, y + 1, z, level);
            offerDependent(channel, x, y, z - 1, level);
            offerDependent(channel, x, y, z + 1, level);
        }

        private void offerDependent(LightChannel channel, int x, int y, int z, int level) {
            int next = level - opacity(x, y, z);
            if (next >= 0 && light(channel, x, y, z) == next) {
                offer(x, y, z, next);
            }
        }

        private void offerDimmerNeighbors(LightChannel channel, int x, int y, int z, int level) {
            offerDimmer(channel, x - 1, y, z, level);
            offerDimmer(channel, x + 1, y, z, level);
            offerDimmer(channel, x, y - 1, z, level);
            offerDimmer(channel, x, y + 1, z, level);
            offerDimmer(channel, x, y, z - 1, level);
            offerDimmer(channel, x, y, z + 1, level);
        }

        private void offerDimmer(LightChannel channel, int x, int y, int z, int level) {
            if (tail < queue.length - 6 && light(channel, x, y, z) < level) {
                offer(x, y, z);
            }
        }

        private void offer(int x, int y, int z) {
            offer(x, y, z, 0);
        }

        private void offer(int x, int y, int z, int level) {
            if (tail >= queue.length || distance(x, y, z) > MAX_DISTANCE) {
                return;
            }
            if (x - originX < -32 || x - originX > 31
                    || y - originY < -32 || y - originY > 31
                    || z - originZ < -32 || z - originZ > 31) {
                return;
            }
            queue[tail++] = pack(x, y, z, level);
        }

        private int distance(int x, int y, int z) {
            return Math.abs(x - originX) + Math.abs(y - originY) + Math.abs(z - originZ);
        }

        private int pack(int x, int y, int z, int level) {
            return (x - originX + 32)
                    | ((y - originY + 32) << 6)
                    | ((z - originZ + 32) << 12)
                    | ((level & 15) << 18);
        }

        private int unpackX(int packed) {
            return originX + (packed & 63) - 32;
        }

        private int unpackY(int packed) {
            return originY + ((packed >> 6) & 63) - 32;
        }

        private int unpackZ(int packed) {
            return originZ + ((packed >> 12) & 63) - 32;
        }

        private int unpackLight(int packed) {
            return (packed >> 18) & 15;
        }
    }

    /**
     * Scratch volume for a 46x256x46 padded relight area.
     */
    private static final class PaddedLightVolume {
        private static final int HEIGHT = 256;
        private static final int RADIUS = 15;
        private static final int WIDTH = 16 + RADIUS * 2;
        private static final int X_STEP = WIDTH << 8;
        private static final int Z_STEP = 1 << 8;
        private static final int SIZE = WIDTH * HEIGHT * WIDTH;

        private final byte[] light = new byte[SIZE];
        private final byte[] opacity = new byte[SIZE];
        private final GridMapping mapping = new GridMapping();
        private final LightBuckets queue = new LightBuckets();
        private final ChunkWindow window = new ChunkWindow(mapping);

        private void reset() {
            Arrays.fill(light, (byte) 0);
            Arrays.fill(opacity, (byte) 1);
            queue.clear();
        }

        private int columnBase(int paddedX, int paddedZ) {
            return ((paddedX * WIDTH) + paddedZ) << 8;
        }

        private void fillColumnOpacity(int base, int value) {
            Arrays.fill(opacity, base, base + HEIGHT, (byte) value);
        }
    }

    /**
     * Lookup tables that translate padded coordinates to NMS chunk columns.
     */
    private static final class GridMapping {
        private final int[] chunkX = new int[PaddedLightVolume.WIDTH];
        private final int[] chunkZ = new int[PaddedLightVolume.WIDTH];
        private final int[] localX = new int[PaddedLightVolume.WIDTH];
        private final int[] localZ = new int[PaddedLightVolume.WIDTH];
        private final boolean[] hasNegativeX = new boolean[PaddedLightVolume.WIDTH * PaddedLightVolume.WIDTH];
        private final boolean[] hasPositiveX = new boolean[PaddedLightVolume.WIDTH * PaddedLightVolume.WIDTH];
        private final boolean[] hasNegativeZ = new boolean[PaddedLightVolume.WIDTH * PaddedLightVolume.WIDTH];
        private final boolean[] hasPositiveZ = new boolean[PaddedLightVolume.WIDTH * PaddedLightVolume.WIDTH];

        private GridMapping() {
            for (int x = 0; x < PaddedLightVolume.WIDTH; x++) {
                chunkX[x] = (x + 1) >> 4;
                localX[x] = (x + 1) & 15;
                for (int z = 0; z < PaddedLightVolume.WIDTH; z++) {
                    if (x == 0) {
                        chunkZ[z] = (z + 1) >> 4;
                        localZ[z] = (z + 1) & 15;
                    }
                    int column = x * PaddedLightVolume.WIDTH + z;
                    hasNegativeX[column] = x > 0;
                    hasPositiveX[column] = x < PaddedLightVolume.WIDTH - 1;
                    hasNegativeZ[column] = z > 0;
                    hasPositiveZ[column] = z < PaddedLightVolume.WIDTH - 1;
                }
            }
        }
    }

    /**
     * Holds the 3x3 chunks that feed light into the center chunk.
     */
    private static final class ChunkWindow {
        private static final int WIDTH = 3;

        private final GridMapping mapping;
        private final Chunk[] chunks = new Chunk[WIDTH * WIDTH];

        private ChunkWindow(GridMapping mapping) {
            this.mapping = mapping;
        }

        private void capture(WorldServer world, Chunk center) {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < WIDTH; z++) {
                    chunks[x * WIDTH + z] =
                            world.chunkProviderServer.getChunkIfLoaded(center.locX + x - 1, center.locZ + z - 1);
                }
            }
        }

        private Chunk chunkAt(int paddedX, int paddedZ) {
            return chunks[mapping.chunkX[paddedX] * WIDTH + mapping.chunkZ[paddedZ]];
        }

        private boolean isComplete() {
            for (Chunk chunk : chunks) {
                if (chunk == null) {
                    return false;
                }
            }
            return true;
        }

        private void clear() {
            Arrays.fill(chunks, null);
        }
    }

    /**
     * Cached 5x5 chunk view for single-block propagation.
     */
    private static final class PropagationWindow {
        private static final int RADIUS = 2;
        private static final int WIDTH = RADIUS * 2 + 1;

        private final Chunk[] chunks = new Chunk[WIDTH * WIDTH];
        private int centerChunkX;
        private int centerChunkZ;
        private boolean nether;

        private void capture(WorldServer world, int centerChunkX, int centerChunkZ) {
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.nether = world.worldProvider.o();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    chunks[(dx + RADIUS) * WIDTH + dz + RADIUS] =
                            world.chunkProviderServer.getChunkIfLoaded(centerChunkX + dx, centerChunkZ + dz);
                }
            }
        }

        private Chunk chunkAtBlock(int x, int z) {
            int dx = (x >> 4) - centerChunkX;
            int dz = (z >> 4) - centerChunkZ;
            if (dx < -RADIUS || dx > RADIUS || dz < -RADIUS || dz > RADIUS) {
                return null;
            }
            return chunks[(dx + RADIUS) * WIDTH + dz + RADIUS];
        }

        private boolean isNether() {
            return nether;
        }

        private void clear() {
            Arrays.fill(chunks, null);
        }
    }

    /**
     * Bucket queue keyed by light level, avoiding per-node objects.
     */
    private static final class LightBuckets {
        private final int[][] buckets = new int[16][];
        private final int[] sizes = new int[16];

        private LightBuckets() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new int[4096];
            }
        }

        private void clear() {
            Arrays.fill(sizes, 0);
        }

        private void add(int level, int index) {
            int[] bucket = buckets[level];
            int size = sizes[level];
            if (size == bucket.length) {
                bucket = buckets[level] = Arrays.copyOf(bucket, bucket.length << 1);
            }
            bucket[size] = index;
            sizes[level] = size + 1;
        }

        private int[] bucket(int level) {
            return buckets[level];
        }

        private int size(int level) {
            return sizes[level];
        }
    }

    /**
     * Caches light opacity and emission for 1.8 combined block-state ids.
     */
    private static final class BlockStateLightCache {
        private static final int STATE_CACHE_SIZE = 65536;

        private final byte[] opacity = new byte[STATE_CACHE_SIZE];
        private final byte[] emission = new byte[STATE_CACHE_SIZE];
        private final boolean[] loaded = new boolean[STATE_CACHE_SIZE];

        private int opacity(int stateId) {
            load(stateId);
            return opacity[stateId & 0xFFFF] & 0xFF;
        }

        private int emission(int stateId) {
            load(stateId);
            return emission[stateId & 0xFFFF] & 0xFF;
        }

        private void load(int stateId) {
            int key = stateId & 0xFFFF;
            if (loaded[key]) {
                return;
            }

            int blockEmission = 0;
            int blockOpacity = 1;
            if (stateId != 0) {
                Block block = Block.getById(FaweCache.getId(stateId));
                if (block != null) {
                    blockEmission = block.r() & 15;
                    blockOpacity = block.p();
                }
            }

            if (blockEmission > 0 && blockOpacity >= 15) {
                blockOpacity = 1;
            } else if (blockOpacity < 1) {
                blockOpacity = 1;
            } else if (blockOpacity > 15) {
                blockOpacity = 15;
            }

            opacity[key] = (byte) blockOpacity;
            emission[key] = (byte) blockEmission;
            loaded[key] = true;
        }
    }

    private enum LightChannel {
        SKY,
        BLOCK
    }
}
