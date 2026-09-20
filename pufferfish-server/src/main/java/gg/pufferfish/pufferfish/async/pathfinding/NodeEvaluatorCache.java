package gg.pufferfish.pufferfish.async.pathfinding;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
	private static final Int2ObjectOpenHashMap<ArrayDeque<NodeEvaluator>> threadLocalNodeEvaluators = new Int2ObjectOpenHashMap<>();
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
		final int nodeEvaluatorFeatures = NodeEvaluatorFeatures.fromNodeEvaluator(localNodeEvaluator);
		NodeEvaluator nodeEvaluator = threadLocalNodeEvaluators.computeIfAbsent(nodeEvaluatorFeatures, key -> new ArrayDeque<>()).poll();

		if (nodeEvaluator == null) {
			nodeEvaluator = generator.generate(NodeEvaluatorFeatures.unpack(nodeEvaluatorFeatures));
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

		final int nodeEvaluatorFeatures = NodeEvaluatorFeatures.fromNodeEvaluator(nodeEvaluator);
		threadLocalNodeEvaluators.computeIfAbsent(nodeEvaluatorFeatures, key -> new ArrayDeque<>()).offer(nodeEvaluator);
	}

	public static synchronized void removeNodeEvaluator(@NotNull final NodeEvaluator nodeEvaluator) {
		// Do not throw if a defensive cleanup path runs after the owning path
		// already released the lease.  The first release remains authoritative.
		leasedNodeEvaluators.remove(nodeEvaluator);
	}
}
