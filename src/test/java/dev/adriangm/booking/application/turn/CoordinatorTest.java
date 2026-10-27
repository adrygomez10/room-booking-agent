package dev.adriangm.booking.application.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.adriangm.booking.application.TurnFailure;
import dev.adriangm.booking.application.booking.FindAvailableRooms;
import dev.adriangm.booking.application.booking.ReservationContext;
import dev.adriangm.booking.application.booking.ReserveRoom;
import dev.adriangm.booking.domain.TimeSlot;
import dev.adriangm.booking.integration.DemoAiModel;
import dev.adriangm.booking.persistence.SqlBookingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoordinatorTest {
  private final SqlBookingRepository repository =
      new SqlBookingRepository("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
  private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final RoomTools tools =
      new RoomTools(new FindAvailableRooms(repository), new ReserveRoom(repository, clock));

  private ReservationContext context(boolean allow) {
    return new ReservationContext(
        "alice",
        new TimeSlot(Instant.parse("2030-01-02T15:00:00Z"), Instant.parse("2030-01-02T16:00:00Z")),
        6,
        UUID.randomUUID().toString(),
        allow);
  }

  private AiModel.Reply reply(String id, String name, Map<String, Object> args) {
    return new AiModel.Reply(List.of(new AiModel.Call(id, name, args)), "", "");
  }

  @Test
  void completesTheArticleWithTwoModelCallsAndTwoTools() {
    var result =
        new ReservationTurnCoordinator(new DemoAiModel(), tools)
            .run(context(true), "Busca una sala con proyector y resérvala.");
    assertEquals("CONFIRMED", result.status());
    assertEquals("north", result.reservation().roomId());
    assertEquals(2, result.modelCalls());
    assertEquals(2, result.toolCalls());
    assertEquals(6, result.trace().size());
  }

  @Test
  void repeatedTurnsRecoverConfirmationWithoutCallingTheModel() {
    var context = context(true);
    var first =
        new ReservationTurnCoordinator(new DemoAiModel(), tools).run(context, "Busca y reserva.");
    var repeated =
        new ReservationTurnCoordinator(
                input -> {
                  throw new AssertionError("The model must not run after a confirmed retry.");
                },
                tools)
            .run(context, "Busca y reserva.");
    assertEquals(first.reservation().id(), repeated.reservation().id());
    assertEquals(0, repeated.modelCalls());
    assertEquals(0, repeated.toolCalls());
  }

  @Test
  void readOnlyModeDoesNotAdvertiseOrExecuteTheWriteTool() {
    var result =
        new ReservationTurnCoordinator(
                input -> {
                  assertTrue(
                      input.tools().stream().noneMatch(tool -> tool.name().equals("reserve_room")));
                  return reply("call-1", "reserve_room", Map.of("roomId", "north"));
                },
                tools)
            .run(context(false), "Solo busca.");
    assertEquals("FORBIDDEN", result.status());
    assertTrue(repository.reservationsIn(context(true).slot()).isEmpty());
  }

  @Test
  void searchRequestsDoNotBecomeReservations() {
    var result =
        new ReservationTurnCoordinator(new DemoAiModel(), tools)
            .run(context(true), "Busca una sala con proyector.");
    assertEquals("NO_ACTION", result.status());
    assertNull(result.reservation());
    assertEquals(1, result.toolCalls());
  }

  @Test
  void rejectsUnknownToolsAndExtraIdentityFields() {
    var unknown =
        new ReservationTurnCoordinator(input -> reply("call-1", "delete_all", Map.of()), tools)
            .run(context(true), "Busca.");
    assertEquals("UNKNOWN_TOOL", unknown.status());
    var injected =
        new ReservationTurnCoordinator(
                input ->
                    reply(
                        "call-1",
                        "find_rooms",
                        Map.of("needsProjector", true, "userId", "mallory")),
                tools)
            .run(context(true), "Busca.");
    assertEquals("INVALID_ARGUMENTS", injected.status());
    assertTrue(repository.reservationsIn(context(true).slot()).isEmpty());
  }

  @Test
  void rejectsSeveralToolsInOneResponse() {
    var result =
        new ReservationTurnCoordinator(
                input ->
                    new AiModel.Reply(
                        List.of(
                            new AiModel.Call("c1", "find_rooms", Map.of("needsProjector", true)),
                            new AiModel.Call("c2", "reserve_room", Map.of("roomId", "north"))),
                        "",
                        ""),
                tools)
            .run(context(true), "Busca y reserva.");
    assertEquals("MULTIPLE_TOOLS_NOT_ALLOWED", result.status());
    assertEquals(0, result.toolCalls());
  }

  @Test
  void repeatedCallIdentifiersAreRejected() {
    var result =
        new ReservationTurnCoordinator(
                input -> reply("same-id", "find_rooms", Map.of("needsProjector", true)), tools)
            .run(context(true), "Busca.");
    assertEquals("DUPLICATE_CALL_ID", result.status());
    assertEquals(1, result.toolCalls());
  }

  @Test
  void repeatedSearchesConsumeTheBudgetAndEachTurnHasItsOwnCounters() {
    AtomicInteger calls = new AtomicInteger();
    var coordinator =
        new ReservationTurnCoordinator(
            input ->
                reply(
                    "call-" + calls.incrementAndGet(),
                    "find_rooms",
                    Map.of("needsProjector", true)),
            tools,
            2,
            4,
            Duration.ofSeconds(5));
    var first = coordinator.run(context(true), "Busca.");
    final var second = coordinator.run(context(true), "Busca.");
    assertEquals("BUDGET_EXHAUSTED", first.status());
    assertEquals(2, first.modelCalls());
    assertEquals(2, first.toolCalls());
    assertEquals("BUDGET_EXHAUSTED", second.status());
    assertEquals(2, second.modelCalls());
  }

  @Test
  void providerFailuresConsumeAttemptsAndStopTheTurn() {
    var result =
        new ReservationTurnCoordinator(
                input -> {
                  throw new TurnFailure("MODEL_UNAVAILABLE");
                },
                tools)
            .run(context(true), "Busca.");
    assertEquals("MODEL_UNAVAILABLE", result.status());
    assertEquals(1, result.modelCalls());
    assertEquals(0, result.toolCalls());
  }

  @Test
  void generatedTextCannotConfirmReservations() {
    var result =
        new ReservationTurnCoordinator(
                input -> new AiModel.Reply(List.of(), "Reservation confirmed!", ""), tools)
            .run(context(true), "Reserva.");
    assertEquals("NO_ACTION", result.status());
    assertNull(result.reservation());
    assertTrue(repository.reservationsIn(context(true).slot()).isEmpty());
  }

  @Test
  void reservationsMustReferToOfferedRooms() {
    var result =
        new ReservationTurnCoordinator(
                input -> reply("call-1", "reserve_room", Map.of("roomId", "north")), tools)
            .run(context(true), "Busca y reserva.");
    assertEquals("ROOM_NOT_OFFERED", result.status());
    assertTrue(repository.reservationsIn(context(true).slot()).isEmpty());
  }

  @Test
  void anExpiredTurnCannotStartAnOperation() {
    var result =
        new ReservationTurnCoordinator(new DemoAiModel(), tools, 3, 4, Duration.ofNanos(1))
            .run(context(true), "Busca y reserva.");
    assertEquals("TURN_TIMEOUT", result.status());
    assertTrue(repository.reservationsIn(context(true).slot()).isEmpty());
  }
}
