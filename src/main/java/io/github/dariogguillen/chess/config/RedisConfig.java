package io.github.dariogguillen.chess.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.Room;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Wires the two typed {@link RedisTemplate}s used by the active-state stores. One template per
 * domain aggregate ({@link Room} and {@link Game}) keeps each {@code RedisTemplate.opsForValue()}
 * call type-safe at the call site, and lets us pick a per-type {@link Jackson2JsonRedisSerializer}
 * that omits {@code @class} type metadata — the JSON in Redis stays {@code redis-cli GET}-readable.
 *
 * <p>Keys are serialized with {@link StringRedisSerializer}: the room id is the 6-char short code
 * (always a {@code String} at the domain level), and the game id is a {@code java.util.UUID} whose
 * {@code toString()} form is what the store concatenates with the {@code game:} prefix before
 * handing it to the template. The Redis keyspace ({@code room:ABC123}, {@code game:<uuid>}) stays
 * inspectable from the CLI.
 *
 * <p>The serializer uses an {@link ObjectMapper} obtained from the Spring context. Spring Boot's
 * autoconfigured mapper already registers the Java 8 modules we depend on — most notably {@code
 * jackson-datatype-jdk8}, which is what serializes the {@code Optional<Piece>} field on {@link
 * io.github.dariogguillen.chess.domain.Move} as either the inner value or {@code null}. We
 * deliberately reuse the same mapper instead of constructing a fresh one so the Redis JSON exactly
 * matches what the REST surface already produces — same date format, same {@code Optional}
 * handling, same enum encoding.
 *
 * <p>Choice of {@code RedisTemplate} + {@code Jackson2JsonRedisSerializer<T>} over the alternatives
 * is documented in {@code notes/08-redis-active-state.md}; in short: records do not fit
 * {@code @RedisHash} + {@code CrudRepository}, and per-type serializers avoid the {@code @class}
 * metadata that {@code GenericJackson2JsonRedisSerializer} bakes into the JSON.
 */
@Configuration
@EnableConfigurationProperties(RedisActiveStateProperties.class)
public class RedisConfig {

  /**
   * Template for the {@code room:{id}} keyspace. Used by {@code RedisRoomStore} for {@code
   * findById} / {@code save} / {@code compute}.
   */
  @Bean
  public RedisTemplate<String, Room> roomRedisTemplate(
      RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    return buildTemplate(connectionFactory, objectMapper, Room.class);
  }

  /**
   * Template for the {@code game:{id}} keyspace. Used by {@code RedisGameStore} for {@code
   * findById} / {@code save} / {@code compute}.
   */
  @Bean
  public RedisTemplate<String, Game> gameRedisTemplate(
      RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    return buildTemplate(connectionFactory, objectMapper, Game.class);
  }

  private static <T> RedisTemplate<String, T> buildTemplate(
      RedisConnectionFactory connectionFactory, ObjectMapper objectMapper, Class<T> valueType) {
    RedisTemplate<String, T> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    StringRedisSerializer keySerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<T> valueSerializer =
        new Jackson2JsonRedisSerializer<>(objectMapper, valueType);
    template.setKeySerializer(keySerializer);
    template.setHashKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    template.setHashValueSerializer(valueSerializer);
    template.afterPropertiesSet();
    return template;
  }
}
