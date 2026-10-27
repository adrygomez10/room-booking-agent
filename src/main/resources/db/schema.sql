CREATE TABLE IF NOT EXISTS booking_write_lock (id INTEGER PRIMARY KEY);
INSERT INTO booking_write_lock SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM booking_write_lock WHERE id = 1);
CREATE TABLE IF NOT EXISTS rooms (id VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, capacity INTEGER NOT NULL CHECK (capacity > 0), has_projector BOOLEAN NOT NULL);
INSERT INTO rooms SELECT 'north', 'North', 8, TRUE WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE id = 'north');
INSERT INTO rooms SELECT 'south', 'South', 4, FALSE WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE id = 'south');
CREATE TABLE IF NOT EXISTS reservations (
    id VARCHAR(64) PRIMARY KEY, request_key VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL, room_id VARCHAR(64) NOT NULL REFERENCES rooms(id),
    starts_at BIGINT NOT NULL, ends_at BIGINT NOT NULL,
    attendee_count INTEGER NOT NULL CHECK (attendee_count > 0), CHECK (ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS reservation_overlap ON reservations(room_id, starts_at, ends_at);
