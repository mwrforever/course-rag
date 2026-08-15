-- P3 A11: RT 一次性旋转原子标记（检查+置位单条脚本，消除 TOCTOU）
-- KEYS[1] = auth:rt:used:{jtiRt}，ARGV[1] = TTL 秒
-- 返回 1 = 首次使用（本次抢占成功）；0 = 已被使用
if redis.call('GET', KEYS[1]) == '1' then
    return 0
end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
return 1
