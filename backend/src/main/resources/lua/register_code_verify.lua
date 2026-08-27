-- 注册验证码「校验 + 消费」原子脚本（防止应用层读-改-写竞态，见宪法 A.5.6）
--
-- 一次脚本内完成：比对验证码 → 错误时累加尝试计数 → 达上限作废验证码 → 正确时双键删除。
-- 应用层绝不拆成 GET/INCR/DEL 多步调用，避免并发重放绕过次数限制。
--
-- KEYS[1] = 验证码键 auth:reg:code:{email}
-- KEYS[2] = 错误尝试计数键 auth:reg:att:{email}
-- ARGV[1] = 用户提交的验证码（6 位数字字符串）
-- ARGV[2] = 最大允许尝试次数（达到即作废验证码，防爆破）
-- ARGV[3] = 尝试计数键过期秒数（与验证码 TTL 同步，窗口外自动清零）
--
-- 返回值（字符串）：
--   VERIFIED — 校验通过，验证码已消费（两键均删除）
--   EXPIRED  — 验证码不存在或已过期
--   MISMATCH — 验证码错误（已累计错误次数）
--   LOCKED   — 错误次数达到上限，验证码已作废
local stored = redis.call('GET', KEYS[1])
if not stored then
    return 'EXPIRED'
end

if stored ~= ARGV[1] then
    -- 错误尝试计数：首次写入设置过期窗口，后续仅累加
    local attempts = redis.call('INCR', KEYS[2])
    if attempts == 1 then
        redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
    end
    if attempts >= tonumber(ARGV[2]) then
        -- 达到上限：立即作废验证码并清零计数，强迫用户重新获取
        redis.call('DEL', KEYS[1], KEYS[2])
        return 'LOCKED'
    end
    return 'MISMATCH'
end

-- 校验通过：消费验证码（一次性），同步清除错误计数
redis.call('DEL', KEYS[1], KEYS[2])
return 'VERIFIED'
