package gg.pufferfish.pufferfish.async.pathfinding;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private final Long2ObjectOpenHashMap<BlockState> blockStates;
    private final int minX, maxX, minZ, maxZ, minY, height, seaLevel;
    private final double borderCenterX, borderCenterZ, borderSize;
    private final CollisionContext collisionContext;
    private final @Nullable EntityState entityState;

    private PathfindingSnapshot(final Long2ObjectOpenHashMap<BlockState> blockStates, final int minX, final int maxX,
                                final int minZ, final int maxZ, final int minY, final int height, final int seaLevel,
                                final double borderCenterX, final double borderCenterZ, final double borderSize,
                                final CollisionContext collisionContext, final @Nullable EntityState entityState) {
        this.blockStates = blockStates;
        this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
        this.minY = minY; this.height = height; this.seaLevel = seaLevel;
        this.borderCenterX = borderCenterX; this.borderCenterZ = borderCenterZ; this.borderSize = borderSize;
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
        final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
        for (int x = minX; x <= maxX; x++) for (int y = captureMinY; y <= captureMaxY; y++) for (int z = minZ; z <= maxZ; z++) {
            final BlockPos pos = new BlockPos(x, y, z);
            final BlockState state = region.getBlockState(pos);
            if (!state.isAir() || !state.getFluidState().isEmpty()) states.put(pos.asLong(), state);
        }
        final WorldBorder border = region.getWorldBorder();
        return new PathfindingSnapshot(states, minX, maxX, minZ, maxZ, region.getMinY(), region.getHeight(), seaLevel,
            border.getCenterX(), border.getCenterZ(), border.getSize(), SnapshotCollisionContext.capture(entity), EntityState.capture(entity));
    }

    @Override public @Nullable BlockEntity getBlockEntity(final BlockPos pos) { return null; }
    @Override public @NotNull BlockState getBlockState(final BlockPos pos) {
        if (this.isOutsideBuildHeight(pos) || pos.getX() < this.minX || pos.getX() > this.maxX || pos.getZ() < this.minZ || pos.getZ() > this.maxZ) return AIR;
        return this.blockStates.getOrDefault(pos.asLong(), AIR);
    }
    @Override public @Nullable BlockState getBlockStateIfLoaded(final BlockPos pos) { return this.getBlockState(pos); }
    @Override public @Nullable FluidState getFluidIfLoaded(final BlockPos pos) { return this.getFluidState(pos); }
    @Override public @NotNull FluidState getFluidState(final BlockPos pos) { return this.getBlockState(pos).getFluidState(); }
    @Override public @NotNull WorldBorder getWorldBorder() {
        WorldBorder border = new WorldBorder();
        border.setCenter(this.borderCenterX, this.borderCenterZ); border.setSize(this.borderSize);
        return border;
    }
    @Override public @Nullable BlockGetter getChunkForCollisions(final int chunkX, final int chunkZ) { return this; }
    @Override public @NotNull List<VoxelShape> getEntityCollisions(final @Nullable Entity source, final AABB testArea) { return List.of(); }
    @Override public boolean noCollision(final @Nullable Entity source, final AABB box) {
        for (VoxelShape shape : this.getBlockCollisionsFromContext(this.collisionContext, box)) if (!shape.isEmpty()) return false;
        return this.getWorldBorder().isWithinBounds(box);
    }
    @Override public int getMinY() { return this.minY; }
    @Override public int getHeight() { return this.height; }
    public int seaLevel() { return this.seaLevel; }
    public @Nullable EntityState entityState() { return this.entityState; }

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
