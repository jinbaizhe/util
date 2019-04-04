package com.parker.util.util;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Component
public class RedisUtil implements InitializingBean {
    private Jedis redis = null;
    private JedisPool pool = null;

    @Value("${redis.auth}")
    private String auth = null;

    @Value("${redis.host}")
    private String host = null;

    @Value("${redis.max-total}")
    private String maxTotal = "20";

    @Value("${redis.max-idle}")
    private String maxIdle = "5";

    public RedisUtil() {
    }

    public String getMaxTotal() {
        return this.maxTotal;
    }

    public void setMaxTotal(String maxTotal) {
        this.maxTotal = maxTotal;
    }

    public String getMaxIdle() {
        return this.maxIdle;
    }

    public void setMaxIdle(String maxIdle) {
        this.maxIdle = maxIdle;
    }

    public void setRedis(Jedis redis) {
        this.redis = redis;
    }

    public void setAuth(String auth) {
        this.auth = auth;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void init() {
        if (this.pool == null) {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(Integer.parseInt(this.maxTotal));
            config.setMaxIdle(Integer.parseInt(this.maxIdle));
            config.setMaxWaitMillis(5000L);
            config.setTestOnBorrow(true);
            this.pool = new JedisPool(config, this.host, 6379, 8000, this.auth);
        }

    }

    public Jedis getInstance() {
        return this.pool.getResource();
    }

    public void close() {
        this.pool.returnResource(this.redis);
        this.pool.close();
    }

    public void destroy(Jedis jedis) {
        this.pool.returnResource(jedis);
    }

    public Jedis getResource() {
        return this.pool.getResource();
    }

    public JedisPool getPool() {
        return this.pool;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }
}
