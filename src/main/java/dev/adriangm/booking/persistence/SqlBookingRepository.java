package dev.adriangm.booking.persistence;

import dev.adriangm.booking.application.TurnFailure;
import dev.adriangm.booking.application.booking.BookingRepository;
import dev.adriangm.booking.application.booking.ReservationContext;
import dev.adriangm.booking.domain.Reservation;
import dev.adriangm.booking.domain.Room;
import dev.adriangm.booking.domain.TimeSlot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persists bookings in H2 and serializes protected reservation transactions. */
public final class SqlBookingRepository implements BookingRepository {
  private final String url;

  /** Opens the configured database and initializes its schema and demonstration catalog. */
  public SqlBookingRepository(String url) {
    this.url = url;

    try (Connection connection = connect();
        var resource = getClass().getResourceAsStream("/db/schema.sql")) {
      if (resource == null) {
        throw new IllegalStateException("Missing database schema.");
      }

      for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.isBlank()) {
          try (var statement = connection.createStatement()) {
            statement.execute(sql);
          }
        }
      }
    } catch (SQLException | IOException failure) {
      throw new IllegalStateException("Cannot initialize booking storage.", failure);
    }
  }

  private Connection connect() throws SQLException {
    Connection connection = DriverManager.getConnection(url, "sa", "");
    try (var statement = connection.createStatement()) {
      statement.execute("SET LOCK_TIMEOUT 5000");
    }

    return connection;
  }

  private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setQueryTimeout(5);
    return statement;
  }

  @Override
  public List<Room> rooms() {
    try (Connection connection = connect();
        PreparedStatement statement = prepare(connection, "SELECT * FROM rooms ORDER BY id");
        ResultSet result = statement.executeQuery()) {
      List<Room> rooms = new ArrayList<>();
      while (result.next()) {
        rooms.add(room(result));
      }

      return List.copyOf(rooms);
    } catch (SQLException failure) {
      throw new TurnFailure("STORAGE_UNAVAILABLE");
    }
  }

  @Override
  public List<Reservation> reservationsIn(TimeSlot slot) {
    try (Connection connection = connect()) {
      return overlapping(connection, slot, null);
    } catch (SQLException failure) {
      throw new TurnFailure("STORAGE_UNAVAILABLE");
    }
  }

  @Override
  public Optional<Reservation> findByRequestKey(String key) {
    try (Connection connection = connect()) {
      return previous(connection, key);
    } catch (SQLException failure) {
      throw new TurnFailure("STORAGE_UNAVAILABLE");
    }
  }

  @Override
  public ReservationTransaction beginReservation(ReservationContext context, String roomId) {
    Connection connection = null;

    try {
      connection = connect();
      connection.setAutoCommit(false);

      // A shared database row serializes reservation writes, including repeated request keys.
      try (PreparedStatement lock =
              prepare(connection, "SELECT id FROM booking_write_lock WHERE id = 1 FOR UPDATE");
          ResultSet result = lock.executeQuery()) {
        if (!result.next()) {
          throw new IllegalStateException("Missing reservation write lock.");
        }
      }

      Optional<Reservation> previous = previous(connection, context.requestKey());
      Optional<Room> room = Optional.empty();
      try (PreparedStatement statement = prepare(connection, "SELECT * FROM rooms WHERE id = ?")) {
        statement.setString(1, roomId);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next()) {
            room = Optional.of(room(result));
          }
        }
      }

      List<Reservation> overlapping = overlapping(connection, context.slot(), roomId);
      return new SqlReservationTransaction(connection, room, overlapping, previous);
    } catch (SQLException failure) {
      discardTransaction(connection, failure);
      throw new TurnFailure("STORAGE_UNAVAILABLE");
    } catch (RuntimeException failure) {
      discardTransaction(connection, failure);
      throw failure;
    }
  }

  private void discardTransaction(Connection connection, Exception failure) {
    if (connection == null) {
      return;
    }

    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }

    try {
      connection.close();
    } catch (SQLException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private Optional<Reservation> previous(Connection connection, String key) throws SQLException {
    try (PreparedStatement statement =
        prepare(connection, "SELECT * FROM reservations WHERE request_key = ?")) {
      statement.setString(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(reservation(result)) : Optional.empty();
      }
    }
  }

  private List<Reservation> overlapping(Connection connection, TimeSlot slot, String roomId)
      throws SQLException {
    String sql = "SELECT * FROM reservations WHERE starts_at < ? AND ends_at > ?";
    if (roomId != null) {
      sql += " AND room_id = ?";
    }

    try (PreparedStatement statement = prepare(connection, sql)) {
      statement.setLong(1, slot.endsAt().toEpochMilli());
      statement.setLong(2, slot.startsAt().toEpochMilli());
      if (roomId != null) {
        statement.setString(3, roomId);
      }

      try (ResultSet result = statement.executeQuery()) {
        List<Reservation> reservations = new ArrayList<>();
        while (result.next()) {
          reservations.add(reservation(result));
        }

        return List.copyOf(reservations);
      }
    }
  }

  private Room room(ResultSet row) throws SQLException {
    return new Room(
        row.getString("id"),
        row.getString("name"),
        row.getInt("capacity"),
        row.getBoolean("has_projector"));
  }

  private Reservation reservation(ResultSet row) throws SQLException {
    return new Reservation(
        row.getString("id"),
        row.getString("request_key"),
        row.getString("user_id"),
        row.getString("room_id"),
        new TimeSlot(
            Instant.ofEpochMilli(row.getLong("starts_at")),
            Instant.ofEpochMilli(row.getLong("ends_at"))),
        row.getInt("attendee_count"));
  }

  private final class SqlReservationTransaction implements ReservationTransaction {
    private final Connection connection;
    private final Optional<Room> room;
    private final List<Reservation> overlapping;
    private final Optional<Reservation> previous;
    private boolean committed;
    private boolean closed;

    private SqlReservationTransaction(
        Connection connection,
        Optional<Room> room,
        List<Reservation> overlapping,
        Optional<Reservation> previous) {
      this.connection = connection;
      this.room = room;
      this.overlapping = overlapping;
      this.previous = previous;
    }

    @Override
    public Optional<Room> room() {
      return room;
    }

    @Override
    public List<Reservation> overlapping() {
      return overlapping;
    }

    @Override
    public Optional<Reservation> previous() {
      return previous;
    }

    @Override
    public void save(Reservation reservation) {
      requireActive();

      try (PreparedStatement insert =
          prepare(
              connection,
              "INSERT INTO reservations (id, request_key, user_id, room_id, starts_at, ends_at,"
                  + " attendee_count) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
        insert.setString(1, reservation.id());
        insert.setString(2, reservation.requestKey());
        insert.setString(3, reservation.userId());
        insert.setString(4, reservation.roomId());
        insert.setLong(5, reservation.slot().startsAt().toEpochMilli());
        insert.setLong(6, reservation.slot().endsAt().toEpochMilli());
        insert.setInt(7, reservation.attendeeCount());

        insert.executeUpdate();
      } catch (SQLException failure) {
        throw new TurnFailure("STORAGE_UNAVAILABLE");
      }
    }

    @Override
    public void commit() {
      requireActive();

      try {
        connection.commit();
        committed = true;
      } catch (SQLException failure) {
        throw new TurnFailure("STORAGE_UNAVAILABLE");
      }
    }

    private void requireActive() {
      if (closed || committed) {
        throw new IllegalStateException("The reservation transaction is no longer active.");
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }

      try (connection) {
        if (!committed) {
          connection.rollback();
        }
      } catch (SQLException failure) {
        throw new TurnFailure("STORAGE_UNAVAILABLE");
      } finally {
        closed = true;
      }
    }
  }
}
