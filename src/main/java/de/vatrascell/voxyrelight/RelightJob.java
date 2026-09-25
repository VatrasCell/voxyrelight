package de.vatrascell.voxyrelight;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * A scan or repair job over the LOD 0 sections of a Voxy world. Runs on its own thread,
 * works column by column (32×32 blocks) and can be cancelled between two columns.
 * <p>
 * Changed data is written back per 16³ sub-section via {@link WorldUpdater#insertUpdate},
 * just like Voxy's importers. This recalculates LOD levels 1–4, marks the sections dirty
 * (saving goes through Voxy's save queue) and triggers a rebuild of the render data.
 */
final class RelightJob implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxyrelight");
    private static final long PROGRESS_INTERVAL_MS = 5_000;

    /** A column of LOD 0 sections; {@code sectionYs} sorted in descending order. */
    private record Column(int x, int z, int[] sectionYs) {}

    private final VoxyInstance instance;
    private final WorldEngine engine;
    private final boolean dryRun;
    private final boolean force;
    private final String description;
    /** null = whole world, otherwise centre and radius in LOD 0 sections. */
    private final Area area;
    private final Consumer<String> chat;

    private final ColumnRelighter.Stats stats = new ColumnRelighter.Stats();
    private final VoxelizedSection voxelized = VoxelizedSection.createEmpty();
    private final SkyLightClassifier classifier;

    private volatile boolean cancelled;
    private long sectionsRead;
    private long sectionsChanged;
    private long subSectionsWritten;
    private long subSectionsSkippedConcurrent;

    record Area(int centerX, int centerZ, int radius, int minSectionY, int maxSectionY) {}

    RelightJob(VoxyInstance instance, WorldEngine engine, Area area, boolean dryRun, boolean force, String description, Consumer<String> chat) {
        this.instance = instance;
        this.engine = engine;
        this.area = area;
        this.dryRun = dryRun;
        this.force = force;
        this.description = description;
        this.chat = chat;
        this.classifier = new SkyLightClassifier(engine.getMapper());
    }

    String description() {
        return this.description;
    }

    void cancel() {
        this.cancelled = true;
    }

    private boolean shouldStop() {
        return this.cancelled || !this.instance.isRunning() || !this.engine.isLive();
    }

    @Override
    public void run() {
        long start = System.currentTimeMillis();
        try {
            this.engine.acquireRef();// Keeps the world alive while the job is running
        } catch (IllegalStateException e) {
            this.chat.accept(this.description + " not started: the Voxy world is no longer active.");
            return;
        }
        try {
            List<Column> columns = this.collectColumns();
            this.chat.accept(this.description + ": " + columns.size() + " section columns to check.");

            long lastProgress = System.currentTimeMillis();
            int done = 0;
            for (Column column : columns) {
                if (!this.waitForVoxy()) {
                    break;
                }
                this.processColumn(column);
                done++;

                long now = System.currentTimeMillis();
                if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                    lastProgress = now;
                    this.chat.accept(String.format("Progress: %d/%d columns (%d %%), %s",
                            done, columns.size(), done * 100L / Math.max(1, columns.size()), this.summary()));
                }
            }

            String result = this.cancelled ? "cancelled" : this.shouldStop() ? "stopped because Voxy shut down" : "finished";
            this.chat.accept(String.format("%s %s after %.1f s. %d/%d columns processed, %s",
                    this.description, result, (System.currentTimeMillis() - start) / 1000.0, done, columns.size(), this.summary()));
        } catch (Throwable t) {
            LOGGER.error("Job failed: {}", this.description, t);
            this.chat.accept(this.description + " failed: " + t + " (see log for details)");
        } finally {
            if (this.engine.isLive()) {
                this.engine.releaseRef();
            }
        }
    }

    private String summary() {
        String changed = this.dryRun
                ? String.format("%d sections would be changed (%d voxels)", this.sectionsChanged, this.stats.changedVoxels)
                : String.format("%d sections changed (%d voxels, %d chunk sections rewritten)",
                        this.sectionsChanged, this.stats.changedVoxels, this.subSectionsWritten);
        String skipped = this.subSectionsSkippedConcurrent == 0 ? "" : ", " + this.subSectionsSkippedConcurrent + " chunk sections only partially repaired because Voxy changed them concurrently";
        return String.format("%d sections read, %d/%d block columns dark, %s%s",
                this.sectionsRead, this.stats.darkColumns, this.stats.columns, changed, skipped);
    }

    /** Waits while Voxy's save queue is full so the game stays smooth. Returns false if cancelled. */
    private boolean waitForVoxy() {
        while (!this.shouldStop()) {
            if (this.instance.savingServiceRateLimiter.getAsBoolean()) {
                Thread.yield();
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.cancelled = true;
            }
        }
        return false;
    }

    private List<Column> collectColumns() {
        List<Column> columns = new ArrayList<>();
        if (this.area != null) {
            // Small area: query existing sections directly instead of iterating the whole database
            int[] ys = new int[this.area.maxSectionY() - this.area.minSectionY() + 1];
            for (int i = 0; i < ys.length; i++) {
                ys[i] = this.area.maxSectionY() - i;
            }
            int r = this.area.radius();
            for (int x = this.area.centerX() - r; x <= this.area.centerX() + r; x++) {
                for (int z = this.area.centerZ() - r; z <= this.area.centerZ() + r; z++) {
                    columns.add(new Column(x, z, ys));
                }
            }
            return columns;
        }

        LongArrayList positions = new LongArrayList();
        this.engine.storage.iteratePositions(0, positions::add);
        Long2ObjectOpenHashMap<IntArrayList> byColumn = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < positions.size(); i++) {
            long pos = positions.getLong(i);
            long columnKey = ((long) WorldEngine.getX(pos) << 32) | (WorldEngine.getZ(pos) & 0xFFFFFFFFL);
            byColumn.computeIfAbsent(columnKey, k -> new IntArrayList()).add(WorldEngine.getY(pos));
        }
        for (var entry : byColumn.long2ObjectEntrySet()) {
            int[] ys = entry.getValue().toIntArray();
            Arrays.sort(ys);
            for (int i = 0, j = ys.length - 1; i < j; i++, j--) {
                int tmp = ys[i];
                ys[i] = ys[j];
                ys[j] = tmp;
            }
            columns.add(new Column((int) (entry.getLongKey() >> 32), (int) entry.getLongKey(), ys));
        }
        // Process in spatial order so Voxy's section cache is hit more often
        columns.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.z, b.z));
        return columns;
    }

    private void processColumn(Column column) {
        List<long[]> original = new ArrayList<>();
        IntArrayList ys = new IntArrayList();
        for (int y : column.sectionYs()) {
            WorldSection section = this.engine.acquireIfExists(0, column.x(), y, column.z());
            if (section == null) {
                continue;
            }
            try {
                original.add(section.copyData());
            } finally {
                section.release();
            }
            ys.add(y);
        }
        if (original.isEmpty()) {
            return;
        }
        this.sectionsRead += original.size();

        long[][] work = new long[original.size()][];
        for (int i = 0; i < work.length; i++) {
            work[i] = original.get(i).clone();
        }
        int[] changedMasks = new int[work.length];
        ColumnRelighter.relight(work, changedMasks, this.classifier, this.force, this.stats);

        for (int i = 0; i < work.length; i++) {
            if (changedMasks[i] == 0) {
                continue;
            }
            this.sectionsChanged++;
            if (!this.dryRun) {
                this.writeBack(column.x(), ys.getInt(i), column.z(), original.get(i), work[i], changedMasks[i]);
            }
        }
    }

    /**
     * Writes the changed 16³ sub-sections back. Voxels that Voxy itself changed since reading
     * (e.g. freshly loaded chunks) keep their current state.
     */
    private void writeBack(int x, int y, int z, long[] original, long[] fixed, int changedMask) {
        WorldSection section = this.engine.acquireIfExists(0, x, y, z);
        if (section == null) {
            return;
        }
        try {
            long[] current = section.copyData();
            for (int sub = 0; sub < 8; sub++) {
                if ((changedMask & (1 << sub)) == 0) {
                    continue;
                }
                int ox = (sub & 1) << 4;
                int oy = ((sub >> 1) & 1) << 4;
                int oz = ((sub >> 2) & 1) << 4;

                long[] target = this.voxelized.section;
                boolean anyChange = false;
                boolean concurrent = false;
                int nonAir = 0;
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            int idx = ColumnRelighter.index(ox + lx, oy + ly, oz + lz);
                            long cur = current[idx];
                            long value;
                            if (cur == original[idx]) {
                                value = fixed[idx];
                            } else {
                                value = cur;
                                concurrent |= fixed[idx] != original[idx];
                            }
                            anyChange |= value != cur;
                            nonAir += Mapper.isNotAirInt(value);
                            // Index layout of the 16³ level of a VoxelizedSection: y<<8 | z<<4 | x
                            target[(ly << 8) | (lz << 4) | lx] = value;
                        }
                    }
                }
                if (concurrent) {
                    this.subSectionsSkippedConcurrent++;
                }
                if (!anyChange) {
                    continue;
                }
                this.voxelized.lvl0NonAirCount = nonAir;
                this.voxelized.setPosition((x << 1) | (ox >> 4), (y << 1) | (oy >> 4), (z << 1) | (oz >> 4));
                WorldVoxilizedSectionMipper.mipSection(this.voxelized, this.engine.getMapper());
                WorldUpdater.insertUpdate(this.engine, this.voxelized);
                this.subSectionsWritten++;
            }
        } finally {
            section.release();
        }
    }
}
