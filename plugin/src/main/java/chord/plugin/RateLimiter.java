package chord.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每玩家令牌桶限速：容量 = burst，速率 = notesPerSecond/秒。
 * 惰性补充，无调度器（Folia 无全局调度器，见设计文档 §3）。
 */
public final class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillMillis;
        long drops;
    }

    private final Map<UUID, Bucket> buckets = new HashMap<>();
    private final double capacity;
    private final double refillPerMillis;

    public RateLimiter(double notesPerSecond, double burst) {
        this.capacity = burst;
        this.refillPerMillis = notesPerSecond / 1000.0;
    }

    /** @return true = 放行 */
    public synchronized boolean tryAcquire(UUID playerId) {
        Bucket bucket = buckets.computeIfAbsent(playerId, k -> {
            Bucket b = new Bucket();
            b.tokens = capacity;
            b.lastRefillMillis = System.currentTimeMillis();
            return b;
        });
        long now = System.currentTimeMillis();
        bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastRefillMillis) * refillPerMillis);
        bucket.lastRefillMillis = now;
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        bucket.drops++;
        return false;
    }

    public synchronized long drops(UUID playerId) {
        Bucket bucket = buckets.get(playerId);
        return bucket == null ? 0 : bucket.drops;
    }

    public synchronized void forget(UUID playerId) {
        buckets.remove(playerId);
    }
}
