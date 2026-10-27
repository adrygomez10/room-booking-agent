package dev.adriangm.booking.web;

import dev.adriangm.booking.application.booking.ReservationContext;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps expiring demo sessions and server-assigned pending requests in memory. */
public final class SessionStore {
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;

  /** Uses the supplied clock to enforce session expiration. */
  public SessionStore(Clock clock) {
    this.clock = clock;
  }

  /** Creates a random one-hour session for the demonstration account. */
  public String create() {
    sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
    if (sessions.size() >= 256) {
      throw new IllegalStateException("Demo session limit reached.");
    }

    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);

    sessions.put(
        token,
        new Session(
            "demo-user", clock.instant().plus(Duration.ofHours(1)), new ConcurrentHashMap<>()));

    return token;
  }

  /** Returns a session only while its token is known and its lifetime has not expired. */
  public Optional<Session> find(String token) {
    Session session = sessions.get(token);
    if (session == null) {
      return Optional.empty();
    }

    if (!session.expiresAt().isAfter(clock.instant())) {
      sessions.remove(token);
      return Optional.empty();
    }

    return Optional.of(session);
  }

  /** Invalidates a session token and its pending requests. */
  public void remove(String token) {
    sessions.remove(token);
  }

  /** Keeps validated request data fixed while the same server-assigned key is retried. */
  public record PendingRequest(ReservationContext context, String message) {}

  /** Associates the authenticated account with an expiry and its recent pending requests. */
  public record Session(String userId, Instant expiresAt, Map<String, PendingRequest> requests) {}
}
