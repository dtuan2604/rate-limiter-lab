package lab.ratelimiter.gateway.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PolicyEventCodec {

  private final ObjectMapper mapper;
  private final int maximumBytes;

  public PolicyEventCodec(int maximumBytes) {
    if (maximumBytes < 256) {
      throw new IllegalArgumentException("maximum event size is too small");
    }
    this.maximumBytes = maximumBytes;
    this.mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  public String encode(PolicyInvalidationEvent event) {
    Objects.requireNonNull(event, "event");
    try {
      String json = mapper.writeValueAsString(event);
      validateSize(json);
      return json;
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("policy event cannot be encoded", exception);
    }
  }

  public PolicyInvalidationEvent decode(String json) {
    Objects.requireNonNull(json, "json");
    validateSize(json);
    try {
      return mapper.readValue(json, PolicyInvalidationEvent.class);
    } catch (java.io.IOException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("invalid policy event", exception);
    }
  }

  private void validateSize(String json) {
    if (json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
      throw new IllegalArgumentException("policy event exceeds maximum size");
    }
  }
}
