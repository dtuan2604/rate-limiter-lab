package lab.ratelimiter.gateway.http.admin;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

public final class InternalPolicyRoutes {

  private InternalPolicyRoutes() {}

  public static RouterFunction<ServerResponse> routes(PolicySnapshotEndpointHandler handler) {
    return RouterFunctions.route()
        .GET("/internal/policy-snapshot", handler::snapshot)
        .POST("/internal/policy-events/pause", handler::pauseEvents)
        .POST("/internal/policy-events/resume", handler::resumeEvents)
        .build();
  }
}
