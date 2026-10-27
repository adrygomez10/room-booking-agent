package dev.adriangm.booking.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.adriangm.booking.application.booking.FindAvailableRooms;
import dev.adriangm.booking.application.booking.ReserveRoom;
import dev.adriangm.booking.application.turn.ReservationTurnCoordinator;
import dev.adriangm.booking.application.turn.RoomTools;
import dev.adriangm.booking.integration.DemoAiModel;
import dev.adriangm.booking.persistence.SqlBookingRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReservationControllerTest {
  private ReservationController controller;
  private String base;
  private final ObjectMapper json = new ObjectMapper();
  private final String testPassword = "local-test-password";

  private HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  @BeforeEach
  void start() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    var repository =
        new SqlBookingRepository("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    var tools =
        new RoomTools(new FindAvailableRooms(repository), new ReserveRoom(repository, clock));
    controller =
        new ReservationController(
            new ReservationTurnCoordinator(new DemoAiModel(), tools), clock, testPassword, "demo");
    base = "http://127.0.0.1:" + controller.start(new InetSocketAddress("127.0.0.1", 0));
  }

  @AfterEach
  void stop() {
    controller.close();
  }

  private HttpResponse<String> send(HttpClient client, String path, Object body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void login(HttpClient client) throws Exception {
    assertEquals(200, send(client, "/api/session", Map.of("password", testPassword)).statusCode());
  }

  private Map<String, Object> request() {
    return Map.of(
        "message",
        "Busca una sala con proyector y resérvala.",
        "startsAt",
        "2030-01-02T15:00:00Z",
        "endsAt",
        "2030-01-02T16:00:00Z",
        "attendeeCount",
        6,
        "reservationAllowed",
        true);
  }

  @Test
  void requiresAuthenticationAndSetsAnHttpOnlySessionCookie() throws Exception {
    var client = client();
    assertEquals(401, send(client, "/api/requests", request()).statusCode());
    var response = send(client, "/api/session", Map.of("password", testPassword));
    String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    assertEquals(201, send(client, "/api/requests", request()).statusCode());
  }

  @Test
  void preparesRunsAndReplaysTheSameServerAssignedRequest() throws Exception {
    var client = client();
    login(client);
    String id =
        json.readTree(send(client, "/api/requests", request()).body()).get("requestId").asText();
    JsonNode first = json.readTree(send(client, "/api/requests/" + id + "/run", Map.of()).body());
    assertEquals("CONFIRMED", first.path("status").asText());
    assertEquals(2, first.path("modelCalls").asInt());
    JsonNode replay = json.readTree(send(client, "/api/requests/" + id + "/run", Map.of()).body());
    assertEquals(first.path("reservation").path("id"), replay.path("reservation").path("id"));
    assertEquals(0, replay.path("modelCalls").asInt());
  }

  @Test
  void sessionsCannotRunEachOthersPendingRequests() throws Exception {
    var first = client();
    var second = client();
    login(first);
    login(second);
    String id =
        json.readTree(send(first, "/api/requests", request()).body()).get("requestId").asText();
    assertEquals(404, send(second, "/api/requests/" + id + "/run", Map.of()).statusCode());
  }

  @Test
  void rejectsForgedIdentityFieldsAndCrossOriginWrites() throws Exception {
    var client = client();
    login(client);
    var forged = new java.util.HashMap<>(request());
    forged.put("userId", "mallory");
    assertEquals(400, send(client, "/api/requests", forged).statusCode());
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/requests"))
                .header("Content-Type", "application/json")
                .header("Origin", "https://other.example")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request())))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, response.statusCode());
  }

  @Test
  void servesTheInterfaceWithContentSecurityPolicy() throws Exception {
    var response =
        client()
            .send(
                HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("Reserva de salas"));
    assertTrue(
        response
            .headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow()
            .contains("script-src 'self'"));
  }
}
