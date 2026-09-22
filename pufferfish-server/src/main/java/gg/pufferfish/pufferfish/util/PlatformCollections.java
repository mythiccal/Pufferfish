package gg.pufferfish.pufferfish.util;

import gg.pufferfish.pufferfish.PufferfishConfig;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Collections that stay plain unless parallel world ticking is enabled, in which
 * case thread-safe equivalents are returned. Adapted from DivineMC.
 */
public final class PlatformCollections {
    private PlatformCollections() { }

    private static boolean pwt() {
        return PufferfishConfig.enableParallelWorldTicking;
    }

    public static <T> Queue<T> priorityQueue(final int initialCapacity, final Comparator<? super T> comparator) {
        return pwt() ? new PriorityBlockingQueue<>(initialCapacity, comparator) : new PriorityQueue<>(initialCapacity, comparator);
    }

    public static <T> Queue<T> queue() {
        return pwt() ? new ConcurrentLinkedQueue<>() : new ArrayDeque<>();
    }

    public static Long2LongMap long2LongMap(final long defaultReturnValue) {
        final Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(defaultReturnValue);
        return pwt() ? Long2LongMaps.synchronize(map) : map;
    }

    public static <T> Set<T> customHashSet(final Hash.Strategy<? super T> strategy) {
        final ObjectOpenCustomHashSet<T> set = new ObjectOpenCustomHashSet<>(strategy);
        return pwt() ? ObjectSets.synchronize(set) : set;
    }

    public static <V> Long2ObjectMap<V> long2ObjectMap() {
        final Long2ObjectMap<V> map = new Long2ObjectOpenHashMap<>();
        return pwt() ? Long2ObjectMaps.synchronize(map) : map;
    }
}
