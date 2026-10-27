package dev.adriangm.booking.application.turn;

import dev.adriangm.booking.application.TurnFailure;
import dev.adriangm.booking.application.booking.FindAvailableRooms;
import dev.adriangm.booking.application.booking.ReservationContext;
import dev.adriangm.booking.application.booking.ReserveRoom;
import dev.adriangm.booking.domain.Reservation;
import dev.adriangm.booking.domain.Room;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validates advertised tool arguments and dispatches them to reservation use cases. */
public final class RoomTools {
  private final FindAvailableRooms search;
  private final ReserveRoom reserve;

  /** Connects the search and reservation operations exposed as tools. */
  public RoomTools(FindAvailableRooms search, ReserveRoom reserve) {
    this.search = search;
    this.reserve = reserve;
  }

  /** Advertises search and, when authorized, the reservation tool. */
  public List<AiModel.Definition> definitions(boolean reservationAllowed) {
    List<AiModel.Definition> result = new ArrayList<>();
    result.add(
        new AiModel.Definition(
            "find_rooms",
            "Find available rooms for the selected slot and attendee count.",
            schema("needsProjector", "boolean")));

    if (reservationAllowed) {
      result.add(
          new AiModel.Definition(
              "reserve_room",
              "Reserve one of the rooms returned by the search for the selected slot.",
              schema("roomId", "string")));
    }

    return List.copyOf(result);
  }

  private Map<String, Object> schema(String name, String type) {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(name, Map.of("type", type)),
        "required",
        List.of(name),
        "additionalProperties",
        false);
  }

  /** Recovers the matching stored confirmation without executing another tool. */
  public Optional<Reservation> previous(ReservationContext context) {
    return reserve.previous(context);
  }

  /** Validates and executes one tool request using the server-owned reservation context. */
  public Execution execute(ReservationContext context, AiModel.Call call) {
    if (call.id() == null || call.id().isBlank()) {
      throw new TurnFailure("INVALID_MODEL_REPLY");
    }

    if ("find_rooms".equals(call.name())) {
      if (!call.arguments().keySet().equals(Set.of("needsProjector"))
          || !(call.arguments().get("needsProjector") instanceof Boolean needsProjector)) {
        throw new TurnFailure("INVALID_ARGUMENTS");
      }

      List<Map<String, Object>> rooms = new ArrayList<>();
      for (Room room : search.execute(context, needsProjector)) {
        rooms.add(
            Map.of(
                "id",
                room.id(),
                "name",
                room.name(),
                "capacity",
                room.capacity(),
                "hasProjector",
                room.hasProjector()));
      }

      return new Execution(
          new AiModel.Result(call.id(), call.name(), Map.of("rooms", rooms)), null);
    }

    if ("reserve_room".equals(call.name())) {
      if (!context.reservationAllowed()) {
        throw new TurnFailure("FORBIDDEN");
      }
      if (!call.arguments().keySet().equals(Set.of("roomId"))
          || !(call.arguments().get("roomId") instanceof String roomId)
          || !roomId.matches("[a-z0-9-]{1,64}")) {
        throw new TurnFailure("INVALID_ARGUMENTS");
      }

      Reservation reservation = reserve.execute(context, roomId);

      return new Execution(
          new AiModel.Result(
              call.id(),
              call.name(),
              Map.of(
                  "reservationId",
                  reservation.id(),
                  "roomId",
                  reservation.roomId(),
                  "status",
                  "CONFIRMED")),
          reservation);
    }

    throw new TurnFailure("UNKNOWN_TOOL");
  }

  /** Returns a tool observation and the reservation when a write has been confirmed. */
  public record Execution(AiModel.Result observation, Reservation reservation) {}
}
