package lab.ratelimiter.gateway.http.admin;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

public final class AdminPolicyRoutes {

  private AdminPolicyRoutes() {}

  public static RouterFunction<ServerResponse> routes(AdminPolicyHandler handler) {
    return RouterFunctions.route()
        .path(
            "/admin/api/v1/policies",
            builder ->
                builder
                    .GET("", handler::listPolicies)
                    .POST("", handler::createPolicy)
                    .POST("/match-test", handler::matchTest)
                    .GET("/{policyId}", handler::getPolicy)
                    .GET("/{policyId}/versions", handler::listVersions)
                    .POST("/{policyId}/versions", handler::cloneVersion)
                    .GET("/{policyId}/versions/{version}", handler::getVersion)
                    .PUT("/{policyId}/versions/{version}", handler::updateVersion)
                    .POST("/{policyId}/versions/{version}/activate", handler::activate)
                    .POST("/{policyId}/versions/{version}/disable", handler::disable)
                    .POST("/{policyId}/versions/{version}/archive", handler::archive)
                    .POST("/{policyId}/versions/{version}/restore", handler::restore))
        .build();
  }
}
