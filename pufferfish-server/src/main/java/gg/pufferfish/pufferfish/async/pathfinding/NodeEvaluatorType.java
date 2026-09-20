package gg.pufferfish.pufferfish.async.pathfinding;

import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

public enum NodeEvaluatorType {
	WALK,
	SWIM,
	AMPHIBIOUS,
	FLY,
	WALK_SUBCLASS,
	SWIM_SUBCLASS,
	AMPHIBIOUS_SUBCLASS,
	FLY_SUBCLASS,
	OTHER;

	public static NodeEvaluatorType fromNodeEvaluator(NodeEvaluator nodeEvaluator) {
		final Class<?> type = nodeEvaluator.getClass();
		if (type == FlyNodeEvaluator.class) {
			return FLY;
		}
		if (type == SwimNodeEvaluator.class) {
			return SWIM;
		}
		if (type == AmphibiousNodeEvaluator.class) {
			return AMPHIBIOUS;
		}
		if (type == WalkNodeEvaluator.class) {
			return WALK;
		}
		// AmphibiousNodeEvaluator extends WalkNodeEvaluator; check the more specific
		// parents before Walk so FrogNodeEvaluator is not collapsed into WALK_SUBCLASS.
		if (nodeEvaluator instanceof FlyNodeEvaluator) {
			return FLY_SUBCLASS;
		}
		if (nodeEvaluator instanceof SwimNodeEvaluator) {
			return SWIM_SUBCLASS;
		}
		if (nodeEvaluator instanceof AmphibiousNodeEvaluator) {
			return AMPHIBIOUS_SUBCLASS;
		}
		if (nodeEvaluator instanceof WalkNodeEvaluator) {
			return WALK_SUBCLASS;
		}
		return OTHER;
	}
}
