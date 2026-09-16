import { randomBytes } from 'node:crypto';
import jwt from 'jsonwebtoken';
import type { SignOptions } from 'jsonwebtoken';
import { env, hashToken } from '../env.js';

/**
 * Token minting and verification for Good Post accounts (§3).
 *
 * Two tokens with deliberately different jobs:
 *
 *  - ACCESS token — a short-lived JWT (ACCESS_TOKEN_TTL, 15m by default)
 *    carrying only the user id and the session id. It is verified by
 *    signature alone, so no database round trip is needed on every request.
 *    `sid` is present so a later milestone can revoke one device without
 *    touching the others.
 *
 *  - REFRESH token — 48 random bytes, never a JWT, and NEVER stored. Only its
 *    SHA-256 hash reaches the database (`user_sessions.refresh_token_hash`),
 *    so a database leak cannot be replayed against the API. It is the piece
 *    that is checked against the database, which is what makes server-side
 *    revocation possible at all — a pure-JWT scheme cannot revoke.
 */

const ISSUER = 'clearview-goodpost';

/**
 * Audience-pinned, so a token minted for another service sharing JWT_SECRET
 * cannot be replayed here.
 */
const AUDIENCE = 'clearview-android';

export interface AccessTokenClaims {
  /** User id (`users.id`). */
  readonly sub: string;
  /** Session id (`user_sessions.id`) — the device this token belongs to. */
  readonly sid: string;
}

export function signAccessToken(claims: AccessTokenClaims): string {
  const options: SignOptions = {
    subject: claims.sub,
    issuer: ISSUER,
    audience: AUDIENCE,
    // The env var is validated as a duration string ("15m"); the `ms` typings
    // model it as a template-literal union that a plain `string` cannot
    // satisfy, so this is a narrowing the runtime already guarantees.
    expiresIn: env.ACCESS_TOKEN_TTL as SignOptions['expiresIn'],
  };
  return jwt.sign({ sid: claims.sid }, env.JWT_SECRET, options);
}

/**
 * Verify a bearer token. Returns null for ANY problem — expired, wrong
 * signature, wrong issuer/audience, malformed, or simply not carrying the two
 * claims we require. Callers must never distinguish those cases to the client;
 * "your token is bad" is all an unauthenticated caller is entitled to know.
 */
export function verifyAccessToken(token: string): AccessTokenClaims | null {
  try {
    const decoded = jwt.verify(token, env.JWT_SECRET, {
      issuer: ISSUER,
      audience: AUDIENCE,
    });
    if (typeof decoded === 'string') return null;

    const sub = decoded.sub;
    const sid = (decoded as { sid?: unknown }).sid;
    if (typeof sub !== 'string' || sub.length === 0) return null;
    if (typeof sid !== 'string' || sid.length === 0) return null;

    return { sub, sid };
  } catch {
    return null;
  }
}

/**
 * A fresh refresh token and the hash that gets persisted. The raw value is
 * returned exactly once, to be handed to the client, and is never recoverable
 * from the database afterwards.
 */
export function mintRefreshToken(): { token: string; hash: string } {
  // base64url of 48 bytes ≈ 64 chars. Random, not derived from the user, so
  // two sessions for the same account never collide.
  const token = randomBytes(48).toString('base64url');
  return { token, hash: hashToken(token) };
}

/** When a refresh token minted now stops being accepted. */
export function refreshTokenExpiry(): Date {
  return new Date(Date.now() + env.REFRESH_TOKEN_TTL_DAYS * 24 * 60 * 60 * 1000);
}

/** Access-token lifetime in seconds, for the client's refresh scheduling. */
export function accessTokenTtlSeconds(): number {
  const raw = env.ACCESS_TOKEN_TTL.trim();
  const match = /^(\d+)\s*([smhd])?$/.exec(raw);
  if (!match) return 900;
  const amount = Number(match[1]);
  const unit = match[2] ?? 's';
  const multiplier = unit === 'm' ? 60 : unit === 'h' ? 3600 : unit === 'd' ? 86400 : 1;
  return amount * multiplier;
}
