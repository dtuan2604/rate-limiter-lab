package lab.ratelimiter.gateway.policy;

public record OutboxDispatchResult(int claimed, int published, int failed) {}
