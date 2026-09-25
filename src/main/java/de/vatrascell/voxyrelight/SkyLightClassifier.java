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

    static int classify(BlockState state) {
        if (state.isAir()) {
            return ColumnRelighter.PASS;
        }
        // Simplification: everything translucent (glass, water, leaves, ice) lets full sky light through.
        if (state.getLightDampening() < 15 && (state.propagatesSkylightDown() || !state.canOcclude())) {
            return ColumnRelighter.PASS;
        }
        // Voxy renders fully opaque blocks with the light of the neighbouring voxel, all others with their own.
        return state.isSolidRender() ? ColumnRelighter.OPAQUE : ColumnRelighter.BLOCK_SELF_LIT;
    }
}
