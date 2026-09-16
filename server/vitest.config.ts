import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    // Configuration is injected here rather than read from a .env file so the
    // suite is hermetic: it never depends on, and never touches, a developer's
    // real database. dotenv does not override variables that already exist, so
    // these win over anything in server/.env.
    env: {
      NODE_ENV: 'test',
      // Port 1 refuses instantly, so the /health/db failure path is asserted
      // without waiting out a connection timeout.
      DATABASE_URL: 'postgresql://postgres:postgres@127.0.0.1:1/clearview_test',
      JWT_SECRET: 'test-access-secret-padded-to-32-chars-min',
      JWT_REFRESH_SECRET: 'test-refresh-secret-padded-to-32-chars-min',
      PHONE_HASH_PEPPER: 'test-pepper-padded-to-32-characters-min',
      RETENTION_JOB_ENABLED: 'false',
      FCM_ENABLED: 'false',
      RATE_LIMIT_MAX: '100000',
      AUTH_RATE_LIMIT_MAX: '100000',
    },
  },
});
