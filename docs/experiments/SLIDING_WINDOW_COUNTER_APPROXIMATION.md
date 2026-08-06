# Sliding Window Counter Approximation Experiment

## Question

How do Fixed Window, the weighted Sliding Window Counter, and an exact in-memory Sliding Window Log differ around boundaries and for clustered traffic?

## Configuration and method

`SlidingWindowCounterApproximationTest` uses deterministic hand-built traces plus 10,000 generated traces with seed `0x5C1D1A6`. The generated window is 1,000 ms, each trace contains 0..20 cost-one events in the preceding window, and observation time is selected in the following window. Exact-log usage counts timestamps in `(now-W, now]`; counter usage uses `p*(W-e)/W` in scaled numerator units. The real-Redis compatibility test separately compares admission, state counts, and numerator among the Phase 1 in-memory counter, the pure Redis-compatible transition, the production Lua adapter using returned Redis time, and an exact timestamp-list oracle.

Commands:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :gateway:test \
  --tests '*SlidingWindowCounterApproximationTest' \
  --tests '*RedisSlidingWindowCounterStateAdapterTest' --no-daemon
scripts/phase6-sliding-window-counter-e2e.sh
```

## Results

- Fixed Window with limit five admitted five requests immediately before and five immediately after a boundary: ten requests in a short interval.
- With the previous Sliding Counter window full, the first request at the adjacent boundary was rejected because previous usage retained full weight.
- Five events at the start of a previous 10-second window, observed one second into the next window, produced counter error `+45,000` scaled units: counter estimate 4.5 above the exact log's zero.
- Five events at the final millisecond of that previous window, observed one second into the next, produced error `-5,000` scaled units: counter estimate 4.5 versus exact usage five.
- The seeded 10,000-trace experiment observed maximum overestimation numerator 5,960 and maximum underestimation numerator 6,320 for `W=1,000`.
- The composed fixed/sliding comparison reproduced the boundary distinction through HAProxy and verified exact backend delivery counts.

## Interpretation

Fixed Window forgets the previous interval completely at the boundary and therefore permits its familiar boundary burst. Sliding Counter assumes previous-window traffic was uniformly distributed, decaying the whole previous count linearly. That constant-state approximation suppresses the abrupt reset but cannot recover the events' real positions: traffic clustered early is overestimated after it has actually expired, while traffic clustered late is underestimated before it expires. The exact log retains event timestamps and therefore has no such placement error, at the cost of state and work proportional to retained events.

No universal approximation bound is claimed. The reported maxima apply only to the specified seeded generated traces; the hand-built cases establish that both error signs are possible under the selected formula.
