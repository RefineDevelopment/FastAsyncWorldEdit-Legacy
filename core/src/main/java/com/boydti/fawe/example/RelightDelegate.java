package com.boydti.fawe.example;

/**
 * Version-specific lighting bridge used by the generic FAWE relighter.
 *
 * <p>The core module only decides which chunks or blocks need lighting work.
 * Implementations of this delegate own the server internals needed to rebuild
 * that light efficiently.</p>
 */
public interface RelightDelegate {

    RelightDelegate NONE = new RelightDelegate() {
        @Override
        public boolean relightChunk(int cx, int cz, RelightScope scope) {
            return false;
        }

        @Override
        public boolean relightBlock(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean relightSky(int x, int y, int z) {
            return false;
        }
    };

    /**
     * Rebuild the requested light channels for a complete chunk.
     *
     * @param cx chunk X
     * @param cz chunk Z
     * @param scope light channels to rebuild
     * @return true if the delegate rebuilt the chunk
     */
    boolean relightChunk(int cx, int cz, RelightScope scope);

    /**
     * Update block light after one changed block.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return true when the update was applied, false when its loaded chunk
     *     neighborhood is not ready and the caller should retry it later
     */
    boolean relightBlock(int x, int y, int z);

    /**
     * Update sky light after one changed block.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return true when the update was applied, false when its loaded chunk
     *     neighborhood is not ready and the caller should retry it later
     */
    boolean relightSky(int x, int y, int z);

    /**
     * Channels that can be rebuilt by a relight delegate.
     */
    enum RelightScope {
        SKY(true, false),
        BLOCK(false, true),
        ALL(true, true);

        private final boolean sky;
        private final boolean block;

        RelightScope(boolean sky, boolean block) {
            this.sky = sky;
            this.block = block;
        }

        public boolean hasSky() {
            return sky;
        }

        public boolean hasBlock() {
            return block;
        }

        public static RelightScope of(boolean sky, boolean block) {
            if (sky && block) {
                return ALL;
            }
            return sky ? SKY : BLOCK;
        }
    }
}
