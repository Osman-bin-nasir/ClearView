import { afterAll, describe, expect, it } from 'vitest';
import request from 'supertest';
import { buildApp } from '../src/app.js';
import { closePool } from '../src/db.js';

const app = buildApp();

// The app opens a connection pool on import; release it so vitest can exit.
afterAll(async () => {
  await closePool();
});

describe('GET /health', () => {
  it('reports the process as up', async () => {
    const res = await request(app).get('/health');

    expect(res.status).toBe(200);
    expect(res.body.ok).toBe(true);
    expect(res.body.service).toBe('clearview-goodpost');
    expect(typeof res.body.uptimeSeconds).toBe('number');
  });

  it('does not depend on the database', async () => {
    // The configured database is unreachable in this suite, so a 200 here is
    // proof that the liveness probe is genuinely independent of Neon. This
    // matters because Render restarts a service whose health check fails.
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
  });

  it('leaks no configuration', async () => {
    const res = await request(app).get('/health');
    const body = JSON.stringify(res.body);

    expect(body).not.toContain('127.0.0.1');
    expect(body).not.toMatch(/secret|pepper|password|token|key/i);
  });
});

describe('GET /health/db', () => {
  it('returns 503 (not 500) when the database is unreachable', async () => {
    const res = await request(app).get('/health/db');

    expect(res.status).toBe(503);
    expect(res.body).toEqual({ ok: false, db: 'down' });
  });
});

describe('request handling', () => {
  it('404s an unknown path as JSON, not HTML', async () => {
    const res = await request(app).get('/api/does-not-exist');

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'not_found' });
  });

  it('rejects an oversized JSON body with 413, never a 500', async () => {
    // Body-parser throws a 413 before routing, so the error handler has to
    // honour the status it carries instead of flattening everything to 500.
    const res = await request(app)
      .post('/health')
      .send({ pad: 'x'.repeat(300_000) });

    expect(res.status).toBe(413);
  });

  it('accepts a body under the limit', async () => {
    const res = await request(app)
      .post('/health')
      .send({ pad: 'x'.repeat(200_000) });

    // Reaches the router, which has no POST /health — a 404 here means the
    // parser accepted the payload rather than rejecting it.
    expect(res.status).toBe(404);
  });

  it('does not advertise the server technology', async () => {
    const res = await request(app).get('/health');
    expect(res.headers['x-powered-by']).toBeUndefined();
  });
});
