# Derive Sliding Window Counter retry timing analytically

**Status:** Accepted

## Context

The next fixed boundary is neither always necessary nor always sufficient for a rejected weighted request. Advising a retry too early creates avoidable repeated rejection.

## Decision

For `T=(L-C)W`, current numerator `N=cW+p(W-e)`, and time to boundary `b=W-e`: when `cW<=T`, return `ceil((N-T)/p)` milliseconds in the current window; otherwise return `b+ceil((cW-T)/c)` in the following window. Handle zero divisors by the matching analytical case and never iterate time units. The value is exact under no additional arrivals; HTTP `Retry-After` is its ceiling in seconds and therefore never early.

## Alternatives considered

Always return the next boundary; millisecond search; exponential/binary search; a conservative constant of two windows.

## Consequences

Clients receive the earliest safe time for the rejected cost under the documented state. The formula remains bounded by two windows and uses the same exact integer constraints as admission.

## Verification

Deterministic cases and properties compare the formula with bounded brute-force and `BigInteger` reference evaluation, including zero counts, costs greater than one, boundary rotation, and maximum policies.

## Known limitations

Additional requests can delay admission; the result assumes none arrive after the rejection.
