package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.config.RedisActiveStateProperties;
import io.github.dariogguillen.chess.domain.Invitation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link InvitationStore}. Each invitee gets one Redis hash at {@code
 * invitations:user:{inviteeUserId}} whose <em>fields</em> are room ids and whose <em>values</em>
 * are JSON-serialized {@link Invitation}s. This layout is what makes {@link #findByInvitee(UUID)} a
 * single {@code HGETALL} — no {@code SCAN}-by-pattern over the keyspace, and at most one entry per
 * room per invitee falls out for free (the room id is the field key).
 *
 * <p>Every mutating call re-applies the TTL on the whole hash, sourced from {@link
 * RedisActiveStateProperties#activeStateTtl()} — the same 24h lease the rooms themselves carry, so
 * an invitation can never outlive the room it points at by more than the lease window. Redis has no
 * per-hash-field TTL, so the lease is per-invitee-hash; that is acceptable because re-validation
 * against the live room at read/accept time (in {@code InvitationService}) is the real liveness
 * gate, and the TTL is only a backstop that bounds abandoned hashes.
 *
 * <p>The value serializer is the same {@code Jackson2JsonRedisSerializer<Invitation>} configured in
 * {@code RedisConfig}, so the stored JSON stays {@code redis-cli HGETALL}-readable, matching the
 * style of {@code RedisRoomStore}.
 */
@Component
public class RedisInvitationStore implements InvitationStore {

  private static final String KEY_PREFIX = "invitations:user:";

  private final RedisTemplate<String, Invitation> redisTemplate;
  private final HashOperations<String, String, Invitation> hashOps;
  private final Duration ttl;

  public RedisInvitationStore(
      RedisTemplate<String, Invitation> invitationRedisTemplate,
      RedisActiveStateProperties properties) {
    this.redisTemplate = invitationRedisTemplate;
    this.hashOps = invitationRedisTemplate.opsForHash();
    this.ttl = properties.activeStateTtl();
  }

  @Override
  public void save(UUID inviteeUserId, Invitation invitation) {
    String key = key(inviteeUserId);
    hashOps.put(key, invitation.roomId(), invitation);
    redisTemplate.expire(key, ttl);
  }

  @Override
  public Optional<Invitation> find(UUID inviteeUserId, String roomId) {
    return Optional.ofNullable(hashOps.get(key(inviteeUserId), roomId));
  }

  @Override
  public List<Invitation> findByInvitee(UUID inviteeUserId) {
    Map<String, Invitation> entries = hashOps.entries(key(inviteeUserId));
    return List.copyOf(entries.values());
  }

  @Override
  public boolean delete(UUID inviteeUserId, String roomId) {
    Long removed = hashOps.delete(key(inviteeUserId), roomId);
    return removed != null && removed > 0;
  }

  private static String key(UUID inviteeUserId) {
    return KEY_PREFIX + inviteeUserId;
  }
}
