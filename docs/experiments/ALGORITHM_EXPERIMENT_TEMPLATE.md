# Algorithm Experiment Template

## Experiment identity

- Algorithm:
- Policy version:
- Code revision:
- Date:
- Random seed:
- Gateway replica count:
- Redis mode:

## Question

State the behavior being investigated, such as fixed-window boundary burst or token-bucket burst absorption.

## Configuration

Record exact policy, traffic scenario, container resources, and timeout settings.

## Hypothesis

State the expected result before running the experiment.

## Commands

List exact reproducible commands.

## Results

Record:

- total requests;
- allowed;
- rejected;
- failed;
- throughput;
- latency percentiles;
- backend received count;
- requests by gateway replica;
- relevant Redis state or sanitized debug output;
- metrics query results.

## Interpretation

Explain whether results support the hypothesis and which algorithm semantics caused the observed behavior.

## Limitations

Document environmental noise, local Docker constraints, sampling issues, and anything not proven.
