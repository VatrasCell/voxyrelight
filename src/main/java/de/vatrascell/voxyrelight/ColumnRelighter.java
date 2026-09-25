package de.vatrascell.voxyrelight;

import me.cortex.voxy.common.world.other.Mapper;

/**
 * Pure repair algorithm working on the raw data of a column of LOD 0 sections.
 * It knows neither Minecraft nor Voxy's world engine, so it can be tested in isolation.
 * <p>
 * Light byte of a voxel ({@link Mapper#getLightId}): lower 4 bits sky light, upper 4 bits block light.
 */
public final class ColumnRelighter {
    /** Light passes through unhindered (air, glass, water, leaves, ...): set to 15 and continue downwards. */
    public static final int PASS = 0;
    /** Blocks light but renders with its own light (slabs, snow layers, ...): set to 15 and stop. */
    public static final int BLOCK_SELF_LIT = 1;
    /** Fully opaque block, Voxy uses the neighbour's light: leave unchanged and stop. */
    public static final int OPAQUE = 2;

    public static final int FULL_SKY = 15;
    public static final int SIZE = 32;

    public interface BlockClassifier {
        /** Returns {@link #PASS}, {@link #BLOCK_SELF_LIT} or {@link #OPAQUE} for a Voxy block ID (never air). */
        int classify(int blockId);
    }

    public static final class Stats {
        public long columns;
        public long darkColumns;
        public long changedVoxels;
    }

    private ColumnRelighter() {}

    /** Equivalent to {@code WorldSection.getIndex}; kept local so the algorithm stays testable without a loaded Voxy runtime. */
    public static int index(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }

    /** Bit of the 16³ sub-section (corresponds to a Minecraft chunk section) within a 32³ section. */
    public static int subSectionBit(int x, int y, int z) {
        return 1 << ((x >> 4) | ((y >> 4) << 1) | ((z >> 4) << 2));
    }

    public static int skyLight(long voxel) {
        return Mapper.getLightId(voxel) & 0x0F;
    }

    public static long withFullSky(long voxel) {
        return Mapper.withLight(voxel, (Mapper.getLightId(voxel) & 0xF0) | FULL_SKY);
    }

    /**
     * Repairs a column of existing LOD 0 sections in place.
     *
     * @param sections     Voxel data of the sections, sorted from top (index 0) to bottom.
     *                     Missing sections in between count as air with full sky light, as in Voxy.
     * @param changedMasks Output: one bit mask of the changed 16³ sub-sections per section
     *                     (see {@link #subSectionBit}).
     * @param force        also process columns that were not detected as dark
     */
    public static void relight(long[][] sections, int[] changedMasks, BlockClassifier classifier, boolean force, Stats stats) {
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                stats.columns++;
                boolean dark = isDark(sections, x, z);
                if (dark) {
                    stats.darkColumns++;
                }
                if (dark || force) {
                    stats.changedVoxels += fixColumn(sections, changedMasks, classifier, x, z);
                }
            }
        }
    }

    /**
     * A block column counts as dark if an air voxel above the first non-air block has less than
     * full sky light. In a correctly lit world this is always 15 there, so intact columns
     * (including those with water, leaves etc. below the air) are left untouched.
     */
    static boolean isDark(long[][] sections, int x, int z) {
        for (long[] data : sections) {
            for (int y = SIZE - 1; y >= 0; y--) {
                long voxel = data[index(x, y, z)];
                if (!Mapper.isAir(voxel)) {
                    return false;
                }
                if (skyLight(voxel) < FULL_SKY) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Sets sky light 15 from the top down to the first light-blocking block; block light stays unchanged. */
    static int fixColumn(long[][] sections, int[] changedMasks, BlockClassifier classifier, int x, int z) {
        int changed = 0;
        for (int s = 0; s < sections.length; s++) {
            long[] data = sections[s];
            for (int y = SIZE - 1; y >= 0; y--) {
                int idx = index(x, y, z);
                long voxel = data[idx];
                int type = Mapper.isAir(voxel) ? PASS : classifier.classify(Mapper.getBlockId(voxel));
                if (type != OPAQUE && skyLight(voxel) < FULL_SKY) {
                    data[idx] = withFullSky(voxel);
                    changedMasks[s] |= subSectionBit(x, y, z);
                    changed++;
                }
                if (type != PASS) {
                    return changed;
                }
            }
        }
        return changed;
    }
}
