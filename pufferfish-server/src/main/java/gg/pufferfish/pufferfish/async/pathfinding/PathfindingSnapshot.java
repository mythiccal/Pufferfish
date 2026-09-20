package gg.pufferfish.pufferfish.async.pathfinding;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Immutable block, border and entity-scalar input for an async request. */
public final class PathfindingSnapshot implements CollisionGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private final Long2ObjectOpenHashMap<CopiedSection> sections;
    private final int minX, maxX, minZ, maxZ, captureMinY, captureMaxY, minY, height, seaLevel;
    private final WorldBorder worldBorder;
    private final CollisionContext collisionContext;
    private final @Nullable EntityState entityState;

    private PathfindingSnapshot(final Long2ObjectOpenHashMap<CopiedSection> sections, final int minX, final int maxX,
                                final int minZ, final int maxZ, final int captureMinY, final int captureMaxY,
                                final int minY, final int height, final int seaLevel,
                                final double borderCenterX, final double borderCenterZ, final double borderSize,
                                final CollisionContext collisionContext, final @Nullable EntityState entityState) {
        this.sections = sections;
        this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
        this.captureMinY = captureMinY; this.captureMaxY = captureMaxY;
        this.minY = minY; this.height = height; this.seaLevel = seaLevel;
        this.worldBorder = new WorldBorder();
        this.worldBorder.setCenter(borderCenterX, borderCenterZ);
        this.worldBorder.setSize(borderSize);
        this.collisionContext = collisionContext; this.entityState = entityState;
    }

    public static @NotNull PathfindingSnapshot capture(final PathNavigationRegion region, final BlockPos center, final int radius) {
        return capture(region, center, radius, 63, null);
    }

    public static @NotNull PathfindingSnapshot capture(final PathNavigationRegion region, final BlockPos center, final int radius, final int seaLevel) {
        return capture(region, center, radius, seaLevel, null);
    }

    /** Captures immutable states only; no chunk, level, block entity or entity is retained. */
    public static @NotNull PathfindingSnapshot capture(final PathNavigationRegion region, final BlockPos center, final int radius,
                                                       final int seaLevel, final @Nullable Mob entity) {
        final int horizontalRadius = Math.max(1, radius) + 2;
        final int minX = center.getX() - horizontalRadius, maxX = center.getX() + horizontalRadius;
        final int minZ = center.getZ() - horizontalRadius, maxZ = center.getZ() + horizontalRadius;
        final int verticalRadius = Math.max(1, radius) + 2;
        final int captureMinY = Math.max(region.getMinY(), center.getY() - verticalRadius);
        final int captureMaxY = Math.min(region.getMaxY(), center.getY() + verticalRadius);
        final Long2ObjectOpenHashMap<CopiedSection> sections = new Long2ObjectOpenHashMap<>();
        copyOverlappingSections(region, minX, maxX, captureMinY, captureMaxY, minZ, maxZ, sections);
        final WorldBorder border = region.getWorldBorder();
        return new PathfindingSnapshot(sections, minX, maxX, minZ, maxZ, captureMinY, captureMaxY, region.getMinY(), region.getHeight(), seaLevel,
            border.getCenterX(), border.getCenterZ(), border.getSize(), SnapshotCollisionContext.capture(entity), EntityState.capture(entity));
    }

    private static void copyOverlappingSections(final PathNavigationRegion region, final int minX, final int maxX,
                                                final int minY, final int maxY, final int minZ, final int maxZ,
                                                final Long2ObjectOpenHashMap<CopiedSection> sections) {
        final int minChunkX = SectionPos.blockToSectionCoord(minX);
        final int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        final int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        final int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
        final int minSectionY = SectionPos.blockToSectionCoord(minY);
        final int maxSectionY = SectionPos.blockToSectionCoord(maxY);
        BlockPos.MutableBlockPos cursor = null;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final BlockGetter chunk = region.getChunkForCollisions(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                if (chunk instanceof ChunkAccess chunkAccess) {
                    copyChunkAccessSections(chunkAccess, chunkX, chunkZ, minSectionY, maxSectionY, sections);
                    continue;
                }
                if (cursor == null) {
                    cursor = new BlockPos.MutableBlockPos();
                }
                copyBlockGetterSections(chunk, chunkX, chunkZ, minX, maxX, minY, maxY, minZ, maxZ, minSectionY, maxSectionY, cursor, sections);
            }
        }
    }

    private static void copyChunkAccessSections(final ChunkAccess chunkAccess, final int chunkX, final int chunkZ,
                                                final int minSectionY, final int maxSectionY,
                                                final Long2ObjectOpenHashMap<CopiedSection> sections) {
        final int lo = Math.max(minSectionY, chunkAccess.getMinSectionY());
        final int hi = Math.min(maxSectionY, chunkAccess.getMaxSectionY());
        for (int sectionY = lo; sectionY <= hi; sectionY++) {
            final int index = chunkAccess.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= chunkAccess.getSectionsCount()) {
                continue;
            }
            final LevelChunkSection section = chunkAccess.getSection(index);
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            sections.put(sectionKey(chunkX, sectionY, chunkZ), CopiedSection.paletted(section.getStates().copy()));
        }
    }

    private static void copyBlockGetterSections(final BlockGetter chunk, final int chunkX, final int chunkZ,
                                                final int minX, final int maxX, final int minY, final int maxY,
                                                final int minZ, final int maxZ, final int minSectionY, final int maxSectionY,
                                                final BlockPos.MutableBlockPos cursor,
                                                final Long2ObjectOpenHashMap<CopiedSection> sections) {
        final int sectionMinX = Math.max(minX, SectionPos.sectionToBlockCoord(chunkX));
        final int sectionMaxX = Math.min(maxX, SectionPos.sectionToBlockCoord(chunkX, SectionPos.SECTION_MAX_INDEX));
        final int sectionMinZ = Math.max(minZ, SectionPos.sectionToBlockCoord(chunkZ));
        final int sectionMaxZ = Math.min(maxZ, SectionPos.sectionToBlockCoord(chunkZ, SectionPos.SECTION_MAX_INDEX));
        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            final int y0 = Math.max(minY, SectionPos.sectionToBlockCoord(sectionY));
            final int y1 = Math.min(maxY, SectionPos.sectionToBlockCoord(sectionY, SectionPos.SECTION_MAX_INDEX));
            BlockState[] packed = null;
            for (int x = sectionMinX; x <= sectionMaxX; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = sectionMinZ; z <= sectionMaxZ; z++) {
                        final BlockState state = chunk.getBlockState(cursor.set(x, y, z));
                        if (state.isAir() && state.getFluidState().isEmpty()) {
                            continue;
                        }
                        if (packed == null) {
                            packed = new BlockState[SectionPos.SECTION_BLOCK_COUNT];
                        }
                        packed[sectionIndex(x, y, z)] = state;
                    }
                }
            }
            if (packed != null) {
                sections.put(sectionKey(chunkX, sectionY, chunkZ), CopiedSection.packed(packed));
            }
        }
    }

    private static int sectionIndex(final int x, final int y, final int z) {
        return (SectionPos.sectionRelative(y) << 8) | (SectionPos.sectionRelative(z) << 4) | SectionPos.sectionRelative(x);
    }

    private static long sectionKey(final BlockPos pos) {
        return sectionKey(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ())
        );
    }

    private static long sectionKey(final int sectionX, final int sectionY, final int sectionZ) {
        return SectionPos.asLong(sectionX, sectionY, sectionZ);
    }

    @Override public @Nullable BlockEntity getBlockEntity(final BlockPos pos) { return null; }
    @Override public @NotNull BlockState getBlockState(final BlockPos pos) {
        if (this.isOutsideBuildHeight(pos)
            || pos.getX() < this.minX || pos.getX() > this.maxX
            || pos.getZ() < this.minZ || pos.getZ() > this.maxZ
            || pos.getY() < this.captureMinY || pos.getY() > this.captureMaxY) {
            return AIR;
        }
        final CopiedSection section = this.sections.get(sectionKey(pos));
        return section == null ? AIR : section.get(pos);
    }
    @Override public @Nullable BlockState getBlockStateIfLoaded(final BlockPos pos) { return this.getBlockState(pos); }
    @Override public @Nullable FluidState getFluidIfLoaded(final BlockPos pos) { return this.getFluidState(pos); }
    @Override public @NotNull FluidState getFluidState(final BlockPos pos) { return this.getBlockState(pos).getFluidState(); }
    @Override public @NotNull WorldBorder getWorldBorder() { return this.worldBorder; }
    @Override public @Nullable BlockGetter getChunkForCollisions(final int chunkX, final int chunkZ) { return this; }
    @Override public @NotNull List<VoxelShape> getEntityCollisions(final @Nullable Entity source, final AABB testArea) { return List.of(); }
    @Override public boolean noCollision(final @Nullable Entity source, final AABB box) {
        for (VoxelShape shape : this.getBlockCollisionsFromContext(this.collisionContext, box)) if (!shape.isEmpty()) return false;
        return this.worldBorder.isWithinBounds(box);
    }
    @Override public int getMinY() { return this.minY; }
    @Override public int getHeight() { return this.height; }
    public int seaLevel() { return this.seaLevel; }
    public @Nullable EntityState entityState() { return this.entityState; }

    private static final class CopiedSection {
        private final @Nullable PalettedContainer<BlockState> paletted;
        private final BlockState @Nullable [] packed;

        private CopiedSection(final @Nullable PalettedContainer<BlockState> paletted, final BlockState @Nullable [] packed) {
            this.paletted = paletted;
            this.packed = packed;
        }

        static CopiedSection paletted(final PalettedContainer<BlockState> paletted) {
            return new CopiedSection(paletted, null);
        }

        static CopiedSection packed(final BlockState[] packed) {
            return new CopiedSection(null, packed);
        }

        BlockState get(final BlockPos pos) {
            final int x = SectionPos.sectionRelative(pos.getX());
            final int y = SectionPos.sectionRelative(pos.getY());
            final int z = SectionPos.sectionRelative(pos.getZ());
            if (this.paletted != null) {
                return this.paletted.get(x, y, z);
            }
            final BlockState state = this.packed[(y << 8) | (z << 4) | x];
            return state == null ? AIR : state;
        }
    }

    /** Scalar entity state used after the evaluator has dropped its Mob reference. */
    public static final class EntityState {
        private final double x, y, z;
        private final AABB boundingBox;
        private final BlockPos blockPosition;
        private final boolean onGround, inWater, canStandOnWater, canStandOnLava;
        private final float bbWidth, bbHeight, maxUpStep;
        private final int maxFallDistance;
        private EntityState(final Mob entity) {
            this.x = entity.getX(); this.y = entity.getY(); this.z = entity.getZ(); this.boundingBox = entity.getBoundingBox();
            this.blockPosition = entity.blockPosition().immutable(); this.onGround = entity.onGround(); this.inWater = entity.isInWater();
            this.bbWidth = entity.getBbWidth(); this.bbHeight = entity.getBbHeight(); this.maxUpStep = entity.maxUpStep();
            this.maxFallDistance = entity.getMaxFallDistance();
            this.canStandOnWater = entity.canStandOnFluid(Fluids.WATER.getSource(false));
            this.canStandOnLava = entity.canStandOnFluid(Fluids.LAVA.getSource(false));
        }
        private static @Nullable EntityState capture(final @Nullable Mob entity) { return entity == null ? null : new EntityState(entity); }
        public double x() { return this.x; } public double y() { return this.y; } public double z() { return this.z; }
        public AABB boundingBox() { return this.boundingBox; } public BlockPos blockPosition() { return this.blockPosition; }
        public boolean onGround() { return this.onGround; } public boolean inWater() { return this.inWater; }
        public float bbWidth() { return this.bbWidth; } public float bbHeight() { return this.bbHeight; }
        public float maxUpStep() { return this.maxUpStep; } public int maxFallDistance() { return this.maxFallDistance; }
        public boolean canStandOnFluid(final FluidState fluid) {
            return fluid.is(net.minecraft.tags.FluidTags.WATER) ? this.canStandOnWater : fluid.is(net.minecraft.tags.FluidTags.LAVA) && this.canStandOnLava;
        }
    }

    private static final class SnapshotCollisionContext implements CollisionContext {
        private final boolean descending;
        private final double entityBottom;
        private final Item heldItem;
        private final boolean canStandOnWater, canStandOnLava;
        private SnapshotCollisionContext(final Mob entity) {
            this.descending = entity.isDescending(); this.entityBottom = entity.getY();
            this.heldItem = entity instanceof LivingEntity living ? living.getMainHandItem().getItem() : ItemStack.EMPTY.getItem();
            this.canStandOnWater = entity.canStandOnFluid(Fluids.WATER.getSource(false)); this.canStandOnLava = entity.canStandOnFluid(Fluids.LAVA.getSource(false));
        }
        private static CollisionContext capture(final @Nullable Mob entity) { return entity == null ? CollisionContext.empty() : new SnapshotCollisionContext(entity); }
        @Override public boolean isDescending() { return this.descending; }
        @Override public boolean isAbove(final VoxelShape shape, final BlockPos pos, final boolean defaultValue) { return this.entityBottom > pos.getY() + shape.max(Direction.Axis.Y) - 1.0E-5F; }
        @Override public boolean isHoldingItem(final Item item) { return this.heldItem == item; }
        @Override public boolean alwaysCollideWithFluid() { return false; }
        @Override public boolean canStandOnFluid(final FluidState above, final FluidState fluid) {
            return !above.getType().isSame(fluid.getType()) && (fluid.is(net.minecraft.tags.FluidTags.WATER) ? this.canStandOnWater : fluid.is(net.minecraft.tags.FluidTags.LAVA) && this.canStandOnLava);
        }
        @Override public VoxelShape getCollisionShape(final BlockState state, final CollisionGetter level, final BlockPos pos) { return state.getCollisionShape(level, pos, this); }
    }
}
