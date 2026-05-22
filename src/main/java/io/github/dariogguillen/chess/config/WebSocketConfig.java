package io.github.dariogguillen.chess.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration. Registers a single STOMP endpoint at {@code /ws} with the
 * allowed-origin patterns drawn from {@link CorsProperties} — the same source of truth the REST
 * CORS configuration in {@code CorsConfig} consumes. Keeping the two layers wired to the same
 * property class is what prevents the drift the previous hardcoded-list shape invited.
 * Subscriptions go through Spring's in-process {@code SimpleBroker} on the {@code /topic} prefix;
 * client-to-server messages would be routed via the {@code /app} prefix, registered here for
 * future-proofing even though this feature does not use it. SockJS fallback is intentionally not
 * enabled — modern browsers plus the planned {@code @stomp/stompjs} frontend client handle native
 * WebSocket fine, and SockJS would add surface area we do not need.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(CorsProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final CorsProperties cors;

  public WebSocketConfig(CorsProperties cors) {
    this.cors = cors;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws")
        .setAllowedOriginPatterns(cors.allowedOriginPatterns().toArray(String[]::new));
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.setApplicationDestinationPrefixes("/app");
    registry.enableSimpleBroker("/topic");
  }
}
