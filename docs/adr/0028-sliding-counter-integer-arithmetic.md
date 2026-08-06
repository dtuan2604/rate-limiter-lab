# Use bounded integer arithmetic for Sliding Window Counter

**Status:** Accepted

## Context

Sliding Window Counter weights the previous window by the unelapsed fraction of the current window. Floating-point comparison in Java and Redis Lua could disagree near an admission boundary and Lua numbers cease representing every integer above `2^53`.

## Decision

Use epoch milliseconds and compare scaled integers. For limit `L`, window `W`, cost `C`, elapsed `e`, previous count `p`, and current count `c`, compute `N=c*W+p*(W-e)` and allow only when `N+C*W<=L*W`. Expose `ceil(N/W)` as the estimate and `max(0,floor((L*W-N)/W))` as immediate whole-cost capacity. The explicit zero saturation covers conservative same-window clock rollback where valid stored counts can temporarily weigh above `L`; it is not arithmetic-overflow clamping. Validate `L<=1,000,000`, `W<=86,400,000`, `C<=L`, and counts `<=L` at every trust boundary. No admission calculation uses floating-point division or clamps overflow.

## Alternatives considered

Lua division; Java decimal arithmetic with conversion; storing a rounded estimate; an exact timestamp log.

## Consequences

Java reference and Redis decisions agree exactly. The largest documented intermediate is `3*L*W=2.592e14`, below `2^53-1`. Estimate output rounds upward while remaining capacity rounds downward.

## Verification

Deterministic boundary tests and jqwik properties compare production-compatible `long` arithmetic with a `BigInteger` rational reference at maximum bounds and generated transitions.

## Known limitations

The arithmetic is exact for the selected two-window approximation, not an exact sliding timestamp log.
