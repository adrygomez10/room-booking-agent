package dev.adriangm.booking.application.turn;

import dev.adriangm.booking.application.TurnFailure;
import dev.adriangm.booking.application.booking.ReservationContext;
import dev.adriangm.booking.domain.BookingFailure;
import dev.adriangm.booking.domain.Reservation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates bounded model calls and tools while retaining control of writes. */
public final class ReservationTurnCoordinator {
  private final AiModel model;
  private final RoomTools tools;

  private final int maxModelCalls;
  private final int maxToolCalls;
  private final Duration deadline;

  /** Connects a model and tool registry for handling reservation turns. */
  public ReservationTurnCoordinator(AiModel model, RoomTools tools) {
    this(model, tools, 3, 4, Duration.ofSeconds(30));
  }

  /** Connects a model and tool registry for handling reservation turns. */
  public ReservationTurnCoordinator(
      AiModel model, RoomTools tools, int modelCalls, int toolCalls, Duration deadline) {
    this.model = model;
    this.tools = tools;
    this.maxModelCalls = modelCalls;
    this.maxToolCalls = toolCalls;
    this.deadline = deadline;
  }

  /** Runs a turn or recovers its stored confirmation before requesting another model decision. */
  public Outcome run(ReservationContext context, String message) {
    if (message == null || message.isBlank() || message.length() > 2000) {
      throw new IllegalArgumentException("Message length must be between 1 and 2000.");
    }

    TurnBudget budget = new TurnBudget(maxModelCalls, maxToolCalls);
    List<Event> trace = new ArrayList<>();
    List<AiModel.Exchange> history = new ArrayList<>();
    Set<String> seenCalls = new HashSet<>();
    Set<String> offeredRooms = new HashSet<>();

    long started = System.nanoTime();

    try {
      var previous = tools.previous(context);
      if (previous.isPresent()) {
        return outcome("CONFIRMED", previous.get(), trace, budget);
      }

      while (true) {
        Duration remaining = deadline.minusNanos(System.nanoTime() - started);
        if (remaining.isNegative() || remaining.isZero()) {
          throw new TurnFailure("TURN_TIMEOUT");
        }

        budget.beforeModelCall();
        trace.add(new Event("MODEL_CALL", "", Map.of("number", budget.modelCalls())));

        AiModel.Reply reply =
            model.respond(
                new AiModel.Input(
                    message,
                    context.slot(),
                    context.attendeeCount(),
                    tools.definitions(context.reservationAllowed()),
                    history,
                    remaining.compareTo(Duration.ofSeconds(15)) < 0
                        ? remaining
                        : Duration.ofSeconds(15)));

        if (System.nanoTime() - started >= deadline.toNanos()) {
          throw new TurnFailure("TURN_TIMEOUT");
        }
        if (reply.calls().isEmpty()) {
          return outcome("NO_ACTION", null, trace, budget);
        }
        if (reply.calls().size() != 1) {
          throw new TurnFailure("MULTIPLE_TOOLS_NOT_ALLOWED");
        }

        AiModel.Call call = reply.calls().get(0);
        if (!seenCalls.add(call.id())) {
          throw new TurnFailure("DUPLICATE_CALL_ID");
        }

        trace.add(
            new Event(
                "TOOL_REQUEST",
                call.name(),
                Map.of("id", call.id(), "arguments", call.arguments())));

        budget.beforeToolCall();
        if ("reserve_room".equals(call.name())
            && context.reservationAllowed()
            && !offeredRooms.contains(call.arguments().get("roomId"))) {
          throw new TurnFailure("ROOM_NOT_OFFERED");
        }

        RoomTools.Execution execution = tools.execute(context, call);
        trace.add(
            new Event(
                "TOOL_RESULT",
                call.name(),
                Map.of("toolCallId", call.id(), "result", execution.observation().result())));

        // A confirmed write terminates immediately, without another model call.
        if (execution.reservation() != null) {
          return outcome("CONFIRMED", execution.reservation(), trace, budget);
        }

        if ("find_rooms".equals(call.name())) {
          offeredRooms.clear();
          Object results = execution.observation().result().get("rooms");
          if (results instanceof List<?> rooms) {
            for (Object value : rooms) {
              if (value instanceof Map<?, ?> room && room.get("id") instanceof String id) {
                offeredRooms.add(id);
              }
            }
          }
        }

        history.add(new AiModel.Exchange(reply, execution.observation()));
      }
    } catch (TurnFailure failure) {
      return outcome(failure.getMessage(), null, trace, budget);
    } catch (BookingFailure failure) {
      return outcome(failure.reason().name(), null, trace, budget);
    }
  }

  private Outcome outcome(
      String status, Reservation reservation, List<Event> trace, TurnBudget budget) {
    return new Outcome(
        status, reservation, List.copyOf(trace), budget.modelCalls(), budget.toolCalls());
  }

  /** Records a visible model call, tool request or application result for the turn trace. */
  public record Event(String stage, String operation, Map<String, Object> data) {}

  /** Reports the application status, confirmed reservation and consumed turn budget. */
  public record Outcome(
      String status, Reservation reservation, List<Event> trace, int modelCalls, int toolCalls) {}
}
