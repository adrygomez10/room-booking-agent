package dev.adriangm.booking.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DomainTest {
    private final Instant start = Instant.parse("2030-01-02T15:00:00Z");

    @Test
    void requiresAnOrderedBoundedSlot()
    {
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(start, start.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(start, start.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(start, start.plusSeconds(9 * 3600)));
        assertThrows(IllegalArgumentException.class, () -> new TimeSlot(start.plusNanos(1), start.plusSeconds(3600)));
    }

    @Test
    void detectsOverlapButAllowsAdjacentSlots()
    {
        TimeSlot slot = new TimeSlot(start, start.plusSeconds(3600));
        assertTrue(slot.overlaps(new TimeSlot(start.plusSeconds(1800), start.plusSeconds(5400))));
        assertFalse(slot.overlaps(new TimeSlot(start.plusSeconds(3600), start.plusSeconds(7200))));
    }

    @Test
    void roomsOwnTheirCapacityAndEquipmentRules()
    {
        Room room = new Room("north", "North", 8, true);
        assertTrue(room.accommodates(6, true));
        assertFalse(room.accommodates(9, false));
        assertFalse(new Room("south", "South", 4, false).accommodates(2, true));
        assertEquals(
            BookingFailure.Reason.INSUFFICIENT_CAPACITY,
            assertThrows(BookingFailure.class, () -> room.requireCapacity(9)).reason()
        );
        assertThrows(IllegalArgumentException.class, () -> new Room("north", "North", 0, false));
    }

    @Test
    void reservationsRejectKeysReusedWithDifferentData()
    {
        TimeSlot slot = new TimeSlot(start, start.plusSeconds(3600));
        Reservation reservation = new Reservation("r1", "k1", "alice", "north", slot, 6);
        assertDoesNotThrow(() -> reservation.requireSameRequest("alice", slot, 6));
        assertThrows(BookingFailure.class, () -> reservation.requireSameRequest("bob", slot, 6));
        assertThrows(BookingFailure.class, () -> reservation.requireSameRequest("alice", slot, 5));
    }
}
