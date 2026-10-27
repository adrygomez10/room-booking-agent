package dev.adriangm.booking.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.adriangm.booking.application.booking.ReservationContext;
import dev.adriangm.booking.application.turn.ReservationTurnCoordinator;
import dev.adriangm.booking.domain.TimeSlot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Translates authenticated HTTP requests into validated reservation turns. */
public final class ReservationController implements AutoCloseable {
  private final ReservationTurnCoordinator coordinator;
  private final Clock clock;
  private final byte[] password;
  private final String modelMode;
  private final SessionStore sessions;
  private final ObjectMapper json = new ObjectMapper();
  private final ExecutorService executor = Executors.newFixedThreadPool(8);
  private HttpServer server;

  /** Configures the coordinator, local demo authentication and HTTP input constraints. */
  public ReservationController(
      ReservationTurnCoordinator coordinator, Clock clock, String password, String modelMode) {
    if (password == null || password.length() < 8) {
      throw new IllegalArgumentException("DEMO_PASSWORD must contain at least eight characters.");
    }

    this.coordinator = coordinator;
    this.clock = clock;
    this.password = password.getBytes(StandardCharsets.UTF_8);
    this.modelMode = modelMode;
    this.sessions = new SessionStore(clock);

    json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    json.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    json.getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(4096).build());
  }

  /** Starts the server on the supplied address and returns its bound port. */
  public int start(InetSocketAddress address) throws IOException {
    server = HttpServer.create(address, 32);
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();

    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange
          .getResponseHeaders()
          .set(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; style-src "
                  + "'self'; base-uri 'none'; frame-ancestors 'none'");
      exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");

      try {
        route(exchange);
      } catch (HttpFailure failure) {
        respond(exchange, failure.status, Map.of("error", failure.getMessage()));
      } catch (IllegalArgumentException | java.time.DateTimeException failure) {
        respond(exchange, 400, Map.of("error", "INVALID_REQUEST"));
      } catch (Exception failure) {
        respond(exchange, 503, Map.of("error", "REQUEST_FAILED"));
      }
    }
  }

  private void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();

    if (method.equals("GET") && Set.of("/", "/app.js", "/app.css").contains(path)) {
      String resource = path.equals("/") ? "/web/index.html" : "/web" + path;
      try (var input = getClass().getResourceAsStream(resource)) {
        if (input == null) {
          throw new HttpFailure(404, "NOT_FOUND");
        }

        byte[] content = input.readAllBytes();
        exchange
            .getResponseHeaders()
            .set(
                "Content-Type",
                path.endsWith(".js")
                    ? "text/javascript; charset=utf-8"
                    : path.endsWith(".css")
                        ? "text/css; charset=utf-8"
                        : "text/html; charset=utf-8");

        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        return;
      }
    }

    if (!method.equals("GET")) {
      checkOrigin(exchange);
    }

    if (path.equals("/api/session")) {
      if (method.equals("GET")) {
        respond(
            exchange,
            200,
            Map.of(
                "authenticated",
                sessions.find(cookie(exchange)).isPresent(),
                "modelMode",
                modelMode));
        return;
      }

      if (method.equals("POST")) {
        JsonNode body = body(exchange, Set.of("password"));
        String submitted = text(body, "password", 512);
        if (!MessageDigest.isEqual(password, submitted.getBytes(StandardCharsets.UTF_8))) {
          throw new HttpFailure(401, "INVALID_CREDENTIALS");
        }

        sessions.remove(cookie(exchange));
        exchange
            .getResponseHeaders()
            .add(
                "Set-Cookie",
                "booking_session="
                    + sessions.create()
                    + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600");

        respond(exchange, 200, Map.of("authenticated", true, "modelMode", modelMode));
        return;
      }

      if (method.equals("DELETE")) {
        sessions.remove(cookie(exchange));
        exchange
            .getResponseHeaders()
            .add("Set-Cookie", "booking_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        respond(exchange, 200, Map.of("authenticated", false));
        return;
      }
    }

    if (path.startsWith("/api/")) {
      SessionStore.Session session =
          sessions
              .find(cookie(exchange))
              .orElseThrow(() -> new HttpFailure(401, "AUTHENTICATION_REQUIRED"));

      if (method.equals("POST") && path.equals("/api/requests")) {
        JsonNode body =
            body(
                exchange,
                Set.of("message", "startsAt", "endsAt", "attendeeCount", "reservationAllowed"));
        final String message = text(body, "message", 2000);
        TimeSlot slot =
            new TimeSlot(
                Instant.parse(text(body, "startsAt", 40)), Instant.parse(text(body, "endsAt", 40)));
        if (!slot.startsAt().isAfter(clock.instant())
            || slot.startsAt().isAfter(clock.instant().plusSeconds(366L * 86400))) {
          throw new HttpFailure(400, "INVALID_SLOT");
        }
        if (!body.path("attendeeCount").isIntegralNumber()
            || !body.path("attendeeCount").canConvertToInt()
            || !body.path("reservationAllowed").isBoolean()) {
          throw new HttpFailure(400, "INVALID_REQUEST");
        }
        if (session.requests().size() >= 20) {
          throw new HttpFailure(429, "REQUEST_LIMIT_REACHED");
        }

        String requestId = UUID.randomUUID().toString();
        ReservationContext context =
            new ReservationContext(
                session.userId(),
                slot,
                body.get("attendeeCount").intValue(),
                requestId,
                body.get("reservationAllowed").booleanValue());

        session.requests().put(requestId, new SessionStore.PendingRequest(context, message));

        respond(exchange, 201, Map.of("requestId", requestId));
        return;
      }

      if (method.equals("POST") && path.matches("/api/requests/[0-9a-f-]{36}/run")) {
        body(exchange, Set.of());
        String key = path.split("/")[3];
        SessionStore.PendingRequest request = session.requests().get(key);
        if (request == null) {
          throw new HttpFailure(404, "REQUEST_NOT_FOUND");
        }

        var outcome = coordinator.run(request.context(), request.message());

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", outcome.status());
        response.put("trace", outcome.trace());
        response.put("modelCalls", outcome.modelCalls());
        response.put("toolCalls", outcome.toolCalls());

        if (outcome.reservation() != null) {
          var reservation = outcome.reservation();
          response.put(
              "reservation",
              Map.of(
                  "id",
                  reservation.id(),
                  "roomId",
                  reservation.roomId(),
                  "startsAt",
                  reservation.slot().startsAt().toString(),
                  "endsAt",
                  reservation.slot().endsAt().toString(),
                  "attendeeCount",
                  reservation.attendeeCount()));
        }

        respond(exchange, 200, response);
        return;
      }
    }

    throw new HttpFailure(404, "NOT_FOUND");
  }

  private void checkOrigin(HttpExchange exchange) {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin == null) {
      return;
    }

    int port = exchange.getLocalAddress().getPort();
    if (!origin.equals("http://localhost:" + port) && !origin.equals("http://127.0.0.1:" + port)) {
      throw new HttpFailure(403, "ORIGIN_NOT_ALLOWED");
    }
  }

  private JsonNode body(HttpExchange exchange, Set<String> allowed) throws IOException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
      throw new HttpFailure(415, "JSON_REQUIRED");
    }

    byte[] bytes = exchange.getRequestBody().readNBytes(16385);
    if (bytes.length > 16384) {
      throw new HttpFailure(413, "BODY_TOO_LARGE");
    }

    JsonNode node;
    try {
      node = json.readTree(bytes);
    } catch (IOException failure) {
      throw new HttpFailure(400, "INVALID_JSON");
    }

    if (node == null || !node.isObject()) {
      throw new HttpFailure(400, "JSON_OBJECT_REQUIRED");
    }

    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(allowed)) {
      throw new HttpFailure(400, "INVALID_FIELDS");
    }

    return node;
  }

  private String text(JsonNode body, String field, int maximum) {
    JsonNode value = body.path(field);
    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum) {
      throw new HttpFailure(400, "INVALID_REQUEST");
    }
    return value.textValue();
  }

  private String cookie(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Cookie");
    if (header != null) {
      for (String part : header.split(";")) {
        String value = part.strip();
        if (value.startsWith("booking_session=")) {
          String token = value.substring("booking_session=".length());
          if (token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1);
          }
          return token.matches("[0-9a-f]{64}") ? token : "";
        }
      }
    }
    return "";
  }

  private void respond(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] bytes = json.writeValueAsBytes(payload);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    if (server != null) {
      server.stop(0);
    }
    executor.shutdownNow();
  }

  private static final class HttpFailure extends RuntimeException {
    private final int status;

    private HttpFailure(int status, String message) {
      super(message);
      this.status = status;
    }
  }
}
