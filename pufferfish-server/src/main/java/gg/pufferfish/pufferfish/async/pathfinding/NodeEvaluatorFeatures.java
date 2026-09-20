package gg.pufferfish.pufferfish.async.pathfinding;

import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

public record NodeEvaluatorFeatures(
	NodeEvaluatorType type,
	boolean canPassDoors,
	boolean canFloat,
	boolean canWalkOverFences,
	boolean canOpenDoors,
	boolean allowBreaching
) {
	private static final NodeEvaluatorType[] NODE_EVALUATOR_TYPES = NodeEvaluatorType.values();
	private static final int TYPE_MASK = 0xFF;
	private static final int CAN_PASS_DOORS = 1 << 8;
	private static final int CAN_FLOAT = 1 << 9;
	private static final int CAN_WALK_OVER_FENCES = 1 << 10;
	private static final int CAN_OPEN_DOORS = 1 << 11;
	private static final int ALLOW_BREACHING = 1 << 12;

	public static int fromNodeEvaluator(NodeEvaluator nodeEvaluator) {
		NodeEvaluatorType type = NodeEvaluatorType.fromNodeEvaluator(nodeEvaluator);
		boolean canPassDoors = nodeEvaluator.canPassDoors();
		boolean canFloat = nodeEvaluator.canFloat();
		boolean canWalkOverFences = nodeEvaluator.canWalkOverFences();
		boolean canOpenDoors = nodeEvaluator.canOpenDoors();
		boolean allowBreaching = nodeEvaluator instanceof SwimNodeEvaluator swimNodeEvaluator && swimNodeEvaluator.allowBreaching();

		return pack(type, canPassDoors, canFloat, canWalkOverFences, canOpenDoors, allowBreaching);
	}

	public static PoolKey poolKey(NodeEvaluator nodeEvaluator) {
		return new PoolKey(nodeEvaluator.getClass(), fromNodeEvaluator(nodeEvaluator));
	}

	public static int pack(NodeEvaluatorType type,
						   boolean canPassDoors,
						   boolean canFloat,
						   boolean canWalkOverFences,
						   boolean canOpenDoors,
						   boolean allowBreaching) {
		int value = type.ordinal() & TYPE_MASK;
		if (canPassDoors) value |= CAN_PASS_DOORS;
		if (canFloat) value |= CAN_FLOAT;
		if (canWalkOverFences) value |= CAN_WALK_OVER_FENCES;
		if (canOpenDoors) value |= CAN_OPEN_DOORS;
		if (allowBreaching) value |= ALLOW_BREACHING;

		return value;
	}

	public static NodeEvaluatorFeatures unpack(int value) {
		NodeEvaluatorType type = NODE_EVALUATOR_TYPES[value & TYPE_MASK];
		boolean canPassDoors = (value & CAN_PASS_DOORS) != 0;
		boolean canFloat = (value & CAN_FLOAT) != 0;
		boolean canWalkOverFences = (value & CAN_WALK_OVER_FENCES) != 0;
		boolean canOpenDoors = (value & CAN_OPEN_DOORS) != 0;
		boolean allowBreaching = (value & ALLOW_BREACHING) != 0;

		return new NodeEvaluatorFeatures(type, canPassDoors, canFloat, canWalkOverFences, canOpenDoors, allowBreaching);
	}

	public record PoolKey(Class<?> evaluatorClass, int features) {
	}
}
