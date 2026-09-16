import cron from 'node-cron';
import { env } from '../env.js';

/**
 * The post-history retention sweep (§11).
 *
 * It is deliberately a no-op stub in M0: the tables it will prune (posts,
 * post_media) arrive in migration 003, and the runner must not reference
 * columns that do not exist yet. The schedule, the configurable window and
 * the shutdown behaviour are wired now so the real sweep drops straight in.
 *
 * Two rules it must honour when implemented:
 *
 *  1. It prunes SERVER-SIDE data only. Media a user has already downloaded
 *     lives on their device and is never touched (§10) — the server cannot
 *     and must not reach into it.
 *  2. It never deletes an S3 object that another row still references (§34),
 *     which is why deletion is driven by a reference count and not by a
 *     post-row delete.
 */
export function startRetentionJob(): void {
  const task = cron.schedule(env.RETENTION_CRON, () => {
    try {
      runRetentionSweep();
    } catch (err) {
      // A failed sweep must never take the process down; the next tick retries.
      console.error('[retention] sweep failed:', (err as Error).message);
    }
  });

  console.log(
    `[retention] scheduled "${env.RETENTION_CRON}" ` +
      `(history window ${env.GOODPOST_HISTORY_DAYS}d, grace ${env.PURGE_GRACE_DAYS}d)`
  );

  // Cron tasks hold the event loop open; unref so a SIGTERM shutdown is not
  // blocked by a pending tick.
  (task as unknown as { unref?: () => void }).unref?.();
}

function runRetentionSweep(): void {
  // Implemented in M7, once posts/post_media exist (migration 003).
}
