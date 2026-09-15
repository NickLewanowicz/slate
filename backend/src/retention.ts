import type { Database } from "bun:sqlite";
import type { AppConfig, Logger } from "./config";

/**
 * Retention: keep at most `maxResponsesPerSlate` interactions per slate
 * (newest win) and drop anything older than `responseTtlHours`.
 * Enforced on insert and by a 10-minute sweep.
 */
export function enforceRetentionForSlate(db: Database, slateId: string, config: Pick<AppConfig, "maxResponsesPerSlate" | "responseTtlHours">): void {
  if (config.responseTtlHours >= 0) {
    const cutoff = new Date(Date.now() - config.responseTtlHours * 3_600_000).toISOString();
    db.run("DELETE FROM interactions WHERE slate_id = ? AND created_at < ?", [slateId, cutoff]);
  }
  if (config.maxResponsesPerSlate > 0) {
    db.run(
      `DELETE FROM interactions WHERE slate_id = ? AND id NOT IN (
         SELECT id FROM interactions WHERE slate_id = ? ORDER BY id DESC LIMIT ?
       )`,
      [slateId, slateId, config.maxResponsesPerSlate],
    );
  }
}

/** Sweep every slate + the TTL globally. Returns number of rows removed. */
export function sweepAll(db: Database, config: Pick<AppConfig, "maxResponsesPerSlate" | "responseTtlHours">): number {
  const before = countInteractions(db);
  if (config.responseTtlHours >= 0) {
    const cutoff = new Date(Date.now() - config.responseTtlHours * 3_600_000).toISOString();
    db.run("DELETE FROM interactions WHERE created_at < ?", [cutoff]);
  }
  const slateIds = db
    .query<{ slate_id: string }, []>("SELECT slate_id FROM slates")
    .all()
    .map((r) => r.slate_id);
  for (const slateId of slateIds) {
    if (config.maxResponsesPerSlate > 0) {
      db.run(
        `DELETE FROM interactions WHERE slate_id = ? AND id NOT IN (
           SELECT id FROM interactions WHERE slate_id = ? ORDER BY id DESC LIMIT ?
         )`,
        [slateId, slateId, config.maxResponsesPerSlate],
      );
    }
  }
  return before - countInteractions(db);
}

function countInteractions(db: Database): number {
  return db.query<{ n: number }, []>("SELECT COUNT(*) AS n FROM interactions").get()?.n ?? 0;
}

export interface Sweeper {
  stop: () => void;
}

const SWEEP_INTERVAL_MS = 10 * 60 * 1000;

/** Starts the periodic retention sweep unless disabled (SLATE_SWEEP_DISABLED=1). */
export function createSweeper(db: Database, config: AppConfig, log: Logger, intervalMs: number = SWEEP_INTERVAL_MS): Sweeper {
  if (config.sweepDisabled) {
    return { stop: () => undefined };
  }
  const tick = () => {
    try {
      const removed = sweepAll(db, config);
      if (removed > 0) log("info", `retention sweep removed ${removed} interaction(s)`);
    } catch (error) {
      log("warn", `retention sweep failed: ${(error as Error).message}`);
    }
  };
  const timer = setInterval(tick, intervalMs);
  (timer as unknown as { unref?: () => void }).unref?.();
  // One sweep shortly after boot to clean up anything left behind.
  const boot = setTimeout(tick, Math.min(intervalMs, 30_000));
  (boot as unknown as { unref?: () => void }).unref?.();
  return {
    stop: () => {
      clearInterval(timer);
      clearTimeout(boot);
    },
  };
}
