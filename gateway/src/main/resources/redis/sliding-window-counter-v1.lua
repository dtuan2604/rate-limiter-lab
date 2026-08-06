-- Atomic epoch-aligned Sliding Window Counter contract version 1.
-- KEYS[1]: policy/version/algorithm/identity key.
-- ARGV: contract version, limit, window milliseconds, request cost.

local maximum_safe_integer = 9007199254740991

local function canonical_non_negative_integer(value)
  return type(value) == "string"
      and (value == "0" or string.match(value, "^[1-9][0-9]*$") ~= nil)
end

local function safe_integer(value)
  local parsed = tonumber(value)
  if parsed == nil or parsed > maximum_safe_integer or math.floor(parsed) ~= parsed then
    return nil
  end
  return parsed
end

local function ceil_divide(numerator, denominator)
  if numerator == 0 then
    return 0
  end
  return math.floor((numerator - 1) / denominator) + 1
end

if #KEYS ~= 1 or #ARGV ~= 4
    or string.find(KEYS[1], ":a=sliding-window-counter:", 1, true) == nil then
  return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
end

for index = 1, 4 do
  if not canonical_non_negative_integer(ARGV[index]) or safe_integer(ARGV[index]) == nil then
    return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
  end
end

local contract_version = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local window = tonumber(ARGV[3])
local request_cost = tonumber(ARGV[4])

if contract_version ~= 1
    or limit < 1 or limit > 1000000
    or window < 1 or window > 86400000
    or request_cost < 1 or request_cost > limit
    or 3 * limit * window > maximum_safe_integer then
  return redis.error_reply("RATE_LIMIT_SCRIPT_ARGUMENT")
end

local redis_time = redis.call("TIME")
local now_ms = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
if now_ms < 0 or now_ms > maximum_safe_integer then
  return redis.error_reply("RATE_LIMIT_SCRIPT_TIME")
end
local current_window_id = math.floor(now_ms / window)
local current_window_start = current_window_id * window
local elapsed = now_ms - current_window_start

local stored = redis.call("HGETALL", KEYS[1])
local stored_window_id = current_window_id
local current_count = 0
local previous_count = 0
local rotation = 0

if #stored > 0 then
  if #stored ~= 6 then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
  local fields = {}
  for index = 1, #stored, 2 do
    local name = stored[index]
    local value = stored[index + 1]
    if (name ~= "window_id" and name ~= "current_count" and name ~= "previous_count")
        or fields[name] ~= nil
        or not canonical_non_negative_integer(value)
        or safe_integer(value) == nil then
      return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
    end
    fields[name] = value
  end
  if fields.window_id == nil or fields.current_count == nil or fields.previous_count == nil then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end
  stored_window_id = tonumber(fields.window_id)
  current_count = tonumber(fields.current_count)
  previous_count = tonumber(fields.previous_count)
  if current_count > limit or previous_count > limit then
    return redis.error_reply("RATE_LIMIT_STATE_MALFORMED")
  end

  if current_window_id < stored_window_id then
    return redis.error_reply("RATE_LIMIT_CLOCK_ROLLBACK")
  end
  local advancement = current_window_id - stored_window_id
  if advancement == 0 then
    rotation = 1
  elseif advancement == 1 then
    previous_count = current_count
    current_count = 0
    rotation = 2
  else
    previous_count = 0
    current_count = 0
    rotation = 3
  end
end

local weighted_numerator = current_count * window + previous_count * (window - elapsed)
local scaled_limit = limit * window
local scaled_cost = request_cost * window
local outcome = 0
if weighted_numerator + scaled_cost <= scaled_limit then
  current_count = current_count + request_cost
  weighted_numerator = weighted_numerator + scaled_cost
  outcome = 1
end

local weighted_estimate = ceil_divide(weighted_numerator, window)
local remaining_capacity = 0
if weighted_numerator < scaled_limit then
  remaining_capacity = math.floor((scaled_limit - weighted_numerator) / window)
end

local retry_after = 0
if outcome == 0 then
  local threshold = (limit - request_cost) * window
  local current_numerator = current_count * window
  if current_numerator <= threshold then
    if previous_count == 0 then
      return redis.error_reply("RATE_LIMIT_SCRIPT_RESULT")
    end
    retry_after = ceil_divide(weighted_numerator - threshold, previous_count)
  else
    if current_count == 0 then
      return redis.error_reply("RATE_LIMIT_SCRIPT_RESULT")
    end
    retry_after = window - elapsed
        + ceil_divide(current_numerator - threshold, current_count)
  end
end

local reset_after = 0
if current_count > 0 then
  reset_after = 2 * window - elapsed
elseif previous_count > 0 then
  reset_after = window - elapsed
end
if reset_after < 1 or reset_after > 2 * window
    or retry_after < 0 or retry_after > 2 * window then
  return redis.error_reply("RATE_LIMIT_SCRIPT_RESULT")
end

local expires_at = now_ms + reset_after
if expires_at > maximum_safe_integer then
  return redis.error_reply("RATE_LIMIT_SCRIPT_RESULT")
end
redis.call(
    "HSET", KEYS[1],
    "window_id", tostring(current_window_id),
    "current_count", tostring(current_count),
    "previous_count", tostring(previous_count))
redis.call("PEXPIREAT", KEYS[1], expires_at)

return {
  1, outcome, limit, window, request_cost,
  current_window_id, current_window_start, elapsed,
  current_count, previous_count, weighted_numerator, weighted_estimate,
  remaining_capacity, retry_after, reset_after, now_ms, reset_after, rotation
}
