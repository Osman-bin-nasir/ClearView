import type { Express } from 'express';
import request from 'supertest';
import type { PGlite } from '@electric-sql/pglite';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { closePool, type Queryable } from '../src/db.js';
import { hashPhone } from '../src/env.js';
import type { PhoneIdentityVerifier } from '../src/auth/firebase.js';
import { unauthorized } from '../src/http/errors.js';
import { applyAllMigrations, asQueryable, freshDatabase, resetData } from './helpers/database.js';

/**
 * M1 authentication (§2, §3, §19, §32, §39).
 *
 * Driven through the real Express app against a real Postgres (PGlite), so a
 * pass here means the middleware, the SQL, the constraints and the error
 * mapping all agree — not that a mock was called.
 *
 * Most of these tests are adversarial on purpose. §39 asks for unauthorised
 * requests to be attempted explicitly, and the interesting failure of an auth
 * system is never the happy path.
 */

const PHONE = '+923001234567';
const OTHER_PHONE = '+923009999999';
const EMAIL = 'ayesha@example.test';

/**
 * Stands in for Firebase. Rejects anything it does not recognise exactly as
 * the real verifier does, so a test cannot pass by having a lenient double.
 */
function fakeVerifier(): PhoneIdentityVerifier {
  return {
    kind: 'firebase',
    async verifyIdToken(idToken: string) {
      const phone = idToken.startsWith('test:') ? idToken.slice('test:'.length) : '';
      if (!/^\+[1-9]\d{6,14}$/.test(phone)) {
        throw unauthorized('invalid_id_token', 'The supplied identity token is not valid.');
      }
      return { phoneE164: phone, firebaseUid: `uid:${phone}` };
    },
  };
}

const idTokenFor = (phone: string): string => `test:${phone}`;

let pglite: PGlite;
let database: Queryable;
let app: Express;

beforeAll(async () => {
  pglite = await freshDatabase();
  await applyAllMigrations(pglite);
  database = asQueryable(pglite);
  app = buildApp({ database, verifier: fakeVerifier() });
});

beforeEach(async () => {
  await resetData(pglite);
});

afterAll(async () => {
  await pglite.close();
  await closePool();
});

// ── Helpers ─────────────────────────────────────────────────────────────

const requestOtp = (phone = PHONE, purpose = 'signin') =>
  request(app).post('/api/v1/auth/otp/request').send({ phone, purpose });

const registerBody = (phone = PHONE, overrides: Record<string, unknown> = {}) => ({
  idToken: idTokenFor(phone),
  displayName: 'Ayesha',
  email: EMAIL,
  ...overrides,
});

/** Full happy path up to a live session. */
async function registered(phone = PHONE, email = EMAIL) {
  await requestOtp(phone, 'register');
  const res = await request(app)
    .post('/api/v1/auth/register')
    .send(registerBody(phone, { email }));
  expect(res.status).toBe(201);
  return res.body as {
    accessToken: string;
    refreshToken: string;
    expiresIn: number;
    user: { id: string; email: string; status: string };
  };
}

const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

// ── OTP issuance (§3) ───────────────────────────────────────────────────

describe('POST /api/v1/auth/otp/request', () => {
  it('records a challenge and reports the remaining allowance', async () => {
    const res = await requestOtp();

    expect(res.status).toBe(200);
    expect(typeof res.body.expiresAt).toBe('string');
    expect(res.body.sendsRemaining).toBe(4);
  });

  it('refuses to keep issuing codes past the hourly limit', async () => {
    for (let i = 0; i < 5; i += 1) {
      expect((await requestOtp()).status).toBe(200);
    }

    const res = await requestOtp();
    expect(res.status).toBe(429);
    expect(res.body.error).toBe('otp_rate_limited');
  });

  it('rejects a phone number that is not E.164', async () => {
    const res = await requestOtp('03001234567');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_phone');
  });

  it('never returns or stores the phone number itself', async () => {
    const res = await requestOtp();

    // §38: the number is hashed on arrival. It must not appear in the response
    // body, and nothing raw may reach the ledger.
    expect(JSON.stringify(res.body)).not.toContain(PHONE);

    const { rows } = await pglite.query<{ phone_hash: string }>(
      'SELECT phone_hash FROM phone_verifications'
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]?.phone_hash).not.toContain(PHONE);
    expect(rows[0]?.phone_hash).toHaveLength(64);
  });

  it('refuses a banned mobile identity before any code is sent', async () => {
    await pglite.query('INSERT INTO banned_identities (phone_hash, reason) VALUES ($1, $2)', [
      hashPhone(PHONE),
      'test ban',
    ]);

    const res = await requestOtp();
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('phone_banned');
  });
});

// ── Registration (§2, §19) ──────────────────────────────────────────────

describe('POST /api/v1/auth/register', () => {
  it('creates an account and returns a usable session', async () => {
    const session = await registered();

    expect(session.accessToken).toEqual(expect.any(String));
    expect(session.refreshToken).toEqual(expect.any(String));
    expect(session.expiresIn).toBe(900);
    expect(session.user.email).toBe(EMAIL);

    const me = await request(app).get('/api/v1/auth/me').set(authed(session.accessToken));
    expect(me.status).toBe(200);
    expect(me.body.user.id).toBe(session.user.id);
  });

  it('requires a verification challenge to have been requested first', async () => {
    const res = await request(app).post('/api/v1/auth/register').send(registerBody());

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('otp_required');
    // Nothing may be written when the challenge is missing.
    const { rows } = await pglite.query('SELECT id FROM users');
    expect(rows).toHaveLength(0);
  });

  it('rejects an expired challenge', async () => {
    await requestOtp(PHONE, 'register');
    await pglite.query(`UPDATE phone_verifications SET expires_at = now() - interval '1 minute'`);

    const res = await request(app).post('/api/v1/auth/register').send(registerBody());
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('otp_required');
  });

  it('refuses a bad identity token', async () => {
    await requestOtp(PHONE, 'register');
    const res = await request(app)
      .post('/api/v1/auth/register')
      .send(registerBody(PHONE, { idToken: 'test:not-a-phone' }));

    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_id_token');
  });

  it('will not create a second account for the same mobile number', async () => {
    await registered();

    await requestOtp(PHONE, 'register');
    const res = await request(app)
      .post('/api/v1/auth/register')
      .send(registerBody(PHONE, { email: 'someone-else@example.test' }));

    expect(res.status).toBe(409);
    expect(res.body.error).toBe('phone_already_registered');
  });

  it('will not reuse an email address', async () => {
    await registered();

    await requestOtp(OTHER_PHONE, 'register');
    const res = await request(app).post('/api/v1/auth/register').send(registerBody(OTHER_PHONE));

    expect(res.status).toBe(409);
    expect(res.body.error).toBe('email_already_registered');
  });

  it('blocks a banned mobile identity BEFORE any other rule is considered (§19)', async () => {
    // No user row exists for this number, so a ban is the only thing that can
    // stop it. Asserting `phone_banned` rather than `phone_already_registered`
    // proves the ban check runs first: changing the email, the display name,
    // the SIM slot or reinstalling the app cannot get past it.
    await pglite.query('INSERT INTO banned_identities (phone_hash, reason) VALUES ($1, $2)', [
      hashPhone(PHONE),
      'evasion attempt',
    ]);
    await requestOtp(PHONE, 'register');

    const res = await request(app).post('/api/v1/auth/register').send(registerBody());

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('phone_banned');
    const { rows } = await pglite.query('SELECT id FROM users');
    expect(rows).toHaveLength(0);
  });

  it('enforces the display-name constraint the database states', async () => {
    await requestOtp(PHONE, 'register');
    const res = await request(app)
      .post('/api/v1/auth/register')
      .send(registerBody(PHONE, { displayName: '   ' }));

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_display_name');
  });

  it('locks a challenge out after repeated blocked attempts', async () => {
    await requestOtp(PHONE, 'register');
    await pglite.query('UPDATE phone_verifications SET attempts = max_attempts');

    const res = await request(app).post('/api/v1/auth/register').send(registerBody());
    expect(res.status).toBe(429);
    expect(res.body.error).toBe('otp_locked');
  });
});

// ── Sign-in ─────────────────────────────────────────────────────────────

describe('POST /api/v1/auth/signin', () => {
  it('returns a session for a registered number', async () => {
    await registered();
    await requestOtp(PHONE, 'signin');

    const res = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });

    expect(res.status).toBe(200);
    expect(res.body.user.email).toBe(EMAIL);
  });

  it('reports an unknown number as not found', async () => {
    await requestOtp(OTHER_PHONE, 'signin');

    const res = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(OTHER_PHONE) });

    expect(res.status).toBe(404);
    expect(res.body.error).toBe('account_not_found');
  });

  it('refuses a banned identity even though the account exists (§19)', async () => {
    await registered();
    await pglite.query('INSERT INTO banned_identities (phone_hash, reason) VALUES ($1, $2)', [
      hashPhone(PHONE),
      'test ban',
    ]);
    await requestOtp(PHONE, 'signin');

    const res = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('phone_banned');
  });

  it('refuses a suspended account', async () => {
    await registered();
    await pglite.query(`UPDATE users SET status = 'suspended', suspended_at = now()`);
    await requestOtp(PHONE, 'signin');

    const res = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('account_suspended');
  });

  it('records blocked attempts durably, so the limit can actually be reached', async () => {
    // Regression: this increment used to run inside the transaction that then
    // threw, so the rollback erased it and a suspended account could retry
    // without ever accumulating an attempt.
    await registered();
    await pglite.query(`UPDATE users SET status = 'suspended', suspended_at = now()`);

    await requestOtp(PHONE, 'signin');
    const denied = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });
    expect(denied.status).toBe(403);

    const { rows } = await pglite.query<{ attempts: number }>(
      'SELECT attempts FROM phone_verifications ORDER BY created_at DESC LIMIT 1'
    );
    expect(rows[0]?.attempts).toBe(1);
  });

  it('treats a soft-deleted account as never having existed (§37)', async () => {
    await registered();
    await pglite.query(`UPDATE users SET deleted_at = now()`);
    await requestOtp(PHONE, 'signin');

    const res = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });

    expect(res.status).toBe(404);
    expect(res.body.error).toBe('account_not_found');
  });
});

// ── Refresh rotation (§3) ───────────────────────────────────────────────

describe('POST /api/v1/auth/refresh', () => {
  it('rotates the token and invalidates the one presented', async () => {
    const first = await registered();

    const rotated = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: first.refreshToken });

    expect(rotated.status).toBe(200);
    expect(rotated.body.refreshToken).not.toBe(first.refreshToken);

    // The new token works…
    const again = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: rotated.body.refreshToken });
    expect(again.status).toBe(200);
  });

  it('detects replay of a rotated token and revokes every session (§3)', async () => {
    const first = await registered();

    const rotated = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: first.refreshToken });
    expect(rotated.status).toBe(200);

    // …and a second device, to prove the blast radius is the whole account.
    await requestOtp(PHONE, 'signin');
    const secondDevice = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });
    expect(secondDevice.status).toBe(200);

    // Replaying the superseded token is not a stale client: it means someone
    // else holds a copy.
    const replay = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: first.refreshToken });
    expect(replay.status).toBe(401);
    expect(replay.body.error).toBe('refresh_token_reused');

    // The stolen-token holder is locked out…
    expect(
      (
        await request(app)
          .post('/api/v1/auth/refresh')
          .send({ refreshToken: rotated.body.refreshToken })
      ).status
    ).toBe(401);

    // …and so is the legitimate second device, which is the point: the account
    // is forced through phone verification again.
    expect(
      (
        await request(app)
          .post('/api/v1/auth/refresh')
          .send({ refreshToken: secondDevice.body.refreshToken })
      ).status
    ).toBe(401);
  });

  it('rejects an unknown refresh token', async () => {
    const res = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: 'x'.repeat(64) });

    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_refresh_token');
  });

  it('rejects a session whose account has since been banned', async () => {
    const session = await registered();
    await pglite.query(`UPDATE users SET status = 'banned', banned_at = now()`);

    const res = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: session.refreshToken });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('account_banned');
  });
});

// ── Session revocation (§19) ────────────────────────────────────────────

describe('session revocation', () => {
  it('logout destroys the session it was given', async () => {
    const session = await registered();

    const out = await request(app)
      .post('/api/v1/auth/logout')
      .set(authed(session.accessToken))
      .send({ refreshToken: session.refreshToken });
    expect(out.status).toBe(200);
    expect(out.body.revoked).toBe(1);

    const me = await request(app).get('/api/v1/auth/me').set(authed(session.accessToken));
    expect(me.status).toBe(401);
    expect(me.body.error).toBe('session_revoked');
  });

  it('a ban takes effect on the very next request, not when the token expires', async () => {
    const session = await registered();
    expect((await request(app).get('/api/v1/auth/me').set(authed(session.accessToken))).status).toBe(
      200
    );

    // Banning revokes sessions AND flips the account status; both paths in
    // requireAuth must refuse, so this asserts the response, not the mechanism.
    await pglite.query(
      `UPDATE user_sessions SET revoked_at = now(), revoked_reason = 'banned' WHERE user_id = $1`,
      [session.user.id]
    );

    const me = await request(app).get('/api/v1/auth/me').set(authed(session.accessToken));
    expect(me.status).toBe(401);
    expect(me.body.error).toBe('session_revoked');
  });

  it('a suspension is rejected mid-session even before revocation lands', async () => {
    const session = await registered();
    await pglite.query(`UPDATE users SET status = 'suspended', suspended_at = now()`);

    const me = await request(app).get('/api/v1/auth/me').set(authed(session.accessToken));
    expect(me.status).toBe(403);
    expect(me.body.error).toBe('account_suspended');
  });
});

// ── Authentication of protected endpoints (§32, §39) ────────────────────

describe('GET /api/v1/auth/me', () => {
  it('rejects a request with no token', async () => {
    const res = await request(app).get('/api/v1/auth/me');
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('missing_token');
  });

  it('rejects a garbage token', async () => {
    const res = await request(app).get('/api/v1/auth/me').set(authed('not.a.jwt'));
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_token');
  });

  it('rejects a well-formed token that was signed with another secret', async () => {
    // A privilege-escalation attempt: the shape is right, the signature is not.
    const forged = [
      Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url'),
      Buffer.from(
        JSON.stringify({ sub: '00000000-0000-0000-0000-000000000000', sid: 'x' })
      ).toString('base64url'),
      'signature',
    ].join('.');

    const res = await request(app).get('/api/v1/auth/me').set(authed(forged));
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_token');
  });

  it('rejects a token whose session has been revoked', async () => {
    const session = await registered();
    await pglite.query('UPDATE user_sessions SET revoked_at = now()');

    const res = await request(app).get('/api/v1/auth/me').set(authed(session.accessToken));
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('session_revoked');
  });
});

// ── Privacy (§38) ───────────────────────────────────────────────────────

describe('privacy', () => {
  it('never puts the phone number or its hash in an auth response', async () => {
    const session = await registered();
    await requestOtp(PHONE, 'signin');
    const signin = await request(app)
      .post('/api/v1/auth/signin')
      .send({ idToken: idTokenFor(PHONE) });
    const me = await request(app).get('/api/v1/auth/me').set(authed(session.accessToken));
    const refresh = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: session.refreshToken });

    const bodies = [session, signin.body, me.body, refresh.body].map((b) => JSON.stringify(b));
    const phoneHash = hashPhone(PHONE);

    for (const body of bodies) {
      expect(body).not.toContain(PHONE);
      expect(body).not.toContain(phoneHash);
      expect(body).not.toMatch(/phone/i);
      expect(body).not.toMatch(/phone_hash/);
    }
  });

  it('does not echo submitted credentials back in a validation error', async () => {
    // The idToken is a credential; a 400 that reflects it hands it to whatever
    // logs the response.
    const res = await request(app)
      .post('/api/v1/auth/register')
      .send({ idToken: 'secret-token-value-abcdefghij', displayName: 'x', email: 'nope' });

    expect(res.status).toBe(400);
    expect(JSON.stringify(res.body)).not.toContain('secret-token-value-abcdefghij');
  });

  it('keeps the raw refresh token out of the database', async () => {
    const session = await registered();

    const { rows } = await pglite.query<{ refresh_token_hash: string }>(
      'SELECT refresh_token_hash FROM user_sessions'
    );
    expect(rows[0]?.refresh_token_hash).not.toBe(session.refreshToken);
    expect(rows[0]?.refresh_token_hash).toHaveLength(64);
  });
});
