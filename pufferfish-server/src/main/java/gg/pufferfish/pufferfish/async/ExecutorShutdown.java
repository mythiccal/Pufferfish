package gg.pufferfish.pufferfish.async;

import gg.pufferfish.pufferfish.async.tracking.ParallelEntityTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;

public class ExecutorShutdown {
	public static final Logger LOGGER = LogManager.getLogger(ExecutorShutdown.class.getSimpleName());

	public static void shutdown() {
		if (AsyncChunkSend.POOL != null) {
			LOGGER.info("Shutting down async chunk send executor...");
			AsyncChunkSend.POOL.shutdown();

			try {
				AsyncChunkSend.POOL.awaitTermination(10L, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			}
		}

		if (ParallelEntityTracker.TRACKER_EXECUTOR != null) {
			LOGGER.info("Shutting down parallel entity tracker executor...");
			ParallelEntityTracker.TRACKER_EXECUTOR.shutdown();

			try {
				ParallelEntityTracker.TRACKER_EXECUTOR.awaitTermination(10L, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			}
		}
	}
}
