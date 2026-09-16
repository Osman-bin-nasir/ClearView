-- 002_auth_rotation.sql
--
-- Refresh-token rotation with reuse detection (§3).
--
-- Rotation on its own — replacing the stored hash on every refresh — has a
-- hole: a refresh token stolen from a device (or from a database snapshot)
-- stays valid for its full lifetime, because the thief's use of it looks
-- exactly like the real client's. Nothing distinguishes them.
--
-- Keeping the hash the session rotated AWAY from changes that. When a token
-- arrives that is no longer current but IS a token this account already moved
-- past, there is no benign explanation: the legitimate client would be holding
-- the newer value. That is treated as compromise, and every session on the
-- account is revoked (see `refreshSession` in src/auth/service.ts).
--
-- The column lives on `user_sessions` rather than in a token-history table
-- because one step back is all the detection needs, and 'sid' staying stable
-- across a rotation is what lets the caller's short-lived access token keep
-- working instead of forcing a hard logout on every refresh.
--
-- Known limitation, stated plainly: only the immediately-previous hash is
-- retained. A token from two or more rotations ago is therefore rejected as an
-- ordinary invalid token rather than flagged as reuse. That still fails
-- closed — the request is refused either way — it is simply not reported as
-- the compromise it might be.
--
-- Cost worth naming: if a refresh response is lost in transit, the client
-- retries with a token that has already been rotated, which trips detection
-- and revokes the account's sessions. That is the standard trade-off for this
-- scheme, and it resolves by signing in again — the safe direction to fail.

ALTER TABLE user_sessions
  ADD COLUMN IF NOT EXISTS previous_refresh_token_hash text;

-- Partial: only rows that have actually rotated need to be searchable, and
-- reuse detection only ever looks up a non-null value.
CREATE INDEX IF NOT EXISTS user_sessions_previous_token_idx
  ON user_sessions (previous_refresh_token_hash)
  WHERE previous_refresh_token_hash IS NOT NULL;
