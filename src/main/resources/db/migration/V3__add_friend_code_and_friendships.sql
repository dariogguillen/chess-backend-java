-- Schema additions for the friends-list feature (feature 23.8).
--
-- Three things land here in a single forward-only migration:
--
--   1. `users.friend_code` — a stable, shareable, 8-char code that lets a user be discovered
--      WITHOUT exposing the user base to enumeration. You add a friend by typing their code,
--      not by searching a directory. Generated in the application by FriendCodeGenerator
--      (mirroring RoomCodeGenerator's unambiguous alphabet + collision-retry) at both
--      user-creation sites; pre-existing rows are backfilled below before the NOT NULL flip.
--   2. `friendships` — the relationship table. Stores raw UUIDs (requester_id, addressee_id),
--      NOT @ManyToOne associations, consistent with how `games` denormalises player identity
--      (see V1/V2 top-of-file comments). Live display names for the list endpoints come from
--      a Hibernate entity join in JPQL (JOIN User u ON u.id = f.addresseeId), so a friend's
--      rename is reflected — there is no display-name snapshot here, unlike `games`.
--   3. A UNIQUE index on the UNORDERED pair (LEAST/GREATEST) so at most one relationship can
--      exist per pair of users regardless of direction. This makes "A already sent B a
--      request" and "B already sent A a request" the same DB-level fact, closing the
--      both-directions duplicate window without an application-level lock.
--
-- WHY friend_code is generated then backfilled (rather than a DB DEFAULT).
--
-- The unambiguous alphabet (ABCDEFGHJKMNPQRSTUVWXYZ23456789, no I/L/O/0/1) and the
-- collision-retry against the UNIQUE constraint live in one place in the application
-- (FriendCodeGenerator) so runtime inserts and the migration backfill share the same
-- semantics conceptually. Postgres has no built-in generator for that exact alphabet, so the
-- backfill below uses a deterministic-but-unique server-side expression purely to populate the
-- legacy rows; every NEW user gets its code from FriendCodeGenerator. The column is added
-- nullable, backfilled, then flipped to NOT NULL + UNIQUE in that order so the migration is
-- safe on a table that already holds rows.
--
-- Column-type conventions match V2: native UUID, VARCHAR with an explicit cap that Hibernate
-- `ddl-auto: validate` checks against the JPA entity (@Column(length = 8) on User.friendCode,
-- @Column(length = 20) on Friendship.status), TIMESTAMPTZ with `DEFAULT now()` for audit-only
-- timestamps.

-- 1. friend_code on users -----------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN friend_code VARCHAR(8);

-- Backfill every pre-existing row with a unique 8-char code over the unambiguous alphabet.
-- A small PL/pgSQL block draws from the same character set FriendCodeGenerator uses and
-- retries on the (vanishingly unlikely) in-batch collision. New rows never reach this path —
-- the application supplies their code.
DO $$
DECLARE
    r           RECORD;
    alphabet    CONSTANT TEXT := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    candidate   TEXT;
    i           INT;
BEGIN
    FOR r IN SELECT id FROM users WHERE friend_code IS NULL LOOP
        LOOP
            candidate := '';
            FOR i IN 1..8 LOOP
                candidate := candidate || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
            END LOOP;
            -- Retry if this code already exists on another row.
            EXIT WHEN NOT EXISTS (SELECT 1 FROM users WHERE friend_code = candidate);
        END LOOP;
        UPDATE users SET friend_code = candidate WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE users
    ALTER COLUMN friend_code SET NOT NULL;

CREATE UNIQUE INDEX idx_users_friend_code
    ON users(friend_code);

-- 2. friendships --------------------------------------------------------------------------

CREATE TABLE friendships (
    id           UUID        PRIMARY KEY,
    requester_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    addressee_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ NULL
);

-- 3. unordered-pair uniqueness ------------------------------------------------------------
--
-- At most one relationship per pair of users, in EITHER direction. LEAST/GREATEST normalise
-- the (requester, addressee) pair to a canonical order so (A,B) and (B,A) collapse to the
-- same index key. This is the DB-level invariant that makes DuplicateFriendRequest a
-- constraint violation rather than a TOCTOU-prone application check.
CREATE UNIQUE INDEX idx_friendships_unordered_pair
    ON friendships(LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));

-- Supporting indexes for the list endpoints. The incoming/outgoing request lists filter by
-- (addressee_id, status) and (requester_id, status); the friends list filters by either side
-- with status = ACCEPTED. These partial-free composite indexes back those lookups directly.
CREATE INDEX idx_friendships_addressee_status
    ON friendships(addressee_id, status);

CREATE INDEX idx_friendships_requester_status
    ON friendships(requester_id, status);
