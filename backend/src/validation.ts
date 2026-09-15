import Ajv2020, { type ErrorObject, type ValidateFunction } from "ajv/dist/2020";
import addFormats from "ajv-formats";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

export interface ValidationErrorInfo {
  message: string;
  hint: string;
}

export type ValidationResult = { ok: true; errors: null } | { ok: false; errors: ValidationErrorInfo };

const SLATE_SCHEMA_ID = "https://slate.example.com/schemas/v2/slate.json";

/** Documented structural caps (schema/api.md). Enforced BEFORE Ajv: the recursive
 *  oneOf element schema costs exponential time under allErrors, so deep or wide
 *  payloads must never reach it. */
export const MAX_DEPTH = 6;
export const MAX_TOTAL_ELEMENTS = 60;

export type CapsResult = { ok: true } | { ok: false; message: string; hint: string };

export function checkStructuralCaps(body: unknown): CapsResult {
  if (!isPlainObjectLoose(body) || !Array.isArray((body as Record<string, unknown>)["children"])) {
    return { ok: true };
  }
  let total = 0;
  const visit = (node: unknown, depth: number): CapsResult => {
    if (!isPlainObjectLoose(node)) return { ok: true };
    total += 1;
    if (depth > MAX_DEPTH) {
      return {
        ok: false,
        message: `spec nesting exceeds the maximum depth of ${MAX_DEPTH}`,
        hint: `Columns/rows may nest at most ${MAX_DEPTH} levels deep (widget layouts are shallow). Found depth ${depth}. Flatten the structure — prefer consecutive statusRows inside one column over nested containers.`,
      };
    }
    if (total > MAX_TOTAL_ELEMENTS) {
      return {
        ok: false,
        message: `spec exceeds the maximum of ${MAX_TOTAL_ELEMENTS} elements`,
        hint: `A slate is capped at ${MAX_TOTAL_ELEMENTS} elements total (renderers degrade beyond that anyway). Split the content across a second slate id, or drop lower-priority rows. Elements counted so far: ${total}.`,
      };
    }
    const children = (node as Record<string, unknown>)["children"];
    if (Array.isArray(children)) {
      for (const child of children) {
        const r = visit(child, depth + 1);
        if (!r.ok) return r;
      }
    }
    return { ok: true };
  };
  const children = (body as Record<string, unknown>)["children"] as unknown[];
  for (const child of children) {
    const r = visit(child, 1);
    if (!r.ok) return r;
  }
  return { ok: true };
}

function isPlainObjectLoose(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Candidate locations for schema files, in order. Docker copies them to /app/schema. */
export function schemaCandidates(fileName: string): string[] {
  const candidates: string[] = [];
  if (process.env.SLATE_SCHEMA_DIR) candidates.push(resolve(process.env.SLATE_SCHEMA_DIR, fileName));
  // src/validation.ts -> repo root /schema
  candidates.push(resolve(import.meta.dir, "../../schema", fileName));
  candidates.push(resolve(process.cwd(), "schema", fileName));
  candidates.push(resolve("/app/schema", fileName));
  return candidates;
}

export function readSchemaFile(fileName: string): string {
  const tried: string[] = [];
  for (const candidate of schemaCandidates(fileName)) {
    tried.push(candidate);
    if (existsSync(candidate)) return readFileSync(candidate, "utf8");
  }
  throw new Error(
    `Cannot find schema file "${fileName}". Looked in: ${tried.join(", ")}. ` +
      "Run from the repo root, set SLATE_SCHEMA_DIR, or COPY schema/ into the image.",
  );
}

/** Convert an Ajv instancePath (/children/3/tone) to an LLM-friendly JS-ish path (children[3].tone). */
export function pointerToPath(instancePath: string): string {
  if (!instancePath) return "(root)";
  let out = "";
  for (const seg of instancePath.split("/").filter((s) => s.length > 0)) {
    const key = seg.replace(/~1/g, "/").replace(/~0/g, "~");
    if (/^\d+$/.test(key)) {
      out += `[${key}]`;
    } else {
      out += out.length === 0 ? key : `.${key}`;
    }
  }
  return out || "(root)";
}

function levenshtein(a: string, b: string): number {
  const m = a.length;
  const n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;
  let prev = Array.from({ length: n + 1 }, (_, i) => i);
  for (let i = 1; i <= m; i++) {
    const curr = [i];
    for (let j = 1; j <= n; j++) {
      curr[j] = Math.min(
        prev[j] + 1,
        curr[j - 1] + 1,
        prev[j - 1] + (a[i - 1].toLowerCase() === b[j - 1].toLowerCase() ? 0 : 1),
      );
    }
    prev = curr;
  }
  return prev[n];
}

function closest(value: string, options: string[]): string | undefined {
  let best: string | undefined;
  let bestScore = Number.POSITIVE_INFINITY;
  for (const option of options) {
    const score = levenshtein(value, option);
    if (score < bestScore) {
      bestScore = score;
      best = option;
    }
  }
  return best;
}

function snippet(value: unknown): string {
  const json = JSON.stringify(value);
  return json.length > 120 ? json.slice(0, 117) + "..." : json;
}

const COMBINATORS = new Set(["oneOf", "anyOf", "allOf", "not", "if", "then", "else"]);

interface MappedError {
  path: string;
  message: string;
  hint: string;
}

/** True if an Ajv instancePath points INSIDE a single element of the children tree. */
function isElementInternal(instancePath: string): boolean {
  return /^\/children\/\d+/.test(instancePath);
}

export class SlateValidator {
  readonly validateSlate: ValidateFunction;
  readonly validateInteractions: ValidateFunction;
  private readonly elementTypes: string[] = [];
  private readonly elementValidators = new Map<string, ValidateFunction>();
  private readonly elementDefs = new Map<string, Record<string, unknown>>();
  private readonly slateRoot: Record<string, unknown>;

  constructor() {
    const ajv = new Ajv2020({ allErrors: true, strict: false });
    addFormats(ajv);

    const slateSchema = JSON.parse(readSchemaFile("slate.schema.json"));
    const interactionsSchema = JSON.parse(readSchemaFile("interactions.schema.json"));

    ajv.addSchema(slateSchema);
    this.validateSlate = ajv.compile(slateSchema);
    this.validateInteractions = ajv.compile(interactionsSchema);
    this.slateRoot = slateSchema;

    // One validator per element type. Validating a known-type element directly
    // against its own schema produces clean, precise errors (no oneOf noise).
    const defs = (slateSchema as { $defs?: Record<string, { properties?: { type?: { const?: string } } }> }).$defs ?? {};
    const oneOf = (defs["element"] as { oneOf?: Array<{ $ref?: string }> })?.oneOf ?? [];
    for (const branch of oneOf) {
      const ref = branch.$ref ?? "";
      const name = ref.startsWith("#/$defs/") ? ref.slice("#/$defs/".length) : "";
      const constant = defs[name]?.properties?.type?.const;
      if (typeof constant !== "string") continue;
      this.elementTypes.push(constant);
      this.elementDefs.set(constant, (defs as Record<string, Record<string, unknown>>)[name] ?? {});
      this.elementValidators.set(
        constant,
        ajv.compile({
          $id: `urn:slate:element:${constant}`,
          $ref: `#/$defs/${name}`,
          $defs: (slateSchema as { $defs?: object })["$defs"] ?? {},
        }),
      );
    }
  }

  validateSpec(body: unknown): ValidationResult {
    return this.run(this.validateSlate, body);
  }

  validateInteractionBatch(body: unknown): ValidationResult {
    return this.run(this.validateInteractions, body);
  }

  private run(validator: ValidateFunction, body: unknown): ValidationResult {
    if (validator(body)) return { ok: true, errors: null };
    const errors = validator.errors ?? [];
    return { ok: false, errors: this.describe(errors, body) };
  }

  /** Pick the most useful issue and turn it into an LLM-friendly message + hint. */
  describe(errors: ErrorObject[], data: unknown): ValidationErrorInfo {
    const unknownElement = this.findUnknownElementType(data, "");
    if (unknownElement) {
      const total = errors.filter((e) => !COMBINATORS.has(e.keyword)).length;
      return this.best([unknownElement], Math.max(total - 1, 0));
    }

    // Per-type element issues are precise; root-level issues cover everything else.
    const elementIssues = this.collectElementIssues(data);
    if (elementIssues.length === 0) {
      const rootIssues: MappedError[] = [];
      const seen = new Set<string>();
      for (const e of errors) {
        if (COMBINATORS.has(e.keyword)) continue;
        if (isElementInternal(e.instancePath)) continue;
        const key = `${e.keyword}|${e.instancePath}|${e.message ?? ""}|${JSON.stringify(e.params)}`;
        if (seen.has(key)) continue;
        seen.add(key);
        rootIssues.push(this.mapOne(e, pointerToPath(e.instancePath), data, resolveContainer(this.slateRoot, e.schemaPath)));
      }
      return this.best(rootIssues, 0);
    }

    const rootIssueCount = errors.filter((e) => !COMBINATORS.has(e.keyword) && !isElementInternal(e.instancePath)).length;
    return this.best(elementIssues, rootIssueCount + elementIssues.length - 1);
  }

  private best(issues: MappedError[], moreCount: number): ValidationErrorInfo {
    if (issues.length === 0) {
      return {
        message: "Validation failed.",
        hint: `The payload does not match schema/slate.schema.json. Check required fields (id, version: 2, children) and re-PUT the complete envelope. Agents should not send updatedAt — the server stamps it.`,
      };
    }
    const best = issues[0];
    return {
      message: moreCount > 0 ? `${best.path}: ${best.message} (and ${moreCount} more issue${moreCount === 1 ? "" : "s"})` : `${best.path}: ${best.message}`,
      hint: best.hint,
    };
  }

  /**
   * Validate every known-type element in the children tree against its own
   * schema; returns the first error of the first broken element (pre-order).
   */
  private collectElementIssues(value: unknown, pointer = ""): MappedError[] {
    if (Array.isArray(value)) {
      for (let i = 0; i < value.length; i++) {
        const hit = this.collectElementIssues(value[i], `${pointer}/${i}`);
        if (hit.length > 0) return hit;
      }
      return [];
    }
    if (value !== null && typeof value === "object") {
      const obj = value as Record<string, unknown>;
      const type = obj["type"];
      if (typeof type === "string" && this.elementValidators.has(type)) {
        const validate = this.elementValidators.get(type)!;
        if (!validate(obj) && (validate.errors ?? []).length > 0) {
          const e = validate.errors![0];
          return [
            this.mapOne(
              e,
              pointerToPath(`${pointer}${e.instancePath}`),
              obj,
              resolveContainer(this.elementDefs.get(type) ?? {}, e.schemaPath),
            ),
          ];
        }
        return [];
      }
      const children = obj["children"];
      if (children !== undefined) {
        return this.collectElementIssues(children, `${pointer}/children`);
      }
    }
    return [];
  }

  /** Walk children trees; if any element declares an unknown "type", describe it. */
  private findUnknownElementType(value: unknown, pointer: string): MappedError | null {
    if (Array.isArray(value)) {
      for (let i = 0; i < value.length; i++) {
        const hit = this.findUnknownElementType(value[i], `${pointer}/${i}`);
        if (hit) return hit;
      }
      return null;
    }
    if (value !== null && typeof value === "object") {
      const obj = value as Record<string, unknown>;
      const type = obj["type"];
      if (typeof type === "string" && !this.elementTypes.includes(type)) {
        const path = pointerToPath(`${pointer}/type`);
        const guess = closest(type, this.elementTypes);
        return {
          path,
          message: `unknown element type "${type}"`,
          hint:
            `"${type}" is not a valid element type at ${pointerToPath(pointer || "")}. ` +
            `Valid element types: ${this.elementTypes.join(", ")}.` +
            (guess ? ` Closest valid type: "${guess}".` : "") +
            ` Renderers degrade unknown content to a caption, but the backend rejects it so agents catch typos early. Corrected snippet: {"type":"${guess ?? this.elementTypes[0]}", ...}`,
        };
      }
      const children = obj["children"];
      if (children !== undefined) {
        return this.findUnknownElementType(children, `${pointer}/children`);
      }
    }
    return null;
  }

  /** Map a single Ajv error. `context` is the object the error's instancePath is relative to;
   *  `container` is the schema object that owns the failing keyword (for allowed-values lists). */
  private mapOne(e: ErrorObject, path: string, context: unknown, container?: Record<string, unknown>): MappedError {
    const params = e.params as Record<string, unknown>;

    switch (e.keyword) {
      case "const": {
        if (path.endsWith(".type") || path === "type" || path === "(root)") {
          const received = String(valueAt(context, e.instancePath) ?? "");
          const guess = closest(received, this.elementTypes);
          return {
            path,
            message: `unknown element type "${received}"`,
            hint:
              `"${received}" is not a valid element type at ${parentPath(path)}. ` +
              `Valid element types: ${this.elementTypes.join(", ")}.` +
              (guess ? ` Closest valid type: "${guess}".` : "") +
              ` Corrected snippet: {"type":"${guess ?? this.elementTypes[0]}", ...}`,
          };
        }
        return {
          path,
          message: `must be ${JSON.stringify(params["allowedValue"])}`,
          hint:
            `${path} must be exactly ${JSON.stringify(params["allowedValue"])}. Received: ${snippet(valueAt(context, e.instancePath))}. Corrected snippet: ${snippet(suggestFix(path, params["allowedValue"]))}` +
            (path === "version"
              ? `. This server speaks spec version ${params["allowedValue"]} only — check what versions it supports via GET /api/ping (field "version").`
              : ""),
        };
      }
      case "enum": {
        const options = (params["allowedValues"] as unknown[])?.map((v) => String(v)) ?? [];
        const received = valueAt(context, e.instancePath);
        const guess = closest(String(received ?? ""), options);
        return {
          path,
          message: `must be one of ${options.join("|")}`,
          hint:
            `${path} must be one of ${options.map((o) => `"${o}"`).join(" | ")}.` +
            (guess ? ` For "${received}" you probably want "${guess}".` : "") +
            ` Corrected snippet: ${snippet(suggestFix(path, guess ?? options[0]))}`,
        };
      }
      case "additionalProperties": {
        const extra = String(params["additionalProperty"]);
        const props = container?.["properties"];
        const allowed = (props !== null && typeof props === "object" ? Object.keys(props) : []).filter((k) => k !== "type");
        return {
          path,
          message: `unknown property "${extra}"`,
          hint:
            `Property "${extra}" is not allowed at ${path}. ` +
            (allowed.length > 0 ? `Allowed properties here: ${allowed.join(", ")}. ` : "Remove it. ") +
            `Unknown fields are rejected to catch agent typos early. Corrected snippet: ${snippet(suggestRemove(valueAt(context, e.instancePath), extra))}`,
        };
      }
      case "required": {
        const missing = String(params["missingProperty"]);
        const req = container?.["required"];
        const required = (Array.isArray(req) ? req.map(String) : []).join(", ");
        return {
          path,
          message: `missing required property "${missing}"`,
          hint: `${path} is missing required property "${missing}".` + (required ? ` Required properties here: ${required}.` : "") + ` Corrected snippet: ${snippet({ [missing]: exampleFor(typeof missing === "string" && missing === "children" ? "array" : "string") })}`,
        };
      }
      case "type": {
        const expected = String(params["type"]);
        const received = valueAt(context, e.instancePath);
        return {
          path,
          message: `must be ${expected}`,
          hint: `${path} must be of type ${expected}, but got ${Array.isArray(received) ? "array" : received === null ? "null" : typeof received} (${snippet(received)}). Corrected snippet: ${snippet(suggestFix(path, exampleFor(expected)))}`,
        };
      }
      case "format": {
        const format = String(params["format"]);
        const received = valueAt(context, e.instancePath);
        return {
          path,
          message: `must be a valid ${format}`,
          hint:
            `${path} must match format "${format}" but received ${snippet(received)}. ` +
            (format === "date-time" ? `Use ISO-8601 / RFC 3339 UTC, e.g. "2026-01-15T09:30:00Z". ` : `Provide a valid ${format}. `) +
            `Corrected snippet: ${snippet(suggestFix(path, format === "date-time" ? "2026-01-15T09:30:00Z" : `<${format}>`))}`,
        };
      }
      case "pattern": {
        const pattern = String(params["pattern"]);
        const received = valueAt(context, e.instancePath);
        return {
          path,
          message: `${snippet(received)} does not match pattern ${pattern}`,
          hint: `${path} must match ${pattern} — start with a letter or digit, then letters/digits/underscore/dot/dash, max 40 chars. Received: ${snippet(received)}. Corrected snippet: ${snippet(suggestFix(path, "my-id-1"))}`,
        };
      }
      case "maxLength":
      case "minLength": {
        const limit = Number(params["limit"]);
        const received = valueAt(context, e.instancePath);
        return {
          path,
          message: `${e.keyword === "maxLength" ? "must be at most" : "must be at least"} ${limit} characters`,
          hint: `${path} must be ${e.keyword === "maxLength" ? "at most" : "at least"} ${limit} characters (currently ${typeof received === "string" ? received.length : "not a string"}). Shorten it — widget surfaces are small.`,
        };
      }
      case "minItems":
      case "maxItems": {
        const limit = Number(params["limit"]);
        const received = valueAt(context, e.instancePath);
        const n = Array.isArray(received) ? received.length : 0;
        return {
          path,
          message: `must have ${e.keyword === "minItems" ? "at least" : "at most"} ${limit} item${limit === 1 ? "" : "s"}`,
          hint: `${path} must contain ${e.keyword === "minItems" ? "at least" : "at most"} ${limit} item${limit === 1 ? "" : "s"} (currently ${n}).`,
        };
      }
      case "minimum":
      case "maximum": {
        const limit = Number(params["limit"] ?? params["exclusiveMinimum"] ?? params["exclusiveMaximum"]);
        const received = valueAt(context, e.instancePath);
        return {
          path,
          message: `must be ${e.keyword === "minimum" ? ">=" : "<="} ${limit}`,
          hint: `${path} must be ${e.keyword === "minimum" ? "at least" : "at most"} ${limit} (received ${snippet(received)}). Corrected snippet: ${snippet(suggestFix(path, limit))}`,
        };
      }
      default: {
        return {
          path,
          message: e.message ?? "is invalid",
          hint: `${path}: ${e.message ?? "is invalid"} (schema ${e.schemaPath}). Validate against schema/slate.schema.json and re-PUT the complete envelope.`,
        };
      }
    }
  }
}

function parentPath(path: string): string {
  const idx = Math.max(path.lastIndexOf("."), path.lastIndexOf("["));
  return idx > 0 ? path.slice(0, idx) : "(root)";
}

/**
 * Ajv v8 does not include parentSchema on errors, so we resolve the schema
 * object that owns the failing keyword by walking the schemaPath ourselves.
 */
function resolveContainer(base: unknown, schemaPath: string): Record<string, unknown> | undefined {
  let cur: unknown = base;
  const tokens = schemaPath.split("/").filter((t) => t !== "" && t !== "#");
  for (let i = 0; i < tokens.length - 1; i++) {
    if (cur === null || typeof cur !== "object") return undefined;
    cur = (cur as Record<string, unknown>)[tokens[i]];
  }
  return cur !== null && typeof cur === "object" ? (cur as Record<string, unknown>) : undefined;
}

function valueAt(data: unknown, instancePath: string): unknown {
  let cur: unknown = data;
  for (const rawSeg of instancePath.split("/").filter((s) => s.length > 0)) {
    const seg = rawSeg.replace(/~1/g, "/").replace(/~0/g, "~");
    if (cur === null || typeof cur !== "object") return undefined;
    cur = (cur as Record<string, unknown>)[seg];
  }
  return cur;
}

function suggestFix(path: string, value: unknown): unknown {
  const prop = path.split(/[.\[\]]/).filter((s) => s.length > 0 && !/^\d+$/.test(s)).pop();
  return { [prop ?? "field"]: value };
}

function suggestRemove(obj: unknown, key: string): unknown {
  if (obj !== null && typeof obj === "object" && !Array.isArray(obj)) {
    const copy = { ...(obj as Record<string, unknown>) };
    delete copy[key];
    return copy;
  }
  return { [key]: undefined };
}

function exampleFor(type: string): unknown {
  switch (type) {
    case "string":
      return "text";
    case "number":
    case "integer":
      return 1;
    case "boolean":
      return true;
    case "object":
      return {};
    case "array":
      return [];
    default:
      return null;
  }
}
