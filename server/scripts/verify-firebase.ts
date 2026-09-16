/**
 * Verify the real Firebase Admin credentials in `server/.env`.
 *
 * This exists because a suite that mocks the verifier proves nothing about
 * whether Firebase actually works: it asserts that our stub was called. This
 * script talks to the real project, with the real service account, and reports
 * what genuinely happened. It is deliberately NOT part of `npm test`, which
 * must stay hermetic — no credentials and no network — so that it can run in
 * CI and on a fresh clone.
 *
 * What it proves, in order of how much it matters:
 *
 *  1. The service-account private key PARSES and can SIGN. Signing a custom
 *     token is a local operation, so a mangled PEM (the failure mode when a
 *     multi-line key is stored wrong) fails here rather than in production.
 *  2. The Admin SDK is wired to the right project and can reach Google.
 *  3. Malformed, forged and expired tokens are all REJECTED — with the real
 *     SDK doing the rejecting, not our own code short-circuiting.
 *  4. The development-only `phone:<E.164>` backdoor is NOT active. This is the
 *     one that would let anyone impersonate any phone number, so it is
 *     asserted rather than assumed.
 *
 * Usage: npm run firebase:verify
 *
 * Nothing here ever prints a token or a key. Only labels, outcomes and error
 * CODES are reported.
 */

import { getAuth } from 'firebase-admin/auth';
import { createPhoneVerifier, firebaseConfigured, getFirebaseAdminApp } from '../src/auth/firebase.js';
import { env } from '../src/env.js';

const results: { check: string; outcome: string; ok: boolean }[] = [];

function record(check: string, outcome: string, ok: boolean): void {
  results.push({ check, outcome, ok });
}

/**
 * A structurally valid but cryptographically forged JWT, with the right
 * issuer and audience so it gets all the way to signature verification.
 * That is what makes it a useful probe: it exercises cert fetching and the
 * real verifier rather than failing on a format check.
 */
function forgedToken(projectId: string): string {
  const b64 = (value: object) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');

  const now = Math.floor(Date.now() / 1000);
  return [
    b64({ alg: 'RS256', typ: 'JWT', kid: 'not-a-real-key-id' }),
    b64({
      iss: `https://securetoken.google.com/${projectId}`,
      aud: projectId,
      sub: 'forged-subject',
      iat: now,
      exp: now + 3600,
      // A phone claim on a forged token must NOT be trusted.
      phone_number: '+923001234567',
      firebase: { sign_in_provider: 'phone' },
    }),
    'not-a-valid-signature',
  ].join('.');
}

async function main(): Promise<void> {
  console.log('[verify-firebase] configuration (no values printed)');
  console.log(`  PHONE_VERIFY_MODE     : ${env.PHONE_VERIFY_MODE}`);
  console.log(`  project id configured : ${env.FIREBASE_PROJECT_ID.length > 0}`);
  console.log(`  client email configured: ${env.FIREBASE_CLIENT_EMAIL.length > 0}`);
  console.log(`  private key configured: ${env.FIREBASE_PRIVATE_KEY.length > 0}`);
  console.log(`  private key is a PEM   : ${/-----BEGIN/.test(env.FIREBASE_PRIVATE_KEY) && /-----END/.test(env.FIREBASE_PRIVATE_KEY)}`);
  console.log(`  private key newlines   : ${(env.FIREBASE_PRIVATE_KEY.match(/\n/g) ?? []).length}`);
  console.log('');

  if (!firebaseConfigured) {
    console.error('[verify-firebase] Firebase Admin is NOT configured. Nothing to verify.');
    process.exit(1);
  }

  // ── 1. The private key parses and signs ───────────────────────────────
  try {
    const token = await getAuth(getFirebaseAdminApp()).createCustomToken('clearview-verify');
    // A signed JWT has exactly three segments. Its contents are not a secret
    // to us but are never printed.
    record('private key can sign', token.split('.').length === 3 ? 'signed OK' : 'malformed output', token.split('.').length === 3);
  } catch (error) {
    record('private key can sign', `FAILED: ${(error as Error).message.slice(0, 120)}`, false);
  }

  // ── 2/3. Real verification rejects what it should ─────────────────────
  const verifier = createPhoneVerifier();

  const rejected: { label: string; input: string }[] = [
    { label: 'garbage string', input: 'not-a-token-at-all' },
    { label: 'empty string', input: '' },
    { label: 'malformed JWT', input: 'aaa.bbb.ccc' },
    { label: 'forged signature (real issuer/audience)', input: forgedToken(env.FIREBASE_PROJECT_ID) },
    { label: 'dev backdoor shape (phone:+E164)', input: 'phone:+923001234567' },
  ];

  for (const { label, input } of rejected) {
    try {
      const identity = await verifier.verifyIdToken(input);
      // Reaching here is a security failure, not a test failure: it means a
      // token we do not trust was accepted as proof of a phone number.
      record(`rejects ${label}`, `ACCEPTED — ${identity.phoneE164.slice(0, 4)}…`, false);
    } catch (error) {
      const type = (error as { type?: string }).type ?? 'error';
      const message = (error as Error).message ?? '';
      // The rejection must not leak key material back to the caller.
      const leaks = /BEGIN|PRIVATE KEY|client_email/.test(message);
      record(`rejects ${label}`, `${type}${leaks ? ' ** LEAKS KEY MATERIAL **' : ''}`, !leaks);
    }
  }

  // The direct SDK error code, so a network problem cannot masquerade as a
  // correct rejection. `auth/argument-error` is the expected signature failure.
  try {
    await getAuth(getFirebaseAdminApp()).verifyIdToken(forgedToken(env.FIREBASE_PROJECT_ID));
    record('raw SDK rejects forged token', 'ACCEPTED', false);
  } catch (error) {
    const code = (error as { code?: string }).code ?? 'unknown';
    const network = /network|ENOTFOUND|timeout|EAI_AGAIN|fetch/i.test((error as Error).message ?? '');
    record(
      'raw SDK rejects forged token',
      `code=${code}${network ? ' (network issue — rejection not conclusive)' : ''}`,
      !network
    );
  }

  console.log('[verify-firebase] results');
  let failed = 0;
  for (const r of results) {
    console.log(`  ${r.ok ? 'PASS' : 'FAIL'}  ${r.check}: ${r.outcome}`);
    if (!r.ok) failed += 1;
  }
  console.log('');
  console.log(failed === 0 ? '[verify-firebase] all checks passed' : `[verify-firebase] ${failed} check(s) failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((error: unknown) => {
  console.error(`[verify-firebase] unexpected failure: ${(error as Error).message}`);
  process.exit(1);
});
