import type { NextFunction, Request, RequestHandler, Response } from 'express';
import type { Queryable } from '../db.js';
import { forbidden, unauthorized } from '../http/errors.js';
import { isSessionLive, loadOwnAccount, type OwnAccount } from './service.js';
import { verifyAccessToken } from './tokens.js';

/**
 * The authenticated identity of a request (§32).
 *
 * Attached by [requireAuth] and read only through [authOf], which refuses to
 * hand out a value that was never set. A handler therefore cannot accidentally
 * treat "no middleware ran" as "some user is signed in" — the failure is an
 * exception in development rather than a request attributed to nobody.
 *
 * Everything here comes from a signed token and the database. §32's checklist
 * ("is it authenticated, which account, is it active, does it own the
 * resource") is answered server-side; a client can never assert any of it.
 */
export interface AuthContext {
  readonly userId: string;
  readonly sessionId: string;
  readonly account: OwnAccount;
}

interface RequestWithAuth extends Request {
  auth?: AuthContext;
}

/** The verified identity of a request. Throws if [requireAuth] did not run. */
export function authOf(req: Request): AuthContext {
  const auth = (req as RequestWithAuth).auth;
  if (!auth) {
    throw new Error('[auth] authOf() called on a route without requireAuth');
  }
  return auth;
}

/**
 * Require a valid, live, active Good Post session.
 *
 * Two checks, deliberately both:
 *
 *  1. The signature, which is free and proves we minted the token.
 *  2. The session row and the account's current status, which costs one
 *     indexed lookup per request.
 *
 * The second is what makes revocation and bans immediate. §19 requires that
 * banning a user revokes their sessions; a signature-only check would keep
 * honouring a banned user's token until it expired — up to ACCESS_TOKEN_TTL of
 * continued access after a moderator acted.
 */
export function requireAuth(database: Queryable): RequestHandler {
  return async (req: Request, _res: Response, next: NextFunction) => {
    const header = req.header('authorization') ?? '';
    const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : '';

    if (!token) {
      next(unauthorized('missing_token', 'A bearer access token is required.'));
      return;
    }

    const claims = verifyAccessToken(token);
    if (!claims) {
      next(unauthorized('invalid_token', 'The access token is not valid.'));
      return;
    }

    if (!(await isSessionLive(database, claims.sub, claims.sid))) {
      next(unauthorized('session_revoked', 'This session is no longer valid. Sign in again.'));
      return;
    }

    const account = await loadOwnAccount(database, claims.sub);
    if (!account) {
      next(unauthorized('account_not_found', 'This account no longer exists.'));
      return;
    }

    // Status is re-read per request rather than trusted from the token, so a
    // suspension takes effect immediately even on an already-issued token.
    if (account.status === 'banned') {
      next(forbidden('account_banned', 'This account has been permanently banned.'));
      return;
    }
    if (account.status === 'suspended') {
      next(forbidden('account_suspended', 'This account is suspended.'));
      return;
    }

    (req as RequestWithAuth).auth = {
      userId: claims.sub,
      sessionId: claims.sid,
      account,
    };
    next();
  };
}
