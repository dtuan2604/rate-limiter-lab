package lab.ratelimiter.gateway.http.admin;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.policy.control.ActivationResult;
import lab.ratelimiter.gateway.policy.control.FixedWindowAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.RefillPeriod;
import lab.ratelimiter.gateway.policy.control.SlidingWindowCounterAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.WindowDuration;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public final class AdminPolicyHandler {

  private final PostgresPolicyRepository repository;
  private final String actor;
  private final ObjectMapper requestMapper;
  private final PolicySnapshotStore snapshotStore;

  public AdminPolicyHandler(PostgresPolicyRepository repository, String actor) {
    this(
        repository,
        actor,
        new PolicySnapshotStore(
            new lab.ratelimiter.gateway.policy.PolicySnapshot(0, Instant.EPOCH, List.of())));
  }

  public AdminPolicyHandler(
      PostgresPolicyRepository repository, String actor, PolicySnapshotStore snapshotStore) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("admin actor is required");
    }
    this.actor = actor;
    this.requestMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();
  }

  public Mono<ServerResponse> createPolicy(ServerRequest request) {
    String correlationId = correlationId(request);
    return decode(request, CreatePolicyRequest.class)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("request body is required")))
        .flatMap(
            body ->
                repository.createPolicy(
                    body.policyId(),
                    body.name(),
                    body.version(),
                    body.definition().toDomain(),
                    actor,
                    correlationId))
        .flatMap(policy -> policyResponse(HttpStatus.CREATED, policy))
        .onErrorResume(error -> errorResponse(error, correlationId));
  }

  public Mono<ServerResponse> cloneVersion(ServerRequest request) {
    String correlationId = correlationId(request);
    String policyId = request.pathVariable("policyId");
    return decode(request, CloneVersionRequest.class)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("request body is required")))
        .flatMap(
            body ->
                repository.createVersion(
                    policyId, body.sourceVersion(), body.version(), actor, correlationId))
        .flatMap(policy -> policyResponse(HttpStatus.CREATED, policy))
        .onErrorResume(error -> errorResponse(error, correlationId));
  }

  public Mono<ServerResponse> getVersion(ServerRequest request) {
    String correlationId = correlationId(request);
    return withVersion(
        request,
        correlationId,
        version ->
            repository
                .findVersion(request.pathVariable("policyId"), version)
                .flatMap(policy -> policyResponse(HttpStatus.OK, policy))
                .switchIfEmpty(notFound(correlationId)));
  }

  public Mono<ServerResponse> updateVersion(ServerRequest request) {
    String correlationId = correlationId(request);
    String ifMatch = request.headers().firstHeader(HttpHeaders.IF_MATCH);
    if (ifMatch == null) {
      return error(
          HttpStatus.PRECONDITION_REQUIRED,
          "PRECONDITION_REQUIRED",
          "If-Match revision is required",
          correlationId);
    }
    long expectedRevision;
    try {
      expectedRevision = parseEntityTag(ifMatch);
    } catch (IllegalArgumentException exception) {
      return error(
          HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH", exception.getMessage(), correlationId);
    }
    return withVersion(
        request,
        correlationId,
        version ->
            decode(request, DefinitionRequest.class)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("request body is required")))
                .flatMap(
                    body ->
                        repository.replaceDraft(
                            request.pathVariable("policyId"),
                            version,
                            expectedRevision,
                            body.toDomain(),
                            actor,
                            correlationId))
                .flatMap(policy -> policyResponse(HttpStatus.OK, policy)));
  }

  public Mono<ServerResponse> activate(ServerRequest request) {
    return propagation(request, true);
  }

  public Mono<ServerResponse> disable(ServerRequest request) {
    return propagation(request, false);
  }

  public Mono<ServerResponse> archive(ServerRequest request) {
    return lifecycle(request, "archive");
  }

  public Mono<ServerResponse> restore(ServerRequest request) {
    return lifecycle(request, "restore");
  }

  public Mono<ServerResponse> listPolicies(ServerRequest request) {
    String correlationId = correlationId(request);
    try {
      PageRequest page = page(request);
      return Mono.zip(
              repository.listPolicySummaries(page.page(), page.size()), repository.countPolicies())
          .flatMap(
              result ->
                  ServerResponse.ok()
                      .contentType(MediaType.APPLICATION_JSON)
                      .bodyValue(
                          new PolicyPage(result.getT1(), page.page(), page.size(), result.getT2())))
          .onErrorResume(error -> errorResponse(error, correlationId));
    } catch (IllegalArgumentException error) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_PAGE", error.getMessage(), correlationId);
    }
  }

  public Mono<ServerResponse> getPolicy(ServerRequest request) {
    String correlationId = correlationId(request);
    return repository
        .findPolicySummary(request.pathVariable("policyId"))
        .flatMap(
            summary ->
                ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(summary))
        .switchIfEmpty(notFound(correlationId))
        .onErrorResume(error -> errorResponse(error, correlationId));
  }

  public Mono<ServerResponse> listVersions(ServerRequest request) {
    String correlationId = correlationId(request);
    try {
      PageRequest page = page(request);
      String policyId = request.pathVariable("policyId");
      return Mono.zip(
              repository.listVersions(policyId, page.page(), page.size()),
              repository.countVersions(policyId))
          .flatMap(
              result ->
                  ServerResponse.ok()
                      .contentType(MediaType.APPLICATION_JSON)
                      .bodyValue(
                          new PolicyPage(
                              result.getT1().stream().map(PolicyVersionResponse::from).toList(),
                              page.page(),
                              page.size(),
                              result.getT2())))
          .onErrorResume(error -> errorResponse(error, correlationId));
    } catch (IllegalArgumentException error) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_PAGE", error.getMessage(), correlationId);
    }
  }

  public Mono<ServerResponse> matchTest(ServerRequest request) {
    String correlationId = correlationId(request);
    return decode(request, MatchTestRequest.class)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("request body is required")))
        .map(this::evaluateMatch)
        .flatMap(
            result -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(result))
        .onErrorResume(error -> errorResponse(error, correlationId));
  }

  private Mono<ServerResponse> propagation(ServerRequest request, boolean activate) {
    String correlationId = correlationId(request);
    return withVersion(
        request,
        correlationId,
        version -> {
          Mono<ActivationResult> result =
              activate
                  ? repository.activate(
                      request.pathVariable("policyId"), version, actor, correlationId)
                  : repository.disable(
                      request.pathVariable("policyId"), version, actor, correlationId);
          return result.flatMap(
              action ->
                  ServerResponse.accepted()
                      .contentType(MediaType.APPLICATION_JSON)
                      .bodyValue(PropagationResponse.from(action)));
        });
  }

  private <T> Mono<T> decode(ServerRequest request, Class<T> type) {
    return request
        .bodyToMono(byte[].class)
        .flatMap(
            bytes -> {
              try {
                return Mono.just(requestMapper.readValue(bytes, type));
              } catch (java.io.IOException exception) {
                return Mono.error(new DecodingException("invalid administrative JSON", exception));
              }
            });
  }

  private Mono<ServerResponse> lifecycle(ServerRequest request, String operation) {
    String correlationId = correlationId(request);
    return withVersion(
        request,
        correlationId,
        version -> {
          Mono<StoredPolicyVersion> result =
              operation.equals("archive")
                  ? repository.archive(
                      request.pathVariable("policyId"), version, actor, correlationId)
                  : repository.restore(
                      request.pathVariable("policyId"), version, actor, correlationId);
          return result.flatMap(policy -> policyResponse(HttpStatus.OK, policy));
        });
  }

  private Mono<ServerResponse> withVersion(
      ServerRequest request, String correlationId, LongFunction<Mono<ServerResponse>> operation) {
    try {
      return operation
          .apply(version(request))
          .onErrorResume(error -> errorResponse(error, correlationId));
    } catch (IllegalArgumentException error) {
      return errorResponse(error, correlationId);
    }
  }

  private static Mono<ServerResponse> policyResponse(
      HttpStatus status, StoredPolicyVersion policy) {
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .eTag(Long.toString(policy.revision()))
        .bodyValue(PolicyVersionResponse.from(policy));
  }

  private static Mono<ServerResponse> errorResponse(Throwable error, String correlationId) {
    Throwable cause = unwrap(error);
    if (cause instanceof DecodingException
        || cause instanceof org.springframework.core.codec.CodecException) {
      return error(
          HttpStatus.BAD_REQUEST,
          "INVALID_REQUEST_BODY",
          "Request body does not match the policy contract",
          correlationId);
    }
    if (cause instanceof IllegalStateException && cause.getMessage().contains("revision")) {
      return error(
          HttpStatus.PRECONDITION_FAILED,
          "POLICY_REVISION_MISMATCH",
          cause.getMessage(),
          correlationId);
    }
    if (cause instanceof IllegalStateException) {
      return error(
          HttpStatus.CONFLICT, "INVALID_POLICY_TRANSITION", cause.getMessage(), correlationId);
    }
    if (cause instanceof IllegalArgumentException) {
      return error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "POLICY_VALIDATION_FAILED",
          cause.getMessage(),
          correlationId);
    }
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "ADMIN_OPERATION_FAILED",
        "Administrative operation failed",
        correlationId);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null
        && (current instanceof java.util.concurrent.CompletionException
            || current instanceof RuntimeException
                && current.getClass().getName().contains("ReactiveException"))) {
      current = current.getCause();
    }
    return current;
  }

  private static Mono<ServerResponse> notFound(String correlationId) {
    return error(
        HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "Policy version was not found", correlationId);
  }

  private static Mono<ServerResponse> error(
      HttpStatus status, String code, String message, String correlationId) {
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new AdminErrorResponse(status.value(), code, message, correlationId, List.of()));
  }

  private static long version(ServerRequest request) {
    try {
      long value = Long.parseLong(request.pathVariable("version"));
      if (value < 1) {
        throw new IllegalArgumentException("version must be positive");
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("version must be positive", exception);
    }
  }

  private static long parseEntityTag(String value) {
    if (value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      throw new IllegalArgumentException("If-Match must be a quoted revision");
    }
    long revision = Long.parseLong(value.substring(1, value.length() - 1));
    if (revision < 0) {
      throw new IllegalArgumentException("If-Match revision must be nonnegative");
    }
    return revision;
  }

  private static String correlationId(ServerRequest request) {
    String value = request.headers().firstHeader("X-Correlation-Id");
    return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
  }

  private MatchTestResponse evaluateMatch(MatchTestRequest test) {
    Objects.requireNonNull(test.request(), "request");
    if (test.candidate() != null) {
      PolicyDefinition candidate = test.candidate().toDomain();
      boolean method = candidate.methods().contains(test.request().method());
      boolean path = candidate.path().equals(test.request().path());
      boolean identity =
          test.request().headers() != null
              && test.request().headers().containsKey("X-Client-Id")
              && !test.request().headers().get("X-Client-Id").isBlank();
      return new MatchTestResponse(
          method && path && identity,
          null,
          null,
          candidate.algorithm().type().name(),
          List.of(
              method ? "method matched" : "method did not match",
              path ? "path matched" : "path did not match",
              identity ? "identity present" : "identity missing"));
    }
    CompiledPolicy matched =
        snapshotStore.current().match(test.request().method(), test.request().path()).orElse(null);
    return matched == null
        ? new MatchTestResponse(false, null, null, null, List.of("no active policy matched"))
        : new MatchTestResponse(
            true,
            matched.policy().policyId().value(),
            matched.policy().policyVersion().value(),
            matched.policy().algorithm().name(),
            List.of("active policy matched"));
  }

  private static PageRequest page(ServerRequest request) {
    int page = parseQuery(request, "page", 0);
    int size = parseQuery(request, "size", 50);
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("page must be nonnegative and size must be 1..100");
    }
    return new PageRequest(page, size);
  }

  private static int parseQuery(ServerRequest request, String name, int defaultValue) {
    try {
      return request.queryParam(name).map(Integer::parseInt).orElse(defaultValue);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  public record CreatePolicyRequest(
      String policyId, String name, long version, DefinitionRequest definition) {}

  public record CloneVersionRequest(long version, long sourceVersion) {}

  public record DefinitionRequest(
      String description,
      MatchRequest match,
      IdentityRequest identity,
      AlgorithmRequest algorithm,
      FailureMode failureMode,
      int priority) {

    PolicyDefinition toDomain() {
      Objects.requireNonNull(match, "match");
      Objects.requireNonNull(identity, "identity");
      Objects.requireNonNull(algorithm, "algorithm");
      return new PolicyDefinition(
          description,
          match.routeId(),
          match.path(),
          match.methods(),
          identity.components().stream()
              .map(component -> new PolicyIdentityComponent(component.type(), component.name()))
              .toList(),
          algorithm.toDomain(),
          failureMode,
          priority);
    }
  }

  public record MatchRequest(String routeId, String path, List<String> methods) {}

  public record IdentityRequest(List<IdentityComponentRequest> components) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record IdentityComponentRequest(String type, String name) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = FixedWindowAlgorithmRequest.class, name = "FIXED_WINDOW"),
    @JsonSubTypes.Type(value = TokenBucketAlgorithmRequest.class, name = "TOKEN_BUCKET"),
    @JsonSubTypes.Type(
        value = SlidingWindowCounterAlgorithmRequest.class,
        name = "SLIDING_WINDOW_COUNTER")
  })
  public sealed interface AlgorithmRequest
      permits FixedWindowAlgorithmRequest,
          TokenBucketAlgorithmRequest,
          SlidingWindowCounterAlgorithmRequest {
    PolicyAlgorithmDefinition toDomain();
  }

  public record FixedWindowAlgorithmRequest(FixedWindowConfigurationRequest configuration)
      implements AlgorithmRequest {
    @Override
    public PolicyAlgorithmDefinition toDomain() {
      Objects.requireNonNull(configuration, "configuration");
      return new FixedWindowAlgorithmDefinition(
          configuration.limit(), Duration.ofMillis(configuration.windowMilliseconds()));
    }
  }

  public record TokenBucketAlgorithmRequest(TokenBucketConfigurationRequest configuration)
      implements AlgorithmRequest {
    @Override
    public PolicyAlgorithmDefinition toDomain() {
      Objects.requireNonNull(configuration, "configuration");
      return new TokenBucketAlgorithmDefinition(
          configuration.capacity(),
          configuration.initialTokens(),
          configuration.refillTokens(),
          RefillPeriod.parse(configuration.refillPeriod()),
          configuration.requestCost());
    }
  }

  public record SlidingWindowCounterAlgorithmRequest(
      SlidingWindowCounterConfigurationRequest configuration) implements AlgorithmRequest {
    @Override
    public PolicyAlgorithmDefinition toDomain() {
      Objects.requireNonNull(configuration, "configuration");
      return new SlidingWindowCounterAlgorithmDefinition(
          configuration.limit(),
          WindowDuration.parse(configuration.window()),
          configuration.requestCost());
    }
  }

  public record FixedWindowConfigurationRequest(long limit, long windowMilliseconds) {}

  public record TokenBucketConfigurationRequest(
      long capacity,
      long initialTokens,
      long refillTokens,
      String refillPeriod,
      long requestCost) {}

  public record SlidingWindowCounterConfigurationRequest(
      long limit, String window, long requestCost) {}

  public record MatchTestRequest(SampleRequest request, DefinitionRequest candidate) {}

  public record SampleRequest(String method, String path, Map<String, String> headers) {}

  public record MatchTestResponse(
      boolean matched, String policyId, Long version, String algorithm, List<String> explanation) {}

  public record PolicyPage(List<?> items, int page, int size, long total) {}

  private record PageRequest(int page, int size) {}

  public record PolicyVersionResponse(
      String policyId,
      String name,
      long version,
      String status,
      long revision,
      String description,
      MatchRequest match,
      IdentityRequest identity,
      AlgorithmRequest algorithm,
      FailureMode failureMode,
      int priority,
      Instant createdAt,
      String createdBy,
      Instant activatedAt,
      String activatedBy) {

    static PolicyVersionResponse from(StoredPolicyVersion stored) {
      PolicyDefinition definition = stored.definition();
      return new PolicyVersionResponse(
          stored.policyId(),
          stored.name(),
          stored.version(),
          stored.lifecycle().name(),
          stored.revision(),
          definition.description(),
          new MatchRequest(definition.routeId(), definition.path(), definition.methods()),
          new IdentityRequest(
              definition.identityComponents().stream()
                  .map(value -> new IdentityComponentRequest(value.type(), value.name()))
                  .toList()),
          algorithmResponse(definition.algorithm()),
          definition.failureMode(),
          definition.priority(),
          stored.createdAt(),
          stored.createdBy(),
          stored.activatedAt(),
          stored.activatedBy());
    }

    private static AlgorithmRequest algorithmResponse(PolicyAlgorithmDefinition algorithm) {
      if (algorithm instanceof FixedWindowAlgorithmDefinition fixedWindow) {
        return new FixedWindowAlgorithmRequest(
            new FixedWindowConfigurationRequest(
                fixedWindow.limit(), fixedWindow.window().toMillis()));
      }
      if (algorithm instanceof TokenBucketAlgorithmDefinition tokenBucket) {
        return new TokenBucketAlgorithmRequest(
            new TokenBucketConfigurationRequest(
                tokenBucket.capacity(),
                tokenBucket.initialTokens(),
                tokenBucket.refillTokens(),
                tokenBucket.refillPeriod().toString(),
                tokenBucket.requestCost()));
      }
      if (algorithm instanceof SlidingWindowCounterAlgorithmDefinition slidingCounter) {
        return new SlidingWindowCounterAlgorithmRequest(
            new SlidingWindowCounterConfigurationRequest(
                slidingCounter.limit(),
                slidingCounter.window().toString(),
                slidingCounter.requestCost()));
      }
      throw new IllegalArgumentException("unsupported policy algorithm");
    }
  }

  public record PropagationResponse(
      String policyId,
      long version,
      String status,
      long policySetRevision,
      UUID eventId,
      String propagationStatus,
      String runtimeState) {

    static PropagationResponse from(ActivationResult result) {
      return new PropagationResponse(
          result.policy().policyId(),
          result.policy().version(),
          result.policy().lifecycle().name(),
          result.policySetRevision(),
          result.event().eventId(),
          "PENDING",
          "FRESH_VERSION_STATE");
    }
  }
}
