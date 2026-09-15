import { hmacSha256Hex } from "./canonical";
import type { AppConfig, Logger } from "./config";

/** One interaction, shaped exactly like an entry of GET /api/slates/{id}/responses. */
export interface WebhookInteraction {
  id: number;
  slateId: string;
  kind: string;
  elementId: string | null;
  questionId: string | null;
  optionId: string | null;
  optionLabel: string | null;
  itemId: string | null;
  value: string | null;
  clientAt: string | null;
  stale: boolean;
  createdAt: string;
}

export interface WebhookDelivery {
  /** Queue a fire-and-forget delivery. Never throws, never blocks the request. */
  enqueue: (slateId: string, interactions: WebhookInteraction[]) => void;
  /** Resolves when no deliveries are in flight (used by tests and graceful shutdown). */
  idle: () => Promise<void>;
  pendingCount: () => number;
  stop: () => Promise<void>;
}

const RETRY_HINT =
  "Webhook delivery is best-effort; polling GET /api/slates/{id}/responses remains the source of truth.";

export function createWebhookDelivery(config: AppConfig, log: Logger): WebhookDelivery {
  const inflight = new Set<Promise<void>>();
  let stopped = false;

  function sleep(ms: number): Promise<void> {
    return new Promise((resolvePromise) => {
      const timer = setTimeout(resolvePromise, ms);
      // Don't keep the process alive for a retry.
      (timer as unknown as { unref?: () => void }).unref?.();
    });
  }

  async function deliverOnce(url: string, body: string, headers: Record<string, string>): Promise<boolean> {
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "content-type": "application/json", ...headers },
        body,
      });
      if (response.ok) return true;
      log("warn", `webhook target responded ${response.status}`, { url });
      return false;
    } catch (error) {
      log("warn", `webhook fetch failed: ${(error as Error).message}`, { url });
      return false;
    }
  }

  async function deliver(slateId: string, interactions: WebhookInteraction[]): Promise<void> {
    const url = config.webhookUrl;
    if (!url || stopped || interactions.length === 0) return;

    const body = JSON.stringify({ type: "interactions", slateId, interactions });
    const headers: Record<string, string> = {};
    if (config.webhookSecret) {
      headers["x-slate-signature"] = `sha256=${hmacSha256Hex(config.webhookSecret, body)}`;
    }

    const attempts = 1 + config.webhookRetryDelaysMs.length;
    for (let attempt = 0; attempt < attempts; attempt++) {
      if (attempt > 0) {
        await sleep(config.webhookRetryDelaysMs[attempt - 1] ?? 0);
      }
      if (stopped) return;
      if (await deliverOnce(url, body, headers)) {
        log("info", `webhook delivered (${interactions.length} interaction${interactions.length === 1 ? "" : "s"})`, {
          slateId,
          attempt: attempt + 1,
        });
        return;
      }
    }
    log("warn", `webhook delivery abandoned after ${attempts} attempt(s). ${RETRY_HINT}`, { slateId });
  }

  function enqueue(slateId: string, interactions: WebhookInteraction[]): void {
    if (!config.webhookUrl || stopped || interactions.length === 0) return;
    const task = deliver(slateId, interactions)
      .catch((error) => log("error", `webhook task crashed: ${(error as Error).message}`))
      .finally(() => inflight.delete(task));
    inflight.add(task);
  }

  async function idle(): Promise<void> {
    while (inflight.size > 0) {
      await Promise.all([...inflight]);
    }
  }

  async function stop(): Promise<void> {
    stopped = true;
    await idle();
  }

  return { enqueue, idle, pendingCount: () => inflight.size, stop };
}
