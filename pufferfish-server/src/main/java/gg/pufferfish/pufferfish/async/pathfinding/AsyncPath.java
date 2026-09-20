package gg.pufferfish.pufferfish.async.pathfinding;

import ca.spottedleaf.moonrise.common.util.TickThread;
import gg.pufferfish.pufferfish.PufferfishConfig;
import gg.pufferfish.pufferfish.util.NamedThreadFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

@SuppressWarnings("NullableProblems")
public final class AsyncPath extends Path {
	private static final String THREAD_PREFIX = "Async Pathfinding";
	private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
	public static final ThreadPoolExecutor EXECUTOR = initializeExecutor();
	private static volatile long lastWarnMillis = System.currentTimeMillis();

	private volatile boolean ready = false;

	private final ArrayList<Consumer<Path>> postProcessingCallbacks = new ArrayList<>(0);
	private final Set<BlockPos> targetPositions;
	private final AtomicReference<Function<PathFinder, Path>> pathFunction;
	private final PathFinder finder;
	private final AtomicReference<Path> computedPath = new AtomicReference<>();

	private volatile BlockPos target;
	private volatile float distToTarget = 0;
	private volatile boolean canReach = true;

	public AsyncPath(@NotNull PathFinder finder,
					 @NotNull List<Node> emptyNodeList,
					 @NotNull Set<BlockPos> targetPositions,
					 @NotNull Function<PathFinder, Path> pathFunction) {
		super(emptyNodeList, null, false);

		this.finder = finder;
		this.nodes = emptyNodeList;
		this.targetPositions = targetPositions;
		this.pathFunction = new AtomicReference<>(pathFunction);

		queueProcessing();
	}

	private void queueProcessing() {
		if (EXECUTOR == null) {
			computePath();
			return;
		}

		CompletableFuture.runAsync(this::computePath, EXECUTOR)
			.orTimeout(60L, TimeUnit.SECONDS)
			.exceptionally(throwable -> {
				Throwable cause = throwable;
				while (cause.getCause() != null) cause = cause.getCause();
				if (cause instanceof TimeoutException) {
					LOGGER.warn("Async pathfinding timed out after 60 seconds", throwable);
				} else {
					LOGGER.warn("Error during async pathfinding", throwable);
				}
				this.computedPath.compareAndSet(null, failedPath());
				return null;
			});
	}

	private void computePath() {
		synchronized (this.finder) {
			final Function<PathFinder, Path> function = this.pathFunction.getAndSet(null);
			if (function == null) return;

			final Path computed = Objects.requireNonNull(function.apply(this.finder));
			this.computedPath.compareAndSet(null, computed);
		}
	}

	private Path failedPath() {
		return new Path(List.of(), this.targetPositions.iterator().next(), false);
	}

	private void complete(@NotNull Path completedPath) {
		final List<Consumer<Path>> callbacks;
		synchronized (this.postProcessingCallbacks) {
			if (this.ready) {
				return;
			}

			this.nodes = completedPath.nodes;
			this.target = completedPath.getTarget();
			this.distToTarget = completedPath.getDistToTarget();
			this.canReach = completedPath.canReach();

			this.ready = true;

			callbacks = List.copyOf(this.postProcessingCallbacks);
			this.postProcessingCallbacks.clear();
		}

		for (Consumer<Path> callback : callbacks) {
			try {
				callback.accept(this);
			} catch (Exception e) {
				LOGGER.error("Error executing post-processing callback", e);
			}
		}
	}

	private void process() {
		if (this.ready) {
			return;
		}

		Path computed = this.computedPath.get();
		if (computed == null) {
			computePath();
			computed = Objects.requireNonNull(this.computedPath.get());
		}

		complete(computed);
	}

	@Override
	public boolean isProcessed() {
		if (this.ready) {
			return true;
		}

		Path computed = this.computedPath.get();
		if (computed != null) {
			complete(computed);
			return true;
		}

		return false;
	}

	public void applyAfterProcessing(@NotNull Consumer<Path> callback) {
		synchronized (this.postProcessingCallbacks) {
			if (!this.ready) {
				this.postProcessingCallbacks.add(callback);
				return;
			}
		}

		callback.accept(this);
	}

	public boolean hasSameTargetPositions(final Set<BlockPos> positions) {
		if (this.targetPositions.size() != positions.size()) {
			return false;
		}

		if (positions.size() == 1) {
			return this.targetPositions.iterator().next().equals(positions.iterator().next());
		}

		return this.targetPositions.containsAll(positions);
	}

	@Override
	public @NotNull BlockPos getTarget() {
		process();
		return target;
	}

	@Override
	public float getDistToTarget() {
		process();
		return distToTarget;
	}

	@Override
	public boolean canReach() {
		process();
		return canReach;
	}

	@Override
	public boolean isDone() {
		if (!this.ready) {
			Path computed = this.computedPath.get();
			if (computed != null) {
				complete(computed);
			}
		}
		return this.ready && super.isDone();
	}

	@Override
	public void advance() {
		process();
		super.advance();
	}

	@Override
	public boolean notStarted() {
		process();
		return super.notStarted();
	}

	@Nullable
	@Override
	public Node getEndNode() {
		process();
		return super.getEndNode();
	}

	@Override
	public Node getNode(int index) {
		process();
		return super.getNode(index);
	}

	@Override
	public void truncateNodes(int length) {
		process();
		super.truncateNodes(length);
	}

	@Override
	public void replaceNode(int index, Node node) {
		process();
		super.replaceNode(index, node);
	}

	@Override
	public int getNodeCount() {
		process();
		return super.getNodeCount();
	}

	@Override
	public int getNextNodeIndex() {
		process();
		return super.getNextNodeIndex();
	}

	@Override
	public void setNextNodeIndex(int nodeIndex) {
		process();
		super.setNextNodeIndex(nodeIndex);
	}

	@Override
	public Vec3 getEntityPosAtNode(Entity entity, int index) {
		process();
		return super.getEntityPosAtNode(entity, index);
	}

	@Override
	public BlockPos getNodePos(int index) {
		process();
		return super.getNodePos(index);
	}

	@Override
	public Vec3 getNextEntityPos(Entity entity) {
		process();
		return super.getNextEntityPos(entity);
	}

	@Override
	public BlockPos getNextNodePos() {
		process();
		return super.getNextNodePos();
	}

	@Override
	public Node getNextNode() {
		process();
		return super.getNextNode();
	}

	@Nullable
	@Override
	public Node getPreviousNode() {
		process();
		return super.getPreviousNode();
	}

	public static void applyAfterProcessing(@Nullable Path path,
											@NotNull Consumer<@Nullable Path> callback) {
		if (path instanceof AsyncPath asyncPath && !asyncPath.isProcessed()) {
			asyncPath.applyAfterProcessing(processedPath -> {
				MinecraftServer server = MinecraftServer.getServer();
				if (Thread.currentThread() == server.getRunningThread()) {
					callback.accept(processedPath);
				} else {
					server.scheduleOnMain(() -> callback.accept(processedPath));
				}
			});
		} else {
			callback.accept(path);
		}
	}

	@Nullable
	private static ThreadPoolExecutor initializeExecutor() {
		if (!PufferfishConfig.enableAsyncPathfinding) {
			return null;
		}

		return new ThreadPoolExecutor(
			1,
			PufferfishConfig.asyncPathfindingMaxThreads,
			PufferfishConfig.asyncPathfindingKeepalive, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(PufferfishConfig.asyncPathfindingQueueSize),
			new NamedThreadFactory<>(
				THREAD_PREFIX,
				TickThread::new,
				Thread.NORM_PRIORITY - 2
			),
			new RejectionHandler()
		);
	}

	private static class RejectionHandler implements RejectedExecutionHandler {
		@Override
		public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
			if (executor.isShutdown()) {
				return;
			}

			switch (PufferfishConfig.asyncPathfindingRejectPolicy) {
				case FLUSH_ALL -> {
					List<Runnable> pending = new ArrayList<>();
					executor.getQueue().drainTo(pending);

					for (Runnable pendingTask : pending) {
						pendingTask.run();
					}
					task.run();
				}
				case CALLER_RUNS -> task.run();
			}

			logQueueWarning();
		}

		private void logQueueWarning() {
			long now = System.currentTimeMillis();
			if (now - lastWarnMillis > 30_000L) {
				LOGGER.warn("Async pathfinding processor is busy! Pathfinding tasks will be treated as policy defined in config. Increasing async.pathfinding.max-threads in pufferfish.yml may help.");
				lastWarnMillis = now;
			}
		}
	}
}
