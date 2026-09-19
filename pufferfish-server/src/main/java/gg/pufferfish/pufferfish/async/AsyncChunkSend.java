package gg.pufferfish.pufferfish.async;

import gg.pufferfish.pufferfish.PufferfishConfig;
import gg.pufferfish.pufferfish.util.NamedThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncChunkSend {
	public static final ExecutorService POOL = PufferfishConfig.enableAsyncChunkSending ? newPool() : null;

	private static ExecutorService newPool() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(
			PufferfishConfig.asyncChunkSendingMaxThreads, PufferfishConfig.asyncChunkSendingMaxThreads,
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(),
			new NamedThreadFactory<>("Async Chunk Sending", AsyncChunkSendThread::new, Thread.NORM_PRIORITY),
			new ThreadPoolExecutor.CallerRunsPolicy()
		);
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	public static class AsyncChunkSendThread extends Thread {
		protected AsyncChunkSendThread(ThreadGroup group, Runnable task, String name) {
			super(group, task, name);
		}
	}
}
