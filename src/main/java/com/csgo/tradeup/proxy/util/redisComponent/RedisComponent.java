package com.csgo.tradeup.proxy.util.redisComponent;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** 字符串缓存统一使用 Duration；写入和值过期在同一条 Redis 命令中完成。 */
@Component
public class RedisComponent {
    @Resource private StringRedisTemplate stringRedisTemplate;
    public void setString(String key, String data, Duration ttl) { stringRedisTemplate.opsForValue().set(key, data, ttl); }
    public String getString(String key) { return stringRedisTemplate.opsForValue().get(key); }

    /**
     * 查询剩余毫秒，不刷新有效期；负数沿用 Redis 的不存在或无过期时间语义。
     */
    public long remainingMillis(String key) {
        Long remaining = stringRedisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        if (remaining == null) throw new IllegalStateException("Redis有效期查询失败");
        return remaining;
    }
    public boolean delKey(String key) { return Boolean.TRUE.equals(stringRedisTemplate.delete(key)); }
    public boolean setIfAbsent(String key, String data, Duration ttl) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, data, ttl));
    }
    /** 原子校验并消费验证码，避免并发重放。 */
    public boolean consume(String key, String value) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
        return Long.valueOf(1).equals(stringRedisTemplate.execute(script, Collections.singletonList(key), value));
    }
    public long increment(String key, Duration ttl) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('pexpire',KEYS[1],ARGV[1]) end; return n", Long.class);
        Long count = stringRedisTemplate.execute(script, Collections.singletonList(key), Long.toString(ttl.toMillis()));
        if (count == null) throw new IllegalStateException("Redis计数失败");
        return count;
    }
}
