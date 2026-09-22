package gg.pufferfish.pufferfish.async.world;

import ca.spottedleaf.moonrise.common.util.TickThread;

import java.util.concurrent.ThreadFactory;

public class ServerLevelTickThreadFactory implements ThreadFactory {
    private final String worldName;

    public ServerLevelTickThreadFactory(String worldName) {
        this.worldName = worldName;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        TickThread.ServerLevelTickThread tickThread = new TickThread.ServerLevelTickThread(runnable, this.worldName + " - ServerLevel Tick Worker");

        if (tickThread.isDaemon()) {
            tickThread.setDaemon(false);
        }

        if (tickThread.getPriority() != Thread.NORM_PRIORITY) {
            tickThread.setPriority(Thread.NORM_PRIORITY);
        }

        return tickThread;
    }
}
