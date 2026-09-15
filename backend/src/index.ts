import { buildApp } from "./app";
import { createLogger, loadConfig, type AppConfig, type Logger } from "./config";
import { ensureDbDir, migrate, openDb } from "./db";

function main(): void {
  let config: AppConfig;
  try {
    config = loadConfig();
  } catch (error) {
    console.error(`[slate] boot failed: ${(error as Error).message}`);
    process.exit(1);
  }
  const log: Logger = createLogger(config);

  ensureDbDir(config.dbPath);
  const db = openDb(config.dbPath);
  const { applied } = migrate(db);
  if (applied.length > 0) log("info", `applied ${applied.length} migration(s)`, { migrations: applied });

  const app = buildApp({ db, config });
  app.listen(config.port);
  log("info", `slate v${config.version} listening on :${config.port}`, {
    dbPath: config.dbPath,
    webhook: config.webhookUrl ?? "disabled",
    sweep: config.sweepDisabled ? "disabled" : "10m",
  });

  const shutdown = (signal: string) => {
    log("info", `${signal} received — shutting down`);
    try {
      app.sweeper.stop();
      void app.webhook.stop();
    } catch {
      // best effort during shutdown
    }
    app.stop(true);
    db.close();
    process.exit(0);
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main();
