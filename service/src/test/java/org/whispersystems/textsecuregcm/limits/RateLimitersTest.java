/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.limits;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.redis.ClusterLuaScript;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisClusterClient;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.util.MockUtils;
import org.whispersystems.textsecuregcm.util.MutableClock;

@SuppressWarnings("unchecked")
public class RateLimitersTest {

  private final DynamicConfiguration configuration = mock(DynamicConfiguration.class);

  private final DynamicConfigurationManager<DynamicConfiguration> dynamicConfig =
      MockUtils.buildMock(DynamicConfigurationManager.class, cfg -> when(cfg.getConfiguration()).thenReturn(configuration));

  private final ClusterLuaScript validateScript = mock(ClusterLuaScript.class);

  private final FaultTolerantRedisClusterClient redisCluster = mock(FaultTolerantRedisClusterClient.class);

  private final MutableClock clock = MockUtils.mutableClock(0);

  private static final String BAD_YAML = """
      limits:
        prekeys:
          bucketSize: 150
          permitRegenerationDuration: PT6S
        unexpected:
          bucketSize: 4
          permitRegenerationDuration: PT30S
      """;

  private static final String GOOD_YAML = """
      limits:
        prekeys:
          bucketSize: 150
          permitRegenerationDuration: PT6S
          failOpen: true
        attachmentCreate:
          bucketSize: 4
          permitRegenerationDuration: PT30S
          failOpen: true
      """;

  public record SimpleDynamicConfiguration(@Valid @NotNull @JsonProperty Map<String, RateLimiterConfig> limits) {
  }

  @Test
  public void testValidateConfigs() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> {
      final SimpleDynamicConfiguration dynamicConfiguration =
          DynamicConfigurationManager.parseConfiguration(BAD_YAML, SimpleDynamicConfiguration.class).orElseThrow();

      final RateLimiters rateLimiters = new RateLimiters(dynamicConfiguration.limits(), dynamicConfig, validateScript, redisCluster, clock);
      rateLimiters.validateValuesAndConfigs();
    });

    final SimpleDynamicConfiguration dynamicConfiguration =
        DynamicConfigurationManager.parseConfiguration(GOOD_YAML, SimpleDynamicConfiguration.class).orElseThrow();

    final RateLimiters rateLimiters = new RateLimiters(dynamicConfiguration.limits(), dynamicConfig, validateScript, redisCluster, clock);
    assertDoesNotThrow(rateLimiters::validateValuesAndConfigs);
  }

  @Test
  public void testValidateDuplicates() throws Exception {
    final TestDescriptor td1 = new TestDescriptor("id1");
    final TestDescriptor td2 = new TestDescriptor("id2");
    final TestDescriptor td3 = new TestDescriptor("id3");
    final TestDescriptor tdDup = new TestDescriptor("id1");

    assertThrows(IllegalStateException.class, () -> new BaseRateLimiters<>(
        new TestDescriptor[] { td1, td2, td3, tdDup },
        Collections.emptyMap(),
        dynamicConfig,
        validateScript,
        redisCluster,
        clock) {});

    new BaseRateLimiters<>(
        new TestDescriptor[] { td1, td2, td3 },
        Collections.emptyMap(),
        dynamicConfig,
        validateScript,
        redisCluster,
        clock) {};
  }

  @Test
  void testUnchangingConfiguration() {
    final RateLimiters rateLimiters = new RateLimiters(Collections.emptyMap(), dynamicConfig, validateScript, redisCluster, clock);
    final RateLimiter limiter = rateLimiters.getRateLimitResetLimiter();
    final RateLimiterConfig expected = RateLimiters.For.RATE_LIMIT_RESET.defaultConfig();
    assertEquals(expected, config(limiter));
  }

  @Test
  void testChangingConfiguration() {
    final RateLimiterConfig initialRateLimiterConfig = new RateLimiterConfig(4, Duration.ofMinutes(1), false);
    final RateLimiterConfig updatedRateLimiterCongig = new RateLimiterConfig(17, Duration.ofSeconds(3), false);
    final RateLimiterConfig baseConfig = new RateLimiterConfig(1, Duration.ofMinutes(1), false);

    final Map<String, RateLimiterConfig> limitsConfigMap = new HashMap<>();

    limitsConfigMap.put(RateLimiters.For.CAPTCHA_CHALLENGE_ATTEMPT.id(), baseConfig);
    limitsConfigMap.put(RateLimiters.For.CAPTCHA_CHALLENGE_SUCCESS.id(), baseConfig);

    when(configuration.getLimits()).thenReturn(limitsConfigMap);

    final RateLimiters rateLimiters = new RateLimiters(Collections.emptyMap(), dynamicConfig, validateScript, redisCluster, clock);
    final RateLimiter limiter = rateLimiters.getRateLimitResetLimiter();

    limitsConfigMap.put(RateLimiters.For.RATE_LIMIT_RESET.id(), initialRateLimiterConfig);
    assertEquals(initialRateLimiterConfig, config(limiter));

    assertEquals(baseConfig, config(rateLimiters.getCaptchaChallengeAttemptLimiter()));
    assertEquals(baseConfig, config(rateLimiters.getCaptchaChallengeSuccessLimiter()));

    limitsConfigMap.put(RateLimiters.For.RATE_LIMIT_RESET.id(), updatedRateLimiterCongig);
    assertEquals(updatedRateLimiterCongig, config(limiter));

    assertEquals(baseConfig, config(rateLimiters.getCaptchaChallengeAttemptLimiter()));
    assertEquals(baseConfig, config(rateLimiters.getCaptchaChallengeSuccessLimiter()));
  }

  @Test
  public void testRateLimiterHasItsPrioritiesStraight() throws Exception {
    final RateLimiters.For descriptor = RateLimiters.For.CAPTCHA_CHALLENGE_ATTEMPT;
    final RateLimiterConfig configForDynamic = new RateLimiterConfig(1, Duration.ofMinutes(1), false);
    final RateLimiterConfig configForStatic = new RateLimiterConfig(2, Duration.ofSeconds(30), false);
    final RateLimiterConfig defaultConfig = descriptor.defaultConfig();

    final Map<String, RateLimiterConfig> mapForDynamic = new HashMap<>();
    final Map<String, RateLimiterConfig> mapForStatic = new HashMap<>();

    when(configuration.getLimits()).thenReturn(mapForDynamic);

    final RateLimiters rateLimiters = new RateLimiters(mapForStatic, dynamicConfig, validateScript, redisCluster, clock);
    final RateLimiter limiter = rateLimiters.forDescriptor(descriptor);

    // test only default is present
    mapForDynamic.remove(descriptor.id());
    mapForStatic.remove(descriptor.id());
    assertEquals(defaultConfig, config(limiter));

    // test dynamic and no static
    mapForDynamic.put(descriptor.id(), configForDynamic);
    mapForStatic.remove(descriptor.id());
    assertEquals(configForDynamic, config(limiter));

    // test dynamic and static
    mapForDynamic.put(descriptor.id(), configForDynamic);
    mapForStatic.put(descriptor.id(), configForStatic);
    assertEquals(configForDynamic, config(limiter));

    // test static, but no dynamic
    mapForDynamic.remove(descriptor.id());
    mapForStatic.put(descriptor.id(), configForStatic);
    assertEquals(configForStatic, config(limiter));
  }

  private record TestDescriptor(String id) implements RateLimiterDescriptor {

    @Override
    public boolean isDynamic() {
      return false;
    }

    @Override
    public RateLimiterConfig defaultConfig() {
      return new RateLimiterConfig(1, Duration.ofMinutes(1), false);
    }
  }

  private static RateLimiterConfig config(final RateLimiter rateLimiter) {
    if (rateLimiter instanceof StaticRateLimiter rm) {
      return rm.config();
    }
    if (rateLimiter instanceof DynamicRateLimiter rm) {
      return rm.config();
    }
    throw new IllegalArgumentException("Rate limiter is of an unexpected type: " + rateLimiter.getClass().getName());
  }
}
