package de.vatrascell.voxyrelight;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Maps Voxy block IDs to their light behaviour via Voxy's {@link Mapper}. Results are cached per ID.
 * Not thread-safe, only used by the worker thread of a job.
 */
final class SkyLightClassifier implements ColumnRelighter.BlockClassifier {
    private static final byte UNKNOWN = -1;

    private final Mapper mapper;
    private byte[] cache = new byte[0];

    SkyLightClassifier(Mapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int classify(int blockId) {
        if (blockId >= this.cache.length) {
            int oldLength = this.cache.length;
            this.cache = Arrays.copyOf(this.cache, Math.max(blockId + 1, this.mapper.getBlockStateCount()));
            Arrays.fill(this.cache, oldLength, this.cache.length, UNKNOWN);
        }
        byte type = this.cache[blockId];
        if (type == UNKNOWN) {
            type = (byte) classify(this.mapper.getBlockStateFromBlockId(blockId));
            this.cache[blockId] = type;
        }
        return type;
    }

    /** Returns {@link ColumnRelighter#pack(int, int)} made up of the type and the attenuation per block. */
    static int classify(BlockState state) {
        if (state.isAir()) {
            return ColumnRelighter.pack(ColumnRelighter.PASS, 0);
        }
        // Voxy renders fully opaque blocks with the light of the neighbouring voxel
        if (state.isSolidRender() || state.getLightDampening() >= 15) {
            return ColumnRelighter.pack(ColumnRelighter.OPAQUE, 0);
        }
        // Slabs, snow layers, dirt paths: vanilla occludes light via the shape. They are rendered with their own light,
        // but the space below stays dark. The shape is not reproduced, so stop here.
        if (state.useShapeForLightOcclusion()) {
            return ColumnRelighter.pack(ColumnRelighter.BLOCK_SELF_LIT, 0);
        }
        // As in vanilla: propagatesSkylightDown (glass, bars, ...) lets sky 15 through unchanged,
        // while water, leaves, ice etc. attenuate it by at least 1 per block.
        if (state.propagatesSkylightDown()) {
            return ColumnRelighter.pack(ColumnRelighter.PASS, 0);
        }
        if (!state.canOcclude()) {
            return ColumnRelighter.pack(ColumnRelighter.PASS, Math.max(1, state.getLightDampening()));
        }
        return ColumnRelighter.pack(ColumnRelighter.BLOCK_SELF_LIT, 0);
    }
}
