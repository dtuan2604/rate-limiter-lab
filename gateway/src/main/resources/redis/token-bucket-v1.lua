-- Atomic bounded-millitoken Token Bucket contract version 1.
-- KEYS[1]: policy/version/algorithm/identity key.
-- ARGV: version, capacityScaled, initialScaled, refillScaled, periodMs,
--       costScaled, activationMs, maximumRollbackMs.

local function canonical_non_negative_integer(value)
  return type(value) == "string"
      and (value == "0" or string.match(value, "^[1-9][0-9]*$") ~= nil)
end

local maximum_safe_integer = 9007199254740991

local function safe_integer(value)
  local parsed = tonumber(value)
  if parsed == nil or parsed > maximum_safe_integer or math.floor(parsed) ~= parsed then
    return nil
  end
  return parsed
end

local function ceil_divide(numerator, denominator)
  return math.floor((numerator - 1) / denominator) + 1
end

if #KEYS ~= 1 or #ARGV ~= 8
    or string.find(KEYS[1], ":a=token-bucket:", 1, true) == nil then
  return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
end

for index = 1, 8 do
  if not canonical_non_negative_integer(ARGV[index]) or safe_integer(ARGV[index]) == nil then
    return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
  end
end

local contract_version = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local initial_tokens = tonumber(ARGV[3])
local refill_tokens = tonumber(ARGV[4])
local refill_period = tonumber(ARGV[5])
local request_cost = tonumber(ARGV[6])
local activation_ms = tonumber(ARGV[7])
local maximum_rollback = tonumber(ARGV[8])

if contract_version ~= 1
    or capacity < 1000 or capacity > 100000000
    or initial_tokens < 0 or initial_tokens > capacity
    or refill_tokens < 1000 or refill_tokens > 100000000
    or refill_period < 1 or refill_period > 86400000
    or request_cost < 1000 or request_cost > capacity
    or maximum_rollback ~= 300000
    or (math.floor((capacity - 1) / refill_tokens) + 1) * refill_period > 2592000000 then
  return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
end

local redis_time = redis.call("TIME")
local now_ms = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
local stored = redis.call("HGETALL", KEYS[1])
local tokens = initial_tokens
local last_ms = activation_ms
local remainder = 0
local reconstructed = 1

if #stored > 0 then
  if #stored ~= 6 then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
  local fields = {}
  for index = 1, #stored, 2 do
    local name = stored[index]
    local value = stored[index + 1]
    if (name ~= "tokens" and name ~= "last_ms" and name ~= "refill_remainder")
        or fields[name] ~= nil or not canonical_non_negative_integer(value) then
      return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
    end
    fields[name] = value
  end
  if fields.tokens == nil or fields.last_ms == nil or fields.refill_remainder == nil then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
  tokens = tonumber(fields.tokens)
  last_ms = tonumber(fields.last_ms)
  remainder = tonumber(fields.refill_remainder)
  if tokens > maximum_safe_integer
      or last_ms > maximum_safe_integer
      or remainder > maximum_safe_integer then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
  reconstructed = 0
end

if tokens < 0 or tokens > capacity
    or remainder < 0 or remainder >= refill_period
    or (tokens == capacity and remainder ~= 0) then
  return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
end

local elapsed = 0
local rollback_delay = 0
if now_ms >= last_ms then
  elapsed = now_ms - last_ms
  last_ms = now_ms
else
  rollback_delay = last_ms - now_ms
  if rollback_delay > maximum_rollback then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
end

local function time_for(needed, carried_remainder)
  if needed == 0 then
    return 0
  end
  return ceil_divide(needed * refill_period - carried_remainder, refill_tokens)
end

if tokens < capacity and elapsed > 0 then
  local deficit = capacity - tokens
  local time_to_full = time_for(deficit, remainder)
  if elapsed >= time_to_full then
    tokens = capacity
    remainder = 0
  else
    local whole_periods = math.floor(elapsed / refill_period)
    local partial_ms = elapsed % refill_period
    local partial_numerator = partial_ms * refill_tokens + remainder
    tokens = tokens
        + whole_periods * refill_tokens
        + math.floor(partial_numerator / refill_period)
    remainder = partial_numerator % refill_period
    if tokens >= capacity then
      tokens = capacity
      remainder = 0
    end
  end
end

local outcome = 0
if tokens >= request_cost then
  tokens = tokens - request_cost
  outcome = 1
end

local retry_after = 0
if outcome == 0 then
  retry_after = rollback_delay + time_for(request_cost - tokens, remainder)
end
local reset_after = rollback_delay + time_for(capacity - tokens, remainder)
if reset_after < 1 or reset_after > 2592300000 then
  return redis.error_reply("RATE_LIMIT_SCRIPT_RESULT")
end

redis.call(
    "HSET", KEYS[1],
    "tokens", tostring(tokens),
    "last_ms", tostring(last_ms),
    "refill_remainder", tostring(remainder))
redis.call("PEXPIRE", KEYS[1], reset_after)

return {
  1, outcome, capacity, tokens, request_cost, refill_tokens, refill_period,
  retry_after, reset_after, now_ms, reset_after, remainder, reconstructed
}
