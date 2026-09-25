package de.vatrascell.voxyrelight;

import me.cortex.voxy.common.world.other.Mapper;

/**
 * Pure repair algorithm working on the raw data of a column of LOD 0 sections.
 * It knows neither Minecraft nor Voxy's world engine, so it can be tested in isolation.
 * <p>
 * Light byte of a voxel ({@link Mapper#getLightId}): lower 4 bits sky light, upper 4 bits block light.
 */
public final class ColumnRelighter {
    /** Light passes through (air, glass, water, leaves, ...) minus the attenuation: set it and continue downwards. */
    public static final int PASS = 0;
    /** Blocks light but renders with its own light (slabs, snow layers, ...): set it and stop. */
    public static final int BLOCK_SELF_LIT = 1;
    /** Fully opaque block, Voxy uses the neighbour's light: leave unchanged and stop. */
    public static final int OPAQUE = 2;

    public static final int FULL_SKY = 15;
    public static final int SIZE = 32;

    public interface BlockClassifier {
        /**
         * Returns {@link #pack(int, int)} for a Voxy block ID (never air), made up of the type ({@link #PASS},
         * {@link #BLOCK_SELF_LIT}, {@link #OPAQUE}) and the sky light attenuation per block.
         */
        int classify(int blockId);
    }

    public static int pack(int type, int attenuation) {
        return type | (attenuation << 2);
    }

    public static int type(int packed) {
        return packed & 3;
    }

    public static int attenuation(int packed) {
        return packed >>> 2;
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

    public static long withSky(long voxel, int sky) {
        return Mapper.withLight(voxel, (Mapper.getLightId(voxel) & 0xF0) | sky);
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
                boolean dark = isDark(sections, classifier, x, z);
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
     * A block column needs repair if
     * <ul>
     *     <li>an air voxel above the first non-air block has less than full sky light
     *     (in a correctly lit world this is always 15 there), or</li>
     *     <li>an attenuating block (water, leaves, ...) in the transparent area has sky 15. This is impossible in
     *     vanilla and comes from earlier repairs without attenuation.</li>
     * </ul>
     * Intact columns are therefore left untouched.
     */
    static boolean isDark(long[][] sections, BlockClassifier classifier, int x, int z) {
        boolean belowSurface = false;
        for (long[] data : sections) {
            for (int y = SIZE - 1; y >= 0; y--) {
                long voxel = data[index(x, y, z)];
                if (Mapper.isAir(voxel)) {
                    if (!belowSurface && skyLight(voxel) < FULL_SKY) {
                        return true;
                    }
                    continue;
                }
                belowSurface = true;
                int packed = classifier.classify(Mapper.getBlockId(voxel));
                if (type(packed) != PASS) {
                    return false;
                }
                if (attenuation(packed) > 0 && skyLight(voxel) == FULL_SKY) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Recalculates the sky light from the top down to the first light-blocking block. As in vanilla,
     * sky 15 is preserved through air and glass, while water, leaves etc. attenuate it per block. Block light stays unchanged.
     */
    static int fixColumn(long[][] sections, int[] changedMasks, BlockClassifier classifier, int x, int z) {
        int changed = 0;
        int level = FULL_SKY;
        for (int s = 0; s < sections.length; s++) {
            long[] data = sections[s];
            for (int y = SIZE - 1; y >= 0; y--) {
                int idx = index(x, y, z);
                long voxel = data[idx];
                int packed = Mapper.isAir(voxel) ? pack(PASS, 0) : classifier.classify(Mapper.getBlockId(voxel));
                int type = type(packed);
                if (type == OPAQUE) {
                    return changed;
                }
                level = Math.max(0, level - attenuation(packed));
                if (skyLight(voxel) != level) {
                    data[idx] = withSky(voxel, level);
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
