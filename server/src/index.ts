import { buildApp } from './app.js';
import { closePool } from './db.js';
import { env } from './env.js';
import { describeActiveLimits, rateLimitConfigFromEnv } from './http/rateLimit.js';
import { startRetentionJob } from './jobs/retention.js';

// Report the enforced limits at boot. Deployment logs are the only place an
// operator can confirm a limit is real rather than merely configured, and
// stating the reserved-but-unenforced variables prevents the two from being
// confused.
for (const line of describeActiveLimits(rateLimitConfigFromEnv())) {
  console.log(line);
}

const server = buildApp().listen(env.PORT, () => {
  console.log(`[api] listening on :${env.PORT} (${env.NODE_ENV})`);
  console.log(`[api] post history window: ${env.GOODPOST_HISTORY_DAYS} days`);
});

if (env.RETENTION_JOB_ENABLED) {
  startRetentionJob();
}

/**
 * Graceful shutdown. Render sends SIGTERM and then waits before killing the
 * process; closing the HTTP server first lets in-flight requests finish, and
 * releasing the pool afterwards avoids leaving Neon connections to time out.
 */
let shuttingDown = false;
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`[api] ${signal} received, shutting down`);
    server.close(() => {
      void closePool().finally(() => process.exit(0));
    });
    // Do not hang forever on a stuck keep-alive connection.
    setTimeout(() => process.exit(0), 10_000).unref();
  });
}
