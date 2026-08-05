# Use bounded millitoken arithmetic for distributed Token Bucket

**Status:** Accepted

## Context

Redis Lua uses IEEE-754 doubles and cannot safely represent arbitrary integers. Java and Lua must make identical continuous-refill decisions without floating-point token balances.

## Decision

Represent one token as 1,000 millitokens. Capacity, refill quantity, and request cost are integers from 1 through 100,000 tokens; initial tokens are zero through capacity; period is 1 through 86,400,000 milliseconds; and empty-to-full time is at most 30 days. Refill uses quotient/remainder arithmetic and persists the remainder. New credit rounds down. Elapsed time saturates at time-to-full before multiplication. The largest permitted bounded product is `100,000,000 × 86,400,000 = 8.64e15`, below `2^53-1`.

## Alternatives considered

Floating-point balances; arbitrary decimal scales; microtokens; direct unbounded elapsed multiplication; Redis modules.

## Consequences

Java and Lua can calculate exactly within stated bounds. Millitoken precision is externally visible in conservative floor behavior. Unsafe policies are rejected rather than clamped.

## Verification

Focused boundary tests, jqwik comparison with a BigInteger reference, shared behavioral traces, real-Redis integration, and repeated concurrency trials.

## Known limitations

Sub-millitoken refill is accumulated only through the persisted division remainder. Configurations outside the fixed bounds are unsupported.
