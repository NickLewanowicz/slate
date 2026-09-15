import packageJson from "../package.json";

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface AppConfig {
  port: number;
  dbPath: string;
  apiKey: string;
  webhookUrl: string | undefined;
  webhookSecret: string | undefined;
  maxResponsesPerSlate: number;
  responseTtlHours: number;
  logLevel: LogLevel;
  sweepDisabled: boolean;
  /** Delivery schedule: initial attempt + one try per delay. Overridable for tests. */
  webhookRetryDelaysMs: number[];
  version: string;
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConfigError";
  }
}

const LOG_LEVELS: LogLevel[] = ["debug", "info", "warn", "error"];

function fail(message: string): never {
  throw new ConfigError(message);
}

function parsePort(env: Record<string, string | undefined>): number {
  const raw = env.SLATE_PORT;
  if (raw === undefined || raw.trim() === "") return 3000;
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    fail(`SLATE_PORT must be an integer between 1 and 65535, got "${raw}". Example: SLATE_PORT=3000`);
  }
  return port;
}

function parseIntEnv(
  env: Record<string, string | undefined>,
  key: string,
  def: number,
  min: number,
): number {
  const raw = env[key];
  if (raw === undefined || raw.trim() === "") return def;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min) {
    fail(`${key} must be an integer >= ${min}, got "${raw}". Example: ${key}=${def}`);
  }
  return value;
}

function parseWebhookUrl(env: Record<string, string | undefined>): string | undefined {
  const raw = env.SLATE_WEBHOOK_URL;
  if (raw === undefined || raw.trim() === "") return undefined;
  try {
    const url = new URL(raw);
    if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error("bad protocol");
    return url.toString();
  } catch {
    fail(`SLATE_WEBHOOK_URL must be a valid http(s) URL, got "${raw}". Example: SLATE_WEBHOOK_URL=http://openclaw-gateway:8080/wake`);
  }
}

export function loadConfig(
  env: Record<string, string | undefined> = process.env,
  overrides: Partial<AppConfig> = {},
): AppConfig {
  const apiKey = env.SLATE_API_KEY;
  if (apiKey === undefined || apiKey.trim() === "") {
    fail(
      'SLATE_API_KEY is required but not set. Generate one (e.g. `openssl rand -hex 32`) and start the server with SLATE_API_KEY=<key> bun run src/index.ts — every /api route requires "Authorization: Bearer <SLATE_API_KEY>".',
    );
  }
  const logLevelRaw = env.SLATE_LOG_LEVEL ?? "info";
  if (!LOG_LEVELS.includes(logLevelRaw as LogLevel)) {
    fail(`SLATE_LOG_LEVEL must be one of ${LOG_LEVELS.join("|")}, got "${logLevelRaw}". Example: SLATE_LOG_LEVEL=info`);
  }

  const base: AppConfig = {
    port: parsePort(env),
    dbPath: env.SLATE_DB_PATH?.trim() ? env.SLATE_DB_PATH : "./data/slate.db",
    apiKey: apiKey,
    webhookUrl: parseWebhookUrl(env),
    webhookSecret: env.SLATE_WEBHOOK_SECRET?.trim() ? env.SLATE_WEBHOOK_SECRET : undefined,
    maxResponsesPerSlate: parseIntEnv(env, "SLATE_MAX_RESPONSES_PER_SLATE", 200, 1),
    responseTtlHours: parseIntEnv(env, "SLATE_RESPONSE_TTL_HOURS", 168, 0),
    logLevel: logLevelRaw as LogLevel,
    sweepDisabled: env.SLATE_SWEEP_DISABLED === "1",
    webhookRetryDelaysMs: [2_000, 8_000, 30_000],
    version: packageJson.version,
  };
  return { ...base, ...overrides };
}

export type Logger = (level: LogLevel, message: string, meta?: Record<string, unknown>) => void;

export function createLogger(config: Pick<AppConfig, "logLevel">): Logger {
  const threshold = LOG_LEVELS.indexOf(config.logLevel);
  const labels: Record<LogLevel, string> = { debug: "DEBUG", info: "INFO", warn: "WARN", error: "ERROR" };
  return (level, message, meta) => {
    if (LOG_LEVELS.indexOf(level) < threshold) return;
    const suffix = meta && Object.keys(meta).length > 0 ? ` ${JSON.stringify(meta)}` : "";
    const line = `[slate] ${new Date().toISOString()} ${labels[level]} ${message}${suffix}`;
    if (level === "error") console.error(line);
    else if (level === "warn") console.warn(line);
    else console.log(line);
  };
}
