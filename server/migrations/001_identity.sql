-- 001_identity.sql
--
-- Accounts, sessions, phone-verification control, and banned identities.
--
-- Design notes that matter:
--
--  * A mobile number is NEVER stored in plaintext. Only
--    HMAC-SHA256(E.164, PHONE_HASH_PEPPER) is persisted, in `phone_hash`.
--    That hash is the platform's abuse-prevention identity (§19): it is what
--    a ban blocks, and it is why a banned user cannot escape by changing
--    their email, display name, or by reinstalling the app. Phone hashing
--    also means a database leak does not expose phone numbers (§38), and it
--    lets the pepper be rotated via `phone_hash_version` without discarding
--    existing ban history.
--
--  * Admin identities deliberately do NOT live here. `users` has no
--    is_admin flag: §48 requires the platform-admin system to be separate,
--    so admin accounts arrive in their own table (migration 006) and are
--    referenced by id. A channel owner can never become a platform admin by
--    owning a channel.
--
--  * Deletion is soft (`deleted_at`). §37 needs the private auth identity,
--    the public profile, channel ownership and moderation history to be
--    separable — none of that is possible if rows are hard-deleted.
--
-- No BEGIN/COMMIT here on purpose: src/migrate.ts wraps each file in its own
-- transaction and records the checksum, so a migration is all-or-nothing and
-- the files themselves stay replayable.

-- ── Enums ───────────────────────────────────────────────────────────────
DO $$ BEGIN
  CREATE TYPE account_status AS ENUM ('active', 'suspended', 'banned');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── users ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Public identity. Never contains the phone number or email.
  display_name       text NOT NULL
                       CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 60),

  -- Account identity. `email` keeps what the user typed (for display and for
  -- outbound mail); `email_normalized` is the lookup/uniqueness key. Email is
  -- private: it must never appear in a payload returned to another user.
  email              text NOT NULL,
  email_normalized   text NOT NULL,

  -- Abuse-prevention identity. See the note at the top of this file.
  phone_hash         text NOT NULL,
  phone_hash_version smallint NOT NULL DEFAULT 1,

  status             account_status NOT NULL DEFAULT 'active',

  -- ── Lifecycle timestamps ──
  -- `banned_at`/`suspended_at` are separate from `status` so moderation
  -- history survives a later un-suspend (migration 006 records who did it).
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  suspended_at       timestamptz,
  banned_at          timestamptz,
  deleted_at         timestamptz,

  -- ── Public profile (separated so §37 account deletion stays tractable) ──
  bio                text CHECK (bio IS NULL OR char_length(bio) <= 300),
  avatar_object_key  text,          -- S3 key, never a URL: keys can be re-signed
  country_code       char(2),       -- ISO 3166-1 alpha-2, display only
  messages_from_followers boolean NOT NULL DEFAULT false
);

-- One account per email, among live accounts. A deleted account releases its
-- email so the address can be reused.
CREATE UNIQUE INDEX IF NOT EXISTS users_email_active_uniq
  ON users (email_normalized) WHERE deleted_at IS NULL;

-- One account per mobile number, EVER — deleted rows included. Releasing a
-- phone hash on self-deletion would hand banned users a trivial bypass:
-- delete the account, register again with the same SIM card.
CREATE UNIQUE INDEX IF NOT EXISTS users_phone_hash_uniq
  ON users (phone_hash);

CREATE INDEX IF NOT EXISTS users_status_idx ON users (status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS users_created_idx ON users (created_at DESC);

-- ── user_sessions ───────────────────────────────────────────────────────
-- Refresh-token rotation. Only the SHA-256 of the refresh token is stored, so
-- a database leak cannot be replayed against the API. `revoked_at` is how a
-- ban (§19) and a forced logout (§30) invalidate every device at once.
CREATE TABLE IF NOT EXISTS user_sessions (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  refresh_token_hash text NOT NULL,
  device_label       text,
  -- Hashed, never raw. §29 allows request/device info "only where
  -- appropriate and legally justified" — a hash supports abuse correlation
  -- without retaining a readable IP log.
  ip_hash            text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  last_used_at       timestamptz,
  expires_at         timestamptz NOT NULL,
  revoked_at         timestamptz,
  revoked_reason     text
);

CREATE UNIQUE INDEX IF NOT EXISTS user_sessions_token_uniq
  ON user_sessions (refresh_token_hash);
CREATE INDEX IF NOT EXISTS user_sessions_live_idx
  ON user_sessions (user_id) WHERE revoked_at IS NULL;

-- ── phone_verifications ─────────────────────────────────────────────────
-- Firebase sends and checks the SMS code, so this table holds no codes. It is
-- the server-side abuse ledger the spec asks for in §3: it is what enforces
-- resend limits, attempt limits, expiry and rate limiting on OTP issuance —
-- all of which Firebase alone does not give us per-account.
CREATE TABLE IF NOT EXISTS phone_verifications (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  phone_hash   text NOT NULL,
  purpose      text NOT NULL CHECK (purpose IN ('register', 'signin', 'recover')),
  send_count   smallint NOT NULL DEFAULT 1,
  attempts     smallint NOT NULL DEFAULT 0,
  max_attempts smallint NOT NULL DEFAULT 5,
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  verified_at  timestamptz,
  consumed_at  timestamptz
);

-- Rate limiting looks up "how many sends for this phone in the last hour".
CREATE INDEX IF NOT EXISTS phone_verifications_phone_idx
  ON phone_verifications (phone_hash, created_at DESC);

-- ── banned_identities ───────────────────────────────────────────────────
-- The enforcement side of §19. Checked on registration AND on account
-- recovery, so a banned mobile identity cannot re-enter the platform.
CREATE TABLE IF NOT EXISTS banned_identities (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),

  -- The primary key of the ban. Deliberately NOT NULL: a ban must always be
  -- anchored to a mobile identity, because that is the only signal that
  -- survives an email change, a rename, a reinstall or a data wipe.
  phone_hash         text NOT NULL,
  phone_hash_version smallint NOT NULL DEFAULT 1,

  -- Secondary signal only. Never the sole basis for a ban, and never exposed.
  email_normalized   text,

  reason             text NOT NULL,
  note               text,          -- internal moderator note, admin-only

  -- FK to admin_users is added in migration 006, once that table exists:
  -- this migration must be applicable on its own.
  banned_by_admin_id uuid,

  created_at         timestamptz NOT NULL DEFAULT now(),
  lifted_at          timestamptz
);

-- At most one ACTIVE ban per identity; lifting a ban keeps the row so the
-- moderation history is not destroyed.
CREATE UNIQUE INDEX IF NOT EXISTS banned_identities_phone_active
  ON banned_identities (phone_hash) WHERE lifted_at IS NULL;

-- ── updated_at maintenance ──────────────────────────────────────────────
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS users_set_updated_at ON users;
CREATE TRIGGER users_set_updated_at
  BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
