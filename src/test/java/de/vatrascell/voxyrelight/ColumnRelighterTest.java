package de.vatrascell.voxyrelight;

import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import static de.vatrascell.voxyrelight.ColumnRelighter.*;
import static org.junit.jupiter.api.Assertions.*;

class ColumnRelighterTest {
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int SLAB = 3;

    private static final BlockClassifier CLASSIFIER = id -> switch (id) {
        case WATER -> PASS;
        case SLAB -> BLOCK_SELF_LIT;
        default -> OPAQUE;
    };

    private static long air(int sky, int block) {
        return Mapper.airWithLight(sky | (block << 4));
    }

    private static long block(int id, int sky, int blockLight) {
        return Mapper.composeMappingId((byte) (sky | (blockLight << 4)), id, 0);
    }

    /** Two sections stacked on top of each other, completely dark air. */
    private static long[][] darkColumn() {
        long[][] sections = new long[2][SIZE * SIZE * SIZE];
        for (long[] s : sections) {
            java.util.Arrays.fill(s, air(0, 0));
        }
        return sections;
    }

    private static int sky(long[][] sections, int section, int x, int y, int z) {
        return skyLight(sections[section][index(x, y, z)]);
    }

    @Test
    void lightsAirDownToSurfaceButNotCaveBelow() {
        long[][] sections = darkColumn();
        // Surface in the lower section at y=20, cave (air) below it at y=10
        sections[1][index(5, 20, 7)] = block(STONE, 0, 0);
        int[] masks = new int[2];
        var stats = new Stats();

        relight(sections, masks, CLASSIFIER, false, stats);

        assertEquals(15, sky(sections, 0, 5, 31, 7));
        assertEquals(15, sky(sections, 0, 5, 0, 7));
        assertEquals(15, sky(sections, 1, 5, 21, 7));
        assertEquals(0, sky(sections, 1, 5, 20, 7), "opaque block stays unchanged");
        assertEquals(0, sky(sections, 1, 5, 10, 7), "cave below the block stays dark");
        assertEquals(SIZE * SIZE, stats.darkColumns);
        assertEquals(0xFF, masks[0]);
        // Lower section: column 5/7 reaches into the upper y half, other columns completely
        assertEquals(0xFF, masks[1]);
    }

    @Test
    void overhangKeepsSpaceBelowDark() {
        long[][] sections = darkColumn();
        sections[0][index(0, 25, 0)] = block(STONE, 0, 0);
        relight(sections, new int[2], CLASSIFIER, false, new Stats());

        assertEquals(15, sky(sections, 0, 0, 26, 0));
        assertEquals(0, sky(sections, 0, 0, 24, 0));
        assertEquals(0, sky(sections, 1, 0, 31, 0));
    }

    @Test
    void waterPassesAndSlabIsLitButStops() {
        long[][] sections = darkColumn();
        sections[0][index(1, 30, 1)] = block(WATER, 0, 0);
        sections[0][index(1, 29, 1)] = block(WATER, 0, 0);
        sections[0][index(1, 28, 1)] = block(SLAB, 0, 0);
        relight(sections, new int[2], CLASSIFIER, false, new Stats());

        assertEquals(15, sky(sections, 0, 1, 30, 1));
        assertEquals(15, sky(sections, 0, 1, 29, 1));
        assertEquals(15, sky(sections, 0, 1, 28, 1), "non-full block uses its own light");
        assertEquals(0, sky(sections, 0, 1, 27, 1));
    }

    @Test
    void blockLightIsPreserved() {
        long[][] sections = darkColumn();
        sections[0][index(2, 31, 2)] = air(0, 12);
        relight(sections, new int[2], CLASSIFIER, false, new Stats());

        long voxel = sections[0][index(2, 31, 2)];
        assertEquals(15, skyLight(voxel));
        assertEquals(12, Mapper.getLightId(voxel) >> 4);
    }

    @Test
    void intactColumnIsUntouchedUnlessForced() {
        long[][] sections = new long[1][SIZE * SIZE * SIZE];
        java.util.Arrays.fill(sections[0], air(15, 0));
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                sections[0][index(x, 10, z)] = block(WATER, 0, 0);
                sections[0][index(x, 9, z)] = air(13, 0);
            }
        }
        long[] before = sections[0].clone();
        int[] masks = new int[1];
        var stats = new Stats();

        relight(sections, masks, CLASSIFIER, false, stats);
        assertArrayEquals(before, sections[0]);
        assertEquals(0, masks[0]);
        assertEquals(0, stats.darkColumns);

        relight(sections, masks, CLASSIFIER, true, stats);
        assertEquals(15, sky(sections, 0, 3, 10, 3));
        assertEquals(15, sky(sections, 0, 3, 9, 3));
    }

    @Test
    void subSectionBitsMatchVoxelizedLayout() {
        assertEquals(1, subSectionBit(0, 0, 0));
        assertEquals(1 << 1, subSectionBit(16, 0, 0));
        assertEquals(1 << 2, subSectionBit(0, 16, 0));
        assertEquals(1 << 4, subSectionBit(0, 0, 16));
    }
}
