#!/usr/bin/env node
/**
 * Put Firebase Admin credentials into `server/.env` in the shape the app reads.
 *
 * The app expects three separate variables (see src/env.ts and render.yaml):
 *
 *   FIREBASE_PROJECT_ID
 *   FIREBASE_CLIENT_EMAIL
 *   FIREBASE_PRIVATE_KEY
 *
 * Firebase hands you ONE json file. Moving its fields by hand is where keys get
 * truncated, pasted with the surrounding JSON, or lose their newlines — so this
 * script does the conversion, and never prints a secret.
 *
 * Usage
 *   node scripts/set-firebase-env.mjs path/to/service-account.json
 *   node scripts/set-firebase-env.mjs --salvage   # whole JSON pasted into .env
 *   node scripts/set-firebase-env.mjs --repair    # re-normalise an .env in place
 *
 * The problem this tool exists for
 * --------------------------------
 * A PEM private key is multi-line, but `.env` is line-oriented. So the value
 * MUST be stored with escaped `\n` sequences on a single line, which src/env.ts
 * turns back into real newlines when it loads them.
 *
 * There are two ways to get that wrong, and this script handles both:
 *
 *  - `JSON.parse` converts a service account's `"private_key": "...\n..."` into
 *    a string containing REAL newlines. Writing that into `.env` verbatim
 *    breaks one line into ~27, and dotenv then reads a mangled key.
 *  - Pasting the raw JSON into `.env` at all, which is how this came up.
 *
 * Everything is verified before it is reported as done: the three values are
 * read back out of the written file and compared with what went in.
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const KEYS = ['FIREBASE_PROJECT_ID', 'FIREBASE_CLIENT_EMAIL', 'FIREBASE_PRIVATE_KEY'];

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const serverDir = path.resolve(scriptDir, '..');
const envPath = path.join(serverDir, '.env');

function fail(message) {
  console.error(`[set-firebase-env] ${message}`);
  process.exit(1);
}

const eolOf = (text) => (/\r\n/.test(text) ? '\r\n' : '\n');

/** Real newlines -> the two-character escape `.env` can hold on one line. */
const escapeValue = (value) => value.replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/\n/g, '\\n');

/** The inverse, so a value read back can be compared with the intended one. */
const unescapeValue = (value) => value.replace(/\\n/g, '\n').replace(/\\r/g, '\r');

/**
 * Parse `.env` into ordered entries, unfolding single-quoted values that span
 * several physical lines. Unfolding is what makes this idempotent: a file
 * broken by a previous run is repaired by reading and rewriting it.
 */
function readEntries(text) {
  const lines = text.split(/\r?\n/);
  const entries = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const match = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);

    if (!match) {
      entries.push({ kind: 'raw', line });
      i += 1;
      continue;
    }

    const key = match[1];
    const rest = match[2];

    if (rest.startsWith("'") && !(rest.length >= 2 && rest.endsWith("'"))) {
      // Unterminated on this line: keep consuming until one closes the quote.
      const parts = [rest.slice(1)];
      let j = i + 1;
      let closed = false;
      while (j < lines.length) {
        const candidate = lines[j];
        if (candidate.endsWith("'")) {
          parts.push(candidate.slice(0, -1));
          closed = true;
          break;
        }
        parts.push(candidate);
        j += 1;
      }
      if (!closed) fail(`unterminated quoted value for ${key}; refusing to guess.`);
      // Physical newlines inside a stored value mean real newlines, which is
      // exactly what a broken write produced — rejoin them. The unescape is a
      // no-op for a PEM (base64 contains no backslashes) but keeps this branch
      // consistent with the single-line one.
      entries.push({ kind: 'pair', key, value: unescapeValue(parts.join('\n')) });
      i = j + 1;
      continue;
    }

    // Unescape so a value read from disk equals the logical value that was
    // written — otherwise the round-trip check compares the escaped form
    // against the real-newline form and reports a mismatch that is not real.
    const value =
      rest.startsWith("'") && rest.endsWith("'") && rest.length >= 2
        ? unescapeValue(rest.slice(1, -1))
        : rest;

    entries.push({ kind: 'pair', key, value });
    i += 1;
  }

  return entries;
}

function serialise(entries, eol) {
  const lines = entries.map((entry) =>
    entry.kind === 'raw' ? entry.line : `${entry.key}='${escapeValue(entry.value)}'`
  );
  return lines.join(eol).replace(/\s+$/, '') + eol;
}

/** Set or append a key, preserving the order of everything else. */
function setValue(entries, key, value) {
  const index = entries.findIndex((e) => e.kind === 'pair' && e.key === key);
  if (index === -1) entries.push({ kind: 'pair', key, value });
  else entries[index] = { kind: 'pair', key, value };
}

function validate(json) {
  if (!json || typeof json !== 'object') fail('the JSON is not an object.');
  if (json.type !== 'service_account') {
    fail(`expected "type": "service_account", found ${JSON.stringify(json.type)}.`);
  }
  for (const field of ['project_id', 'client_email', 'private_key']) {
    if (typeof json[field] !== 'string' || json[field].length === 0) {
      fail(`the JSON has no usable "${field}".`);
    }
  }
  const pem = json.private_key;
  if (!/-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(pem) || !/-----END [A-Z ]*PRIVATE KEY-----/.test(pem)) {
    fail('"private_key" is not a PEM block — it may have lost its newlines.');
  }
  return json;
}

function parseServiceAccount(blob) {
  let json;
  try {
    json = JSON.parse(blob);
  } catch (e) {
    fail(`service-account JSON is not parseable: ${e.message}`);
  }
  return validate(json);
}

function salvage(envText) {
  const lines = envText.split(/\r?\n/);

  let start = -1;
  for (let i = 0; i < lines.length - 1; i += 1) {
    if (lines[i].trim() === '{' && /"type"\s*:\s*"service_account"/.test(lines[i + 1])) {
      start = i;
      break;
    }
  }
  if (start === -1) fail('no pasted service-account blob found in .env.');

  let end = -1;
  for (let i = start; i < lines.length; i += 1) {
    if (lines[i].trim() === '}') {
      end = i;
      break;
    }
  }
  if (end === -1) fail('the pasted blob has no closing brace; refusing to guess.');

  const json = parseServiceAccount(lines.slice(start, end + 1).join('\n'));
  console.log(`  salvaged pasted blob: lines ${start + 1}-${end + 1} (${end - start + 1} lines)`);

  return { json, remainingLines: lines.slice(0, start).concat(lines.slice(end + 1)) };
}

/** Read back what we just wrote and confirm it matches, value for value. */
function verify(entries, expected) {
  const byKey = new Map(entries.filter((e) => e.kind === 'pair').map((e) => [e.key, e.value]));
  const problems = [];

  for (const key of KEYS) {
    const actual = byKey.get(key);
    if (actual === undefined) problems.push(`${key} missing`);
    else if (actual !== expected[key]) {
      problems.push(`${key} does not match (${actual.length} vs ${expected[key].length} chars)`);
    }
  }

  return problems;
}

function main() {
  if (!existsSync(envPath)) {
    fail(`no .env at ${envPath} — copy server/.env.example to server/.env first.`);
  }

  const args = process.argv.slice(2);
  const envText = readFileSync(envPath, 'utf8');
  const eol = eolOf(envText);

  let entries = readEntries(envText);
  let json = null;

  if (args.includes('--salvage')) {
    const result = salvage(envText);
    json = result.json;
    entries = readEntries(result.remainingLines.join(eol));
  } else if (args.includes('--repair')) {
    // Nothing to import; just re-normalise whatever is already there.
  } else {
    const file = args[0];
    if (!file) {
      fail('usage: node scripts/set-firebase-env.mjs <service-account.json> | --salvage | --repair');
    }
    const resolved = path.resolve(file);
    if (!existsSync(resolved)) fail(`no such file: ${resolved}`);
    json = parseServiceAccount(readFileSync(resolved, 'utf8'));
  }

  if (json) {
    setValue(entries, 'FIREBASE_PROJECT_ID', json.project_id);
    setValue(entries, 'FIREBASE_CLIENT_EMAIL', json.client_email);
    setValue(entries, 'FIREBASE_PRIVATE_KEY', json.private_key);
  }

  const byKey = new Map(entries.filter((e) => e.kind === 'pair').map((e) => [e.key, e.value]));
  const expected = {
    FIREBASE_PROJECT_ID: byKey.get('FIREBASE_PROJECT_ID'),
    FIREBASE_CLIENT_EMAIL: byKey.get('FIREBASE_CLIENT_EMAIL'),
    FIREBASE_PRIVATE_KEY: byKey.get('FIREBASE_PRIVATE_KEY'),
  };

  for (const key of KEYS) {
    if (!expected[key]) fail(`${key} is empty in .env — nothing to write. Use --salvage or pass a JSON file.`);
  }

  writeFileSync(envPath, serialise(entries, eol), 'utf8');

  // Re-read from disk: proves the escaping round-trips through the file.
  const written = readEntries(readFileSync(envPath, 'utf8'));
  const problems = verify(written, expected);

  const pem = expected.FIREBASE_PRIVATE_KEY;
  console.log('[set-firebase-env] server/.env (gitignored)');
  console.log(`  FIREBASE_PROJECT_ID    ${expected.FIREBASE_PROJECT_ID.length} chars`);
  console.log(`  FIREBASE_CLIENT_EMAIL  ${expected.FIREBASE_CLIENT_EMAIL.length} chars`);
  console.log(`                         service-account address: ${/\.iam\.gserviceaccount\.com$/.test(expected.FIREBASE_CLIENT_EMAIL)}`);
  console.log(`  FIREBASE_PRIVATE_KEY   ${pem.length} chars`);
  console.log(`                         PEM markers: ${/-----BEGIN/.test(pem)}/${/-----END/.test(pem)}`);
  console.log(`                         newlines: ${(pem.match(/\n/g) || []).length}`);
  console.log(`  round-trip read-back:  ${problems.length === 0 ? 'PASS' : 'FAIL — ' + problems.join('; ')}`);
  console.log(`  raw JSON lines left:   ${written.filter((e) => e.kind === 'raw' && /^\s*"/.test(e.line)).length}`);
  console.log('  No secret value was printed.');

  if (problems.length > 0) process.exit(1);
}

main();
