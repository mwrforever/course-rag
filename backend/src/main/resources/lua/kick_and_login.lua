-- kick_and_login.lua — 设备互踢原子操作
--
-- 功能：新设备登录时，原子性地覆盖活跃设备指针 + 将旧设备 jti 加入黑名单。
-- Redis 是执法层（纳秒级生效），PG 是审计层（异步落盘）。
--
-- KEYS[1] = auth:cur:{userId}:{deviceType}  (当前活跃设备指针)
-- ARGV[1] = new_jti_at|new_jti_rt|new_login_id  (新设备标记)
-- ARGV[2] = cur_key_ttl  (= 新 RT 有效期，秒)
-- ARGV[3] = old_at_ttl  (旧 AT 黑名单 TTL = accessTokenExpiry 全量，秒；H-2：不再传 Java 预读的
--                        剩余 TTL——预读与 Lua 内 GET 分离存在 TOCTOU，旧设备是否存在由本脚本
--                        第 1 步 GET 决定，TTL 全量多留无害，过期自动清理)
-- ARGV[4] = old_rt_ttl  (旧 RT 黑名单 TTL = refreshTokenExpiry 全量，秒；同上)
-- ARGV[5] = kick_reason  ("DEVICE_KICKED")
-- ARGV[6] = current_timestamp  (由 Java 传入，避免 Lua 内调 TIME 导致脚本不可复制)

-- 1. 获取旧设备信息
local old = redis.call('GET', KEYS[1])

-- 2. 写入新设备标记（覆盖旧设备）
redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), ARGV[1])

-- 3. 如果存在旧设备，将其 jti 加入黑名单
local kicked = false
local old_jti_at = ''
local old_jti_rt = ''

if old and old ~= '' then
    -- 解析 "jti_at|jti_rt|loginId"
    local parts = {}
    for part in string.gmatch(old, '[^|]+') do
        table.insert(parts, part)
    end
    old_jti_at = parts[1] or ''
    old_jti_rt = parts[2] or ''

    if old_jti_at ~= '' and tonumber(ARGV[3]) > 0 then
        redis.call('SETEX', 'auth:bl:' .. old_jti_at, tonumber(ARGV[3]),
                   ARGV[5] .. '|' .. ARGV[6])
    end
    if old_jti_rt ~= '' and tonumber(ARGV[4]) > 0 then
        redis.call('SETEX', 'auth:bl:' .. old_jti_rt, tonumber(ARGV[4]),
                   ARGV[5] .. '|' .. ARGV[6])
    end
    kicked = true
end

return cjson.encode({
    kicked = kicked,
    old_jti_at = old_jti_at,
    old_jti_rt = old_jti_rt
})
