package dev.adriangm.booking.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.adriangm.booking.application.booking.BookingRepository;
import dev.adriangm.booking.application.booking.FindAvailableRooms;
import dev.adriangm.booking.application.booking.ReservationContext;
import dev.adriangm.booking.application.booking.ReserveRoom;
import dev.adriangm.booking.domain.BookingFailure;
import dev.adriangm.booking.domain.Reservation;
import dev.adriangm.booking.domain.Room;
import dev.adriangm.booking.domain.TimeSlot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlBookingRepositoryTest {
  private final Instant start = Instant.parse("2030-01-02T15:00:00Z");
  private final Clock clock = Clock.fixed(start.minusSeconds(86400), ZoneOffset.UTC);

  private SqlBookingRepository repository() {
    return new SqlBookingRepository("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
  }

  private ReservationContext context(String key, String actor, int attendees) {
    return new ReservationContext(
        actor, new TimeSlot(start, start.plusSeconds(3600)), attendees, key, true);
  }

  @Test
  void searchesRealRowsAndAppliesCapacityAndEquipment() {
    var repository = repository();
    assertEquals(
        java.util.List.of("north"),
        new FindAvailableRooms(repository)
            .execute(context("k1", "alice", 6), true).stream().map(Room::id).toList());
    new ReserveRoom(repository, clock).execute(context("k1", "alice", 6), "north");
    assertTrue(new FindAvailableRooms(repository).execute(context("k2", "bob", 6), true).isEmpty());
  }

  @Test
  void repeatedRequestsReturnTheSameStoredReservation() {
    var repository = repository();
    var reserve = new ReserveRoom(repository, clock);
    var context = context("same-key", "alice", 6);
    Reservation first = reserve.execute(context, "north");
    assertEquals(first.id(), reserve.execute(context, "north").id());
    assertEquals(1, repository.reservationsIn(context.slot()).size());
  }

  @Test
  void rejectsKeyReuseAcrossRoomsOrActors() {
    var repository = repository();
    var reserve = new ReserveRoom(repository, clock);
    reserve.execute(context("same-key", "alice", 2), "north");
    assertEquals(
        BookingFailure.Reason.REQUEST_KEY_CONFLICT,
        assertThrows(
                BookingFailure.class,
                () -> reserve.execute(context("same-key", "alice", 2), "south"))
            .reason());
    assertThrows(
        BookingFailure.class, () -> reserve.execute(context("same-key", "bob", 2), "north"));
    assertEquals(1, repository.reservationsIn(context("key", "alice", 2).slot()).size());
  }

  @Test
  void competingWritersCannotReserveAnOverlappingSlot() throws Exception {
    var repository = repository();
    var reserve = new ReserveRoom(repository, clock);
    var executor = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    try {
      var first = executor.submit(() -> attempt(reserve, context("k1", "alice", 6), ready, go));
      var second = executor.submit(() -> attempt(reserve, context("k2", "bob", 6), ready, go));
      assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      go.countDown();
      assertEquals(1, first.get() + second.get());
      assertEquals(1, repository.reservationsIn(context("unused", "alice", 6).slot()).size());
    } finally {
      executor.shutdownNow();
    }
  }

  private int attempt(
      ReserveRoom reserve, ReservationContext context, CountDownLatch ready, CountDownLatch go)
      throws Exception {
    ready.countDown();
    go.await();
    try {
      reserve.execute(context, "north");
      return 1;
    } catch (BookingFailure failure) {
      assertEquals(BookingFailure.Reason.SLOT_UNAVAILABLE, failure.reason());
      return 0;
    }
  }

  @Test
  void rejectsPartialOverlapAndAllowsAnAdjacentBooking() {
    var repository = repository();
    var reserve = new ReserveRoom(repository, clock);
    reserve.execute(context("k1", "alice", 6), "north");
    var overlapping =
        new ReservationContext(
            "bob", new TimeSlot(start.plusSeconds(1800), start.plusSeconds(5400)), 6, "k2", true);
    assertThrows(BookingFailure.class, () -> reserve.execute(overlapping, "north"));
    var adjacent =
        new ReservationContext(
            "bob", new TimeSlot(start.plusSeconds(3600), start.plusSeconds(7200)), 6, "k3", true);
    assertNotNull(reserve.execute(adjacent, "north"));
  }

  @Test
  void closingAnUncommittedTransactionRollsBackItsWrite() {
    var repository = repository();
    var context = context("k1", "alice", 6);

    try (BookingRepository.ReservationTransaction transaction =
        repository.beginReservation(context, "north")) {
      transaction.save(new Reservation("uncommitted", "k1", "alice", "north", context.slot(), 6));
    }

    assertTrue(repository.findByRequestKey("k1").isEmpty());
    assertNotNull(new ReserveRoom(repository, clock).execute(context, "north"));
  }

  @Test
  void persistenceSurvivesRecreatingTheRepository(@TempDir Path directory) {
    String url = "jdbc:h2:file:" + directory.resolve("bookings").toAbsolutePath();
    var context = context("persistent-key", "alice", 6);
    Reservation saved =
        new ReserveRoom(new SqlBookingRepository(url), clock).execute(context, "north");
    assertEquals(
        saved.id(),
        new SqlBookingRepository(url).findByRequestKey(context.requestKey()).orElseThrow().id());
  }

  @Test
  void readOnlyAndPastRequestsCannotCreateReservations() {
    var repository = repository();
    var reserve = new ReserveRoom(repository, clock);
    var readOnly =
        new ReservationContext("alice", context("k1", "alice", 6).slot(), 6, "k1", false);
    assertEquals(
        BookingFailure.Reason.FORBIDDEN,
        assertThrows(BookingFailure.class, () -> reserve.execute(readOnly, "north")).reason());
    var past =
        new ReservationContext(
            "alice",
            new TimeSlot(start.minusSeconds(172800), start.minusSeconds(169200)),
            6,
            "past",
            true);
    assertEquals(
        BookingFailure.Reason.SLOT_IN_PAST,
        assertThrows(BookingFailure.class, () -> reserve.execute(past, "north")).reason());
  }
}
