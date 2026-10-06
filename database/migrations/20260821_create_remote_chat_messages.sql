CREATE TABLE IF NOT EXISTS remote_chat_messages (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id),
    sender_user_id BIGINT NOT NULL REFERENCES users(id),
    sender_role VARCHAR(20) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_remote_chat_booking_created
    ON remote_chat_messages(booking_id, created_at, id);
