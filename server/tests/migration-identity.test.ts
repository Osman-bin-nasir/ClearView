import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { PGlite } from '@electric-sql/pglite';
import { applyAllMigrations, freshDatabase, insertUser, one } from './helpers/database.js';

/**
 * Migration 001, applied to a real PostgreSQL and then attacked.
 *
 * Every assertion here is a security invariant, not a schema-shape check:
 * these are the constraints that make §19 (mobile-number bans) and §38
 * (phone numbers stay private) hold under a hostile client. If one of them
 * silently disappears in a later migration, this suite fails.
 */

let db: PGlite;

beforeAll(async () => {
  db = await freshDatabase();
  await applyAllMigrations(db);
}, 120_000);

afterAll(async () => {
  await db.close();
});

describe('migration runner behaviour', () => {
  it('applies every migration file', async () => {
    const applied = await db.query<{ version: string }>(
      'SELECT version FROM schema_migrations ORDER BY version'
    );
    expect(applied.rows.length).toBeGreaterThan(0);
    expect(one(applied.rows).version).toMatch(/^\d+_.*\.sql$/);
  });

  it('is idempotent — a second run applies nothing new', async () => {
    const before = await db.query<{ count: number }>('SELECT count(*)::int AS count FROM schema_migrations');
    await applyAllMigrations(db);
    const after = await db.query<{ count: number }>('SELECT count(*)::int AS count FROM schema_migrations');

    expect(after.rows[0]?.count).toBe(before.rows[0]?.count);
  });
});

describe('phone identity is the unbypassable ban anchor (§19)', () => {
  it('never stores a plaintext phone number', async () => {
    const columns = await db.query<{ column_name: string }>(
      `SELECT column_name FROM information_schema.columns WHERE table_name = 'users'`
    );
    const names = columns.rows.map((c) => c.column_name);

    expect(names).toContain('phone_hash');
    // If a `phone` column ever appears here, the platform is holding phone
    // numbers in the clear and §38 is broken.
    expect(names).not.toContain('phone');
    expect(names).not.toContain('phone_number');
    expect(names).not.toContain('mobile');
  });

  it('rejects a second account with the same phone hash', async () => {
    await insertUser(db, { phone_hash: 'shared-hash' });

    await expect(
      insertUser(db, { phone_hash: 'shared-hash' })
    ).rejects.toThrow(/duplicate key value/i);
  });

  it('still rejects the phone hash after the first account is soft-deleted', async () => {
    // This is the bypass that matters: delete the account, register again with
    // the same SIM. Releasing the hash on self-deletion would hand a banned
    // user a one-tap escape, so the constraint is deliberately not partial on
    // `deleted_at IS NULL`.
    const id = await insertUser(db, { phone_hash: 'reused-after-delete' });
    await db.query('UPDATE users SET deleted_at = now() WHERE id = $1', [id]);

    await expect(
      insertUser(db, { phone_hash: 'reused-after-delete' })
    ).rejects.toThrow(/duplicate key value/i);
  });
});

describe('email rules', () => {
  it('rejects two live accounts on one email', async () => {
    const email = 'dup@example.test';
    await insertUser(db, { email, email_normalized: email });

    await expect(
      insertUser(db, { email, email_normalized: email })
    ).rejects.toThrow(/duplicate key value/i);
  });

  it('allows the email to be reused after the account is deleted', async () => {
    // Unlike the phone hash, an email is not an abuse identity — holding it
    // forever would block a legitimate new owner of the same address.
    const email = 'reusable@example.test';
    const id = await insertUser(db, { email, email_normalized: email });
    await db.query('UPDATE users SET deleted_at = now() WHERE id = $1', [id]);

    const second = await insertUser(db, { email, email_normalized: email });
    expect(second).toBeTruthy();
  });
});

describe('banned identities (§19)', () => {
  it('requires a phone hash on every ban', async () => {
    // A ban anchored to nothing else is not enforceable: email and display
    // name are both trivially changed by the user being banned.
    await expect(
      db.query(`INSERT INTO banned_identities (reason) VALUES ('spam')`)
    ).rejects.toThrow(/null value in column "phone_hash"/i);
  });

  it('allows at most one active ban per phone hash', async () => {
    await db.query(
      `INSERT INTO banned_identities (phone_hash, reason) VALUES ('ban-target', 'spam')`
    );

    await expect(
      db.query(
        `INSERT INTO banned_identities (phone_hash, reason) VALUES ('ban-target', 'spam again')`
      )
    ).rejects.toThrow(/duplicate key value/i);
  });

  it('permits a new ban after the previous one is lifted, keeping the history', async () => {
    await db.query(
      `INSERT INTO banned_identities (phone_hash, reason) VALUES ('ban-lifted', 'first')`
    );
    await db.query(
      `UPDATE banned_identities SET lifted_at = now() WHERE phone_hash = 'ban-lifted'`
    );
    await db.query(
      `INSERT INTO banned_identities (phone_hash, reason) VALUES ('ban-lifted', 'second')`
    );

    const rows = await db.query<{ count: number }>(
      `SELECT count(*)::int AS count FROM banned_identities WHERE phone_hash = 'ban-lifted'`
    );
    // Both rows survive: lifting a ban must not erase the moderation record.
    expect(rows.rows[0]?.count).toBe(2);
  });
});

describe('account status and input validation', () => {
  it('rejects an unknown account status', async () => {
    await expect(
      insertUser(db, { status: 'shadowbanned' })
    ).rejects.toThrow(/invalid input value for enum/i);
  });

  it('rejects a whitespace-only display name', async () => {
    await expect(
      insertUser(db, { display_name: '   ' })
    ).rejects.toThrow(/violates check constraint/i);
  });

  it('rejects an over-long display name', async () => {
    await expect(
      insertUser(db, { display_name: 'x'.repeat(61) })
    ).rejects.toThrow(/violates check constraint/i);
  });
});

describe('session hygiene', () => {
  it('deletes a user’s sessions when the account row is hard-deleted', async () => {
    const id = await insertUser(db);
    await db.query(
      `INSERT INTO user_sessions (user_id, refresh_token_hash, expires_at)
       VALUES ($1, $2, now() + interval '30 days')`,
      [id, 'token-hash-1']
    );

    await db.query('DELETE FROM users WHERE id = $1', [id]);

    const left = await db.query<{ count: number }>(
      'SELECT count(*)::int AS count FROM user_sessions WHERE user_id = $1',
      [id]
    );
    expect(left.rows[0]?.count).toBe(0);
  });

  it('refuses two sessions holding the same refresh token hash', async () => {
    const a = await insertUser(db);
    const b = await insertUser(db);
    await db.query(
      `INSERT INTO user_sessions (user_id, refresh_token_hash, expires_at)
       VALUES ($1, 'collide', now() + interval '1 day')`,
      [a]
    );

    await expect(
      db.query(
        `INSERT INTO user_sessions (user_id, refresh_token_hash, expires_at)
         VALUES ($1, 'collide', now() + interval '1 day')`,
        [b]
      )
    ).rejects.toThrow(/duplicate key value/i);
  });
});

describe('admin separation (§48)', () => {
  it('has no admin flag anywhere on users', async () => {
    const columns = await db.query<{ column_name: string }>(
      `SELECT column_name FROM information_schema.columns WHERE table_name = 'users'`
    );
    const names = columns.rows.map((c) => c.column_name);

    // A normal Good Post registration flow must never be able to produce a
    // platform administrator. The strongest way to guarantee that is for the
    // users table to have no admin-shaped field at all.
    expect(names).not.toContain('is_admin');
    expect(names).not.toContain('role');
    expect(names).not.toContain('permissions');
  });
});

describe('updated_at maintenance', () => {
  it('bumps updated_at on update', async () => {
    const id = await insertUser(db);
    const before = one(
      await db.query<{ updated_at: Date }>('SELECT updated_at FROM users WHERE id = $1', [id]).then(
        (r) => r.rows
      )
    ).updated_at;

    await db.query('UPDATE users SET display_name = $2 WHERE id = $1', [id, 'renamed']);

    const rows = await db.query<{ updated_at: Date }>('SELECT updated_at FROM users WHERE id = $1', [id]);
    expect(rows.rows[0]?.updated_at.getTime()).toBeGreaterThan(before.getTime());
  });
});
