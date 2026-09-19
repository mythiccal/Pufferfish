package gg.pufferfish.pufferfish.util;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class NamedThreadFactory<T extends Thread> implements ThreadFactory {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final ThreadGroup group;
	private final AtomicInteger threadNumber = new AtomicInteger(1);
	private final String namePrefix;
	private final ThreadBuilderFunction<T> typeOfThread;
	private final int priority;

	public NamedThreadFactory(String name, ThreadBuilderFunction<T> typeOfThread, int priority) {
		this.typeOfThread = typeOfThread;
		this.priority = priority;
		this.group = Thread.currentThread().getThreadGroup();
		this.namePrefix = name + "-";
	}

	@Override
	public Thread newThread(@NotNull Runnable runnable) {
		T thread = typeOfThread.apply(this.group, runnable, this.namePrefix + this.threadNumber.getAndIncrement());
		thread.setUncaughtExceptionHandler((threadx, throwable) -> {
			LOGGER.error("Caught exception in thread {} from {}", threadx, runnable);
			LOGGER.error("", throwable);
		});
		if (thread.getPriority() != this.priority) {
			thread.setPriority(this.priority);
		}
		return thread;
	}

	@FunctionalInterface
	public interface ThreadBuilderFunction<T extends Thread> {
		T apply(ThreadGroup threadGroup, Runnable runnable, String name);
	}
}
