-- disable_user.lua — 禁用用户批量入黑名单
--
-- 功能：管理员禁用用户时，批量将该用户所有活跃 session 的 jti 加入黑名单。
-- 防重入：NX + EX 300s，防止并发重复执行。
--
-- KEYS[1] = auth:disable:{userId}  (防重入标记)
-- ARGV[1] = userId
-- ARGV[2] = adminUserId
-- ARGV[3] = reason  ("USER_DISABLED")
-- ARGV[4] = timestamp
-- ARGV[5], ARGV[6] = jti1, ttl1
-- ARGV[7], ARGV[8] = jti2, ttl2
-- ...  (jti/ttl 交替排列)

-- 防重入：SET NX EX 300s
local disabled = redis.call('SET', KEYS[1], '1', 'NX', 'EX', 300)
if not disabled then
    return cjson.encode({ status = 'ALREADY_PROCESSING', disabled_jti_count = 0 })
end

local reason = ARGV[3]
local timestamp = ARGV[4]
local count = 0

-- jti/ttl 对从 ARGV[5] 开始
local pair_count = math.floor((#ARGV - 4) / 2)

for i = 1, pair_count do
    local jti = ARGV[4 + (i - 1) * 2 + 1]
    local ttl = tonumber(ARGV[4 + (i - 1) * 2 + 2])
    if jti and jti ~= '' and ttl and ttl > 0 then
        redis.call('SETEX', 'auth:bl:' .. jti, ttl, reason .. '|' .. timestamp)
        count = count + 1
    end
end

return cjson.encode({ status = 'OK', disabled_jti_count = count })
