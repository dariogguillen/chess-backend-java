package io.github.dariogguillen.chess.config;

import io.github.dariogguillen.chess.websocket.StompAuthInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
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
 *
 * <p>Feature 20 ({@code auth-stomp-trust}) adds {@link StompAuthInterceptor} to the client-inbound
 * channel as the first (and currently only) {@code ChannelInterceptor}. It runs before any
 * downstream session-event listener ({@code PlayerSessionTracker}, {@code ViewerCountTracker}, both
 * {@code @EventListener}-driven and therefore <em>after</em> the inbound interceptor chain) so the
 * identity attached on CONNECT is in place by the time those trackers read the session. The
 * interceptor's contract is "identity-strengthening, never access-gating": anonymous STOMP keeps
 * working, JWT only used to identify a session, and identity spoofing on SEND / SUBSCRIBE is
 * blocked with an ERROR frame (no disconnect). The full two-phase contract lives in {@link
 * StompAuthInterceptor}'s class JavaDoc.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(CorsProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final CorsProperties cors;
  private final StompAuthInterceptor stompAuthInterceptor;

  public WebSocketConfig(CorsProperties cors, StompAuthInterceptor stompAuthInterceptor) {
    this.cors = cors;
    this.stompAuthInterceptor = stompAuthInterceptor;
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

  /**
   * Registers {@link StompAuthInterceptor} at the head of the client-inbound channel. The order
   * matters: the auth interceptor must run before any downstream consumer reads session identity.
   * The existing trackers ({@code PlayerSessionTracker}, {@code ViewerCountTracker}) are
   * {@code @EventListener}s on Spring's session-event bus rather than {@code ChannelInterceptor}s,
   * so they are not in this list — but their handlers run after the inbound channel finishes
   * processing the frame, which means the interceptor's identity attachment on CONNECT is visible
   * to them by the time they fire on SUBSCRIBE / DISCONNECT.
   */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(stompAuthInterceptor);
  }
}
