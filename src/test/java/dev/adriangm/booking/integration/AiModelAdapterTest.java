package dev.adriangm.booking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.adriangm.booking.application.TurnFailure;
import dev.adriangm.booking.application.turn.AiModel;
import dev.adriangm.booking.domain.TimeSlot;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModelAdapterTest {
  private final ObjectMapper json = new ObjectMapper();

  private AiModel.Input input(List<AiModel.Exchange> history) {
    return new AiModel.Input(
        "Busca con proyector.",
        new TimeSlot(Instant.parse("2030-01-02T15:00:00Z"), Instant.parse("2030-01-02T16:00:00Z")),
        6,
        List.of(new AiModel.Definition("find_rooms", "Search rooms.", Map.of("type", "object"))),
        history,
        Duration.ofSeconds(2));
  }

  @Test
  void preservesFullProviderContentIncludingThoughtSignaturesAndCallIds() throws Exception {
    List<JsonNode> bodies = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String reply =
        "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\""
            + ":\"model\",\"parts\":[{\"thoughtSignature\":\"opaque-test-sign"
            + "ature\",\"functionCall\":{\"id\":\"provider-call-1\",\"name\":\"f"
            + "ind_rooms\",\"args\":{\"needsProjector\":true}}}]}}]}";
    server.createContext(
        "/model",
        exchange -> {
          assertEquals("placeholder-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          assertNull(exchange.getRequestURI().getQuery());
          bodies.add(json.readTree(exchange.getRequestBody()));
          byte[] response = reply.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new AiModelAdapter(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model"),
              "placeholder-key");
      AiModel.Reply first = adapter.respond(input(List.of()));
      assertEquals("provider-call-1", first.calls().get(0).id());
      var result =
          new AiModel.Result(first.calls().get(0).id(), "find_rooms", Map.of("rooms", List.of()));
      adapter.respond(input(List.of(new AiModel.Exchange(first, result))));
      assertEquals(
          json.readTree(reply).path("candidates").get(0).path("content"),
          bodies.get(1).path("contents").get(1));
      assertEquals(
          "provider-call-1",
          bodies
              .get(1)
              .path("contents")
              .get(2)
              .path("parts")
              .get(0)
              .path("functionResponse")
              .path("id")
              .asText());
      assertTrue(
          bodies
              .get(0)
              .path("tools")
              .get(0)
              .path("functionDeclarations")
              .get(0)
              .has("parametersJsonSchema"));
      assertFalse(bodies.get(0).toString().contains("userId"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerFailuresAreNotRetried() throws Exception {
    java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/model",
        exchange -> {
          count.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new AiModelAdapter(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model"),
              "placeholder-key");
      assertEquals(
          "MODEL_UNAVAILABLE",
          assertThrows(TurnFailure.class, () -> adapter.respond(input(List.of()))).getMessage());
      assertEquals(1, count.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void malformedJsonAndNullArgumentsAreRejected() throws Exception {
    assertInvalidResponse("not-json");
    assertInvalidResponse(
        "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\""
            + ":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"find_rooms\","
            + "\"args\":{\"needsProjector\":null}}}]}}]}");
    assertInvalidResponse("{}");
  }

  private void assertInvalidResponse(String payload) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/model",
        exchange -> {
          byte[] response = payload.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new AiModelAdapter(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model"),
              "placeholder-key");
      assertEquals(
          "INVALID_MODEL_REPLY",
          assertThrows(TurnFailure.class, () -> adapter.respond(input(List.of()))).getMessage());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void truncatedModelResponsesCannotBecomeActions() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/model",
        exchange -> {
          byte[] response =
              "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[]}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new AiModelAdapter(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model"),
              "placeholder-key");
      assertEquals(
          "INVALID_MODEL_REPLY",
          assertThrows(TurnFailure.class, () -> adapter.respond(input(List.of()))).getMessage());
    } finally {
      server.stop(0);
    }
  }
}
