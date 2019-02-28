package com.parker.util.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import javax.annotation.Resource;
import java.util.Iterator;
import java.util.Set;

@Component
public class RedisManager {

    private final Logger logger = LoggerFactory.getLogger(RedisManager.class);

    @Resource
    private RedisUtil    redisUtil;


    /**
     * @Title: get
     * @Description:  获取key
     * @param dbIndex
     * @param key
     * @return
     * @return: String
     */
    public String get(int dbIndex, String key) {
        Jedis jedis = null;
        try {
            jedis = redisUtil.getInstance();
            jedis.select(dbIndex);
            return jedis.get(key);
        } catch (Exception e) {
            logger.error("redis查询异常[{}]", key, e);
        } finally {
            redisUtil.destroy(jedis);
        }
        return null;
    }

    /**
     * @Title: setex
     * @Description:  设置固定时间的key
     * @param dbIndex
     * @param key
     * @param val
     * @param seconds
     * @return: void
     */
    public void setex(int dbIndex, String key, String val, int seconds) {
        Jedis jedis = null;
        try {
            jedis = redisUtil.getInstance();
            jedis.select(dbIndex);
            jedis.setex(key, seconds, val);
        } catch (Exception e) {
            logger.error("redis设置有效期key异常", e);
        } finally {
            redisUtil.destroy(jedis);
        }
    }

    /**
     * @Title: set
     * @Description: 设置key对应的值
     * @param dbIndex
     * @param key
     * @param val
     * @return: void
     */
    public void set(int dbIndex, String key, String val) {
        Jedis jedis = null;
        try {
            jedis = redisUtil.getInstance();
            jedis.select(dbIndex);
            jedis.set(key, val);
        } catch (Exception e) {
            logger.error("redis设置key异常", e);
        } finally {
            redisUtil.destroy(jedis);
        }
    }

    /**
     * @Title: delMatchKey
     * @Description:  清除缓存
     * @param dbIndex
     * @param keyPrefix
     * @return: void
     */
    public void delMatchKey(int dbIndex, String keyPrefix) {
        Jedis jedis = null;
        try {
            jedis = redisUtil.getInstance();
            jedis.select(dbIndex);
            Set<String> set = jedis.keys(keyPrefix + "*");
            Iterator<String> it = set.iterator();
            while (it.hasNext()) {
                jedis.del(it.next());
            }
        } catch (Exception e) {
            logger.error("清除缓存失败[{}]", keyPrefix, e);
        } finally {
            redisUtil.destroy(jedis);
        }
    }

    /**
     * @Title: decrBy
     * @Description: key自减
     * @param dbIndex
     * @param key
     * @param val
     * @return
     * @return: Long
     */
    public Long decrBy(int dbIndex, String key, long val) {
        Jedis jedis = null;
        try {
            jedis = redisUtil.getInstance();
            jedis.select(dbIndex);
            return jedis.decrBy(key, val);
        } catch (Exception e) {
            logger.error("redis自减异常", e);
            return null;
        } finally {
            redisUtil.destroy(jedis);
        }
    }

    /**
     * @Title: incrBy
     * @Description: key自增
     * @param dbIndex
     * @param key
     * @param val
     * @return
     * @return: Long
     */
    public Long incrBy(int dbIndex, String key, long val) {
        Jedis jedis = null;
        try {
            jedis = redisUtil.getInstance();
            jedis.select(dbIndex);
            return jedis.incrBy(key, val);
        } catch (Exception e) {
            logger.error("redis自增异常", e);
            return null;
        } finally {
            redisUtil.destroy(jedis);
        }
    }
}
