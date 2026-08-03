-- Atomic fixed-window counter contract version 1.
-- KEYS[1] is the deterministic counter key for ARGV[5]'s candidate window.
-- ARGV: contractVersion, limit, windowMilliseconds, cost, candidateWindowId.

local function canonical_non_negative_integer(value)
  return type(value) == "string"
      and (value == "0" or string.match(value, "^[1-9][0-9]*$") ~= nil)
end

if #KEYS ~= 1 or #ARGV ~= 5 then
  return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
end

for index = 1, 5 do
  if not canonical_non_negative_integer(ARGV[index]) then
    return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
  end
end

local contract_version = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local window_milliseconds = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])
local candidate_window_id = tonumber(ARGV[5])

if contract_version ~= 1
    or limit < 1 or limit > 1000000
    or window_milliseconds < 1 or window_milliseconds > 86400000
    or cost ~= 1 then
  return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
end

local redis_time = redis.call("TIME")
local redis_now_milliseconds = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
local window_id = math.floor(redis_now_milliseconds / window_milliseconds)
local reset_at_milliseconds = (window_id + 1) * window_milliseconds
local ttl_milliseconds = reset_at_milliseconds - redis_now_milliseconds
local expected_suffix = ":w=" .. tostring(window_id)

if candidate_window_id ~= window_id
    or string.sub(KEYS[1], -string.len(expected_suffix)) ~= expected_suffix then
  return {
    1, 2, 0, limit, limit, 0, reset_at_milliseconds,
    redis_now_milliseconds, window_id, ttl_milliseconds
  }
end

local stored = redis.call("GET", KEYS[1])
local current = 0
if stored then
  if not canonical_non_negative_integer(stored) then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
  current = tonumber(stored)
  if current > limit then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
end

local outcome = 0
local retry_after_milliseconds = ttl_milliseconds
if cost <= limit - current then
  current = redis.call("INCRBY", KEYS[1], cost)
  outcome = 1
  retry_after_milliseconds = 0
end

if stored or outcome == 1 then
  redis.call("PEXPIREAT", KEYS[1], reset_at_milliseconds)
end

return {
  1, outcome, current, limit - current, limit, retry_after_milliseconds,
  reset_at_milliseconds, redis_now_milliseconds, window_id, ttl_milliseconds
}
