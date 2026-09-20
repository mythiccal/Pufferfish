package gg.pufferfish.pufferfish.async.pathfinding;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class NodeEvaluatorCache {
	private static final Object2ObjectOpenHashMap<NodeEvaluatorFeatures.PoolKey, ArrayDeque<NodeEvaluator>> threadLocalNodeEvaluators = new Object2ObjectOpenHashMap<>();
	/**
	 * Tracks only currently leased evaluators. Identity semantics are important
	 * here, and unlike the old evaluator-to-generator map this does not retain a
	 * generator (and its Mob) for the lifetime of the cache.
	 */
	private static final Set<NodeEvaluator> leasedNodeEvaluators =
		Collections.newSetFromMap(new IdentityHashMap<>());
	public static final ThreadLocal<BinaryHeap> HEAP_LOCAL = ThreadLocal.withInitial(BinaryHeap::new);
	public static final ThreadLocal<Node[]> NEIGHBORS_LOCAL = ThreadLocal.withInitial(() -> new Node[32]);

	private NodeEvaluatorCache() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	public static synchronized @NotNull NodeEvaluator takeNodeEvaluator(@NotNull NodeEvaluatorGenerator generator, @NotNull NodeEvaluator localNodeEvaluator) {
		final NodeEvaluatorFeatures.PoolKey key = NodeEvaluatorFeatures.poolKey(localNodeEvaluator);
		NodeEvaluator nodeEvaluator = threadLocalNodeEvaluators.computeIfAbsent(key, ignored -> new ArrayDeque<>()).poll();

		if (nodeEvaluator == null) {
			nodeEvaluator = generator.generate(NodeEvaluatorFeatures.unpack(key.features()));
		}

		Validate.notNull(nodeEvaluator, "NodeEvaluator generator returned null");
		Validate.isTrue(leasedNodeEvaluators.add(nodeEvaluator), "NodeEvaluator already leased");

		return nodeEvaluator;
	}

	public static synchronized void returnNodeEvaluator(@NotNull final NodeEvaluator nodeEvaluator) {
		// Cleanup is deliberately idempotent: timeout, cancellation, and a
		// worker finally block can all converge on the same lease.
		if (!leasedNodeEvaluators.remove(nodeEvaluator)) {
			return;
		}

		final NodeEvaluatorFeatures.PoolKey key = NodeEvaluatorFeatures.poolKey(nodeEvaluator);
		threadLocalNodeEvaluators.computeIfAbsent(key, ignored -> new ArrayDeque<>()).offer(nodeEvaluator);
	}

	public static synchronized void removeNodeEvaluator(@NotNull final NodeEvaluator nodeEvaluator) {
		// Do not throw if a defensive cleanup path runs after the owning path
		// already released the lease.  The first release remains authoritative.
		leasedNodeEvaluators.remove(nodeEvaluator);
	}
}
