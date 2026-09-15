import type { Database } from "bun:sqlite";
import { canonicalJson, sha256Hex } from "./canonical";
import type { WebhookInteraction } from "./webhook";
import { isPlainObject, nowIso } from "./http";

export const MAX_SPEC_BYTES = 64 * 1024;

export interface SlateRow {
  slate_id: string;
  spec_json: string;
  content_hash: string;
  tone: string;
  updated_at: string;
}

export interface SlateSummary {
  slateId: string;
  tone: string;
  updatedAt: string;
  contentHash: string;
}

export interface PutResult {
  created: boolean;
  slateId: string;
  updatedAt: string;
  contentHash: string;
}

export function specSizeBytes(body: unknown): number {
  return Buffer.byteLength(JSON.stringify(body) ?? "", "utf8");
}

/** The content hash covers the agent-authored content; updatedAt is server-owned metadata. */
export function contentHashOf(envelope: Record<string, unknown>): string {
  const withoutUpdatedAt: Record<string, unknown> = { ...envelope };
  delete withoutUpdatedAt["updatedAt"];
  return sha256Hex(canonicalJson(withoutUpdatedAt));
}

export function toneOf(envelope: Record<string, unknown>): string {
  const tone = envelope["tone"];
  return typeof tone === "string" ? tone : "neutral";
}

export function getSlate(db: Database, slateId: string): SlateRow | null {
  return db.query<SlateRow, [string]>("SELECT slate_id, spec_json, content_hash, tone, updated_at FROM slates WHERE slate_id = ?").get(slateId) ?? null;
}

export function listSlates(db: Database): SlateSummary[] {
  return db
    .query<{ slate_id: string; tone: string; updated_at: string; content_hash: string }, []>(
      "SELECT slate_id, tone, updated_at, content_hash FROM slates ORDER BY slate_id",
    )
    .all()
    .map((r) => ({ slateId: r.slate_id, tone: r.tone, updatedAt: r.updated_at, contentHash: r.content_hash }));
}

/** Full-replace upsert. Caller must have validated the envelope against the slate schema. */
export function putSlate(db: Database, slateId: string, envelope: Record<string, unknown>): PutResult {
  const now = nowIso();
  const stored = { ...envelope };
  stored["updatedAt"] = now; // server ALWAYS owns updatedAt (agents must not send it)
  const contentHash = contentHashOf(stored);
  const specJson = canonicalJson(stored);
  const tone = toneOf(stored);

  const existed = getSlate(db, slateId) !== null;
  db.run(
    `INSERT INTO slates (slate_id, spec_json, content_hash, tone, updated_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(slate_id) DO UPDATE SET spec_json = excluded.spec_json, content_hash = excluded.content_hash, tone = excluded.tone, updated_at = excluded.updated_at`,
    [slateId, specJson, contentHash, tone, now],
  );
  return { created: !existed, slateId, updatedAt: stored["updatedAt"] as string, contentHash };
}

export function deleteSlate(db: Database, slateId: string): boolean {
  return db.run("DELETE FROM slates WHERE slate_id = ?", [slateId]).changes > 0;
}

/** Returns true if a todo item was found and mutated (spec is mutated in place). */
export function applyTodoCheck(spec: unknown, listId: string, itemId: string, checked: boolean): boolean {
  if (!isPlainObject(spec)) return false;
  const children = spec["children"];
  if (Array.isArray(children)) {
    for (const child of children) {
      if (applyTodoCheck(child, listId, itemId, checked)) return true;
    }
  }
  if (spec["type"] === "todoList" && spec["id"] === listId && Array.isArray(spec["items"])) {
    for (const item of spec["items"]) {
      if (isPlainObject(item) && item["id"] === itemId) {
        item["checked"] = checked;
        return true;
      }
    }
  }
  return false;
}

/** Persist a spec mutation (check/uncheck) — refreshes spec_json + content_hash, leaves updated_at (agent push time). */
export function storeMutatedSpec(db: Database, slateId: string, spec: Record<string, unknown>): string {
  const contentHash = contentHashOf(spec);
  db.run("UPDATE slates SET spec_json = ?, content_hash = ? WHERE slate_id = ?", [canonicalJson(spec), contentHash, slateId]);
  return contentHash;
}

export interface InteractionRecord {
  seq: string;
  kind: string;
  elementId?: string;
  questionId?: string;
  optionId?: string;
  optionLabel?: string;
  itemId?: string;
  value?: string;
  clientAt?: string;
}

const INSERT_INTERACTION = `INSERT OR IGNORE INTO interactions
  (slate_id, kind, element_id, question_id, option_id, option_label, item_id, value, client_at, stale, seq, payload_json, created_at)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

export function insertInteraction(
  db: Database,
  slateId: string,
  interaction: InteractionRecord,
  stale: boolean,
  now: string,
): { inserted: boolean; rowId: number } {
  const payload: Record<string, unknown> = {
    slateId,
    kind: interaction.kind,
    elementId: interaction.elementId ?? null,
    questionId: interaction.questionId ?? null,
    optionId: interaction.optionId ?? null,
    optionLabel: interaction.optionLabel ?? null,
    itemId: interaction.itemId ?? null,
    value: interaction.value ?? null,
    clientAt: interaction.clientAt ?? null,
  };
  const result = db.run(INSERT_INTERACTION, [
    slateId,
    interaction.kind,
    interaction.elementId ?? null,
    interaction.questionId ?? null,
    interaction.optionId ?? null,
    interaction.optionLabel ?? null,
    interaction.itemId ?? null,
    interaction.value ?? null,
    interaction.clientAt ?? null,
    stale ? 1 : 0,
    interaction.seq,
    canonicalJson(payload),
    now,
  ]);
  return { inserted: result.changes > 0, rowId: Number(result.lastInsertRowid) };
}

export function interactionById(db: Database, id: number): WebhookInteraction | null {
  const row = db
    .query<InteractionDbRow, [number]>(
      `SELECT id, slate_id, kind, element_id, question_id, option_id, option_label, item_id, value, client_at, stale, created_at
       FROM interactions WHERE id = ?`,
    )
    .get(id);
  return row ? shapeInteractionRow(row) : null;
}

export function interactionsByIds(db: Database, ids: number[]): WebhookInteraction[] {
  const out: WebhookInteraction[] = [];
  for (const id of ids) {
    const row = interactionById(db, id);
    if (row) out.push(row);
  }
  return out;
}

export interface InteractionDbRow {
  id: number;
  slate_id: string;
  kind: string;
  element_id: string | null;
  question_id: string | null;
  option_id: string | null;
  option_label: string | null;
  item_id: string | null;
  value: string | null;
  client_at: string | null;
  stale: number;
  created_at: string;
}

export function shapeInteractionRow(row: InteractionDbRow): WebhookInteraction {
  return {
    id: Number(row.id),
    slateId: row.slate_id,
    kind: row.kind,
    elementId: row.element_id,
    questionId: row.question_id,
    optionId: row.option_id,
    optionLabel: row.option_label,
    itemId: row.item_id,
    value: row.value,
    clientAt: row.client_at,
    stale: row.stale === 1,
    createdAt: row.created_at,
  };
}

export function isExpired(spec: unknown, now: Date): boolean {
  if (!isPlainObject(spec)) return false;
  const expiresAt = spec["expiresAt"];
  if (typeof expiresAt !== "string") return false;
  const expiry = Date.parse(expiresAt);
  return Number.isFinite(expiry) && now.getTime() > expiry;
}
