package gg.pufferfish.pufferfish.async.pathfinding;

import ca.spottedleaf.moonrise.common.util.TickThread;
import gg.pufferfish.pufferfish.PufferfishConfig;
import gg.pufferfish.pufferfish.util.NamedThreadFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SuppressWarnings("NullableProblems")
public final class AsyncPath extends Path {
	private static final String THREAD_PREFIX = "Async Pathfinding";
	private static final Logger LOGGER = LogManager.getLogger(THREAD_PREFIX);
	public static final ThreadPoolExecutor EXECUTOR = initializeExecutor();
	private static volatile long lastWarnMillis = System.currentTimeMillis();

	private volatile boolean ready = false;

	private final ArrayList<Consumer<Path>> postProcessingCallbacks = new ArrayList<>(0);
	private final Set<BlockPos> targetPositions;
	private final AtomicReference<Path> computedPath = new AtomicReference<>();
	private final WorkItem workItem;
	private volatile CompletableFuture<?> processingFuture;

	private volatile BlockPos target;
	private volatile float distToTarget = 0;
	private volatile boolean canReach = true;

	public AsyncPath(@NotNull List<Node> emptyNodeList,
					 @NotNull Set<BlockPos> targetPositions,
					 @NotNull Supplier<Path> pathSupplier) {
		this(emptyNodeList, targetPositions, pathSupplier, () -> {});
	}

	public AsyncPath(@NotNull List<Node> emptyNodeList,
					 @NotNull Set<BlockPos> targetPositions,
					 @NotNull Supplier<Path> pathSupplier,
					 @NotNull Runnable cleanup) {
		super(emptyNodeList, null, false);

		this.nodes = emptyNodeList;
		this.targetPositions = Set.copyOf(targetPositions);
		final BlockPos failedTarget = this.targetPositions.stream().findFirst().orElse(BlockPos.ZERO);
		this.workItem = new WorkItem(pathSupplier, this.computedPath, failedTarget, cleanup);

		queueProcessing();
	}

	private void queueProcessing() {
		if (EXECUTOR == null) {
			// Keep the class usable when async execution is disabled or when a
			// caller explicitly constructs an AsyncPath in synchronous mode.
			this.workItem.run();
			return;
		}

		this.processingFuture = WorkItem.withTimeout(this.workItem);

		// The submitted Runnable is the static WorkItem. It has no reference to this path
		// or to any post-processing callback, so queued work cannot retain the path graph.
		try {
			EXECUTOR.execute(this.workItem);
		} catch (Throwable throwable) {
			// A custom executor/rejection handler may still throw (for example
			// while shutdown races submission).  Claim queued ownership here so
			// the evaluator lease cannot be stranded.
			LOGGER.warn("Unable to queue async pathfinding work", throwable);
			this.workItem.cancel();
		}
	}

	/**
	 * Abandons a queued request. A running worker retains ownership until its
	 * finally block, so cancellation cannot return an evaluator while it is in
	 * use.
	 */
	public void cancel() {
		this.workItem.cancel();
		final CompletableFuture<?> future = this.processingFuture;
		if (future != null) {
			future.cancel(false);
		}
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
			// If the executor has not started the work yet, the main thread can claim it.
			// If a worker already owns it, run() is a no-op and we wait for its completion.
			this.workItem.run();
			computed = this.computedPath.get();
			if (computed == null) {
				try {
					this.workItem.completion.join();
				} catch (Throwable throwable) {
					LOGGER.warn("Error waiting for async pathfinding", throwable);
					this.workItem.cancel();
				}
				computed = this.computedPath.get();
			}
			if (computed == null) {
				this.workItem.cancel();
				computed = this.computedPath.get();
			}
		}

		complete(Objects.requireNonNull(computed));
	}

	/**
	 * The executor-owned unit of work. This class deliberately contains no
	 * reference to AsyncPath (or its callbacks), allowing queued work to be
	 * discarded without retaining the path object.
	 */
	private static final class WorkItem implements Runnable {
		private enum State { QUEUED, RUNNING, DONE, CANCELLED }

		private final AtomicReference<Supplier<Path>> supplier;
		private final AtomicReference<Path> result;
		private final BlockPos failedTarget;
		private final AtomicReference<Runnable> cleanup;
		private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
		private final AtomicBoolean cleanupComplete = new AtomicBoolean();
		private final CompletableFuture<Path> completion = new CompletableFuture<>();

		private WorkItem(@NotNull Supplier<Path> supplier,
						 @NotNull AtomicReference<Path> result,
						 @NotNull BlockPos failedTarget,
						 @NotNull Runnable cleanup) {
			this.supplier = new AtomicReference<>(supplier);
			this.result = result;
			this.failedTarget = failedTarget;
			this.cleanup = new AtomicReference<>(cleanup);
		}

		@Override
		public void run() {
			if (!this.state.compareAndSet(State.QUEUED, State.RUNNING)) {
				return;
			}

			try {
				final Supplier<Path> supplier = this.supplier.getAndSet(null);
				this.result.compareAndSet(null, Objects.requireNonNull(supplier).get());
			} catch (Throwable throwable) {
				LOGGER.warn("Error during async pathfinding", throwable);
				publishFailedPath();
			} finally {
				cleanupOnce();
				publishFailedPathIfMissing();
				this.state.compareAndSet(State.RUNNING, State.DONE);
				this.completion.complete(this.result.get());
			}
		}

		private void cancel() {
			if (this.state.compareAndSet(State.QUEUED, State.CANCELLED)) {
				this.supplier.getAndSet(null);
				publishFailedPath();
				cleanupOnce();
				this.completion.complete(this.result.get());
				return;
			}

			// A running supplier still owns its evaluator/resources. Publish a failed
			// path to unblock readers, but let run() perform cleanup in its finally.
			if (this.state.get() == State.RUNNING) {
				publishFailedPath();
				this.completion.complete(this.result.get());
			}
		}

		private static CompletableFuture<Path> withTimeout(@NotNull WorkItem workItem) {
			return workItem.completion.orTimeout(60L, TimeUnit.SECONDS)
				.whenComplete((ignored, throwable) -> onCompletion(workItem, throwable));
		}

		private static void onCompletion(@NotNull WorkItem workItem, @Nullable Throwable throwable) {
			if (throwable == null) {
				return;
			}
			if (throwable instanceof java.util.concurrent.CancellationException) {
				workItem.cancel();
				return;
			}

			Throwable cause = throwable;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof TimeoutException) {
				LOGGER.warn("Async pathfinding timed out after 60 seconds", throwable);
			} else {
				LOGGER.warn("Error during async pathfinding", throwable);
			}
			workItem.cancel();
		}

		private void publishFailedPathIfMissing() {
			this.result.compareAndSet(null, failedPath());
		}

		private void publishFailedPath() {
			this.result.compareAndSet(null, failedPath());
		}

		private Path failedPath() {
			return new Path(List.of(), this.failedTarget, false);
		}

		private void cleanupOnce() {
			if (!this.cleanupComplete.compareAndSet(false, true)) {
				return;
			}
			final Runnable cleanup = this.cleanup.getAndSet(null);
			if (cleanup == null) {
				return;
			}
			try {
				cleanup.run();
			} catch (Throwable throwable) {
				LOGGER.error("Error releasing async pathfinding resources", throwable);
			}
		}
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

	@Override
	public boolean sameAs(@Nullable Path path) {
		if (path == this) {
			return true;
		}
		if (!this.ready || (path instanceof AsyncPath asyncPath && !asyncPath.ready)) {
			return false;
		}
		return super.sameAs(path);
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
				if (task instanceof WorkItem workItem) {
					workItem.cancel();
				}
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
