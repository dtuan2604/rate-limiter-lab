package lab.ratelimiter.gateway.http.admin;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

public final class AcceptancePolicyEventControlRoutes {

  private AcceptancePolicyEventControlRoutes() {}

  public static RouterFunction<ServerResponse> routes(AcceptancePolicyEventControlHandler handler) {
    return RouterFunctions.route()
        .POST("/internal/policy-events/pause", handler::pause)
        .POST("/internal/policy-events/resume", handler::resume)
        .build();
  }
}
