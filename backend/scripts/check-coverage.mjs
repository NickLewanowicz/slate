#!/usr/bin/env node
/**
 * Branch-coverage gate for the Slate v2 backend.
 *
 *   node scripts/check-coverage.mjs [threshold=80]
 *
 * Parses backend/coverage/lcov.info (produced by `bun test --coverage
 * --coverage-reporter=lcov`) and enforces, per src/ file and overall:
 *   - branch coverage  >= threshold
 *   - line coverage    >= threshold
 *   - function coverage >= threshold
 *
 * Branch coverage:
 *   - If the lcov file contains BRDA records (Bun >= a future version,
 *     see https://github.com/oven-sh/bun/issues/7100), the TRUE branch hit
 *     rate is computed: branches taken / branches found.
 *   - Bun 1.3.x emits NO BRDA records (only FN/DA). In that mode we fall
 *     back to a decision-point proxy: every branch construct in the source
 *     (if, ternary, case, &&, ||, ??, catch, loop) is counted per line, and
 *     a construct counts as covered when its line has line coverage (a DA
 *     hit). The script prints which mode is active.
 *
 * Exit 1 below threshold. Test files, node_modules, .sql and scripts are
 * excluded; only src/ is gated.
 */
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const backendDir = resolve(scriptDir, "..");
const lcovPath = process.env.LCOV_PATH ?? join(backendDir, "coverage", "lcov.info");
const threshold = Number(process.argv[2] ?? 80);

if (!Number.isFinite(threshold) || threshold < 0 || threshold > 100) {
  console.error(`usage: node scripts/check-coverage.mjs [threshold 0-100] (got "${process.argv[2]}")`);
  process.exit(1);
}
if (!existsSync(lcovPath)) {
  console.error(`coverage file not found: ${lcovPath}. Run: bun test --coverage --coverage-reporter=lcov`);
  process.exit(1);
}

const srcDir = join(backendDir, "src");

function listSourceFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      out.push(...listSourceFiles(full));
    } else if (entry.endsWith(".ts")) {
      out.push(full);
    }
  }
  return out;
}

/**
 * Count branch-decision constructs per line of a TypeScript source file.
 * String/regex/comment stripping is a pragmatic approximation: we only need
 * stable relative counts, not perfect parsing.
 */
function stripNoise(source) {
  let out = "";
  let i = 0;
  let mode = "code"; // code | line | block | template | string | regex
  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];
    if (mode === "code") {
      if (c === "/" && next === "/") { mode = "line"; i += 2; out += "  "; continue; }
      if (c === "/" && next === "*") { mode = "block"; i += 2; out += "  "; continue; }
      if (c === "`") { mode = "template"; i += 1; out += " "; continue; }
      if (c === '"' || c === "'") { mode = "string"; i += 1; out += " "; continue; }
      out += c;
      i += 1;
      continue;
    }
    if (mode === "line") {
      if (c === "\n") { mode = "code"; out += c; } else { out += " "; }
      i += 1;
      continue;
    }
    if (mode === "block") {
      if (c === "*" && next === "/") { mode = "code"; i += 2; out += "  "; continue; }
      out += c === "\n" ? "\n" : " ";
      i += 1;
      continue;
    }
    if (mode === "template") {
      if (c === "\\") { i += 2; out += "  "; continue; }
      if (c === "`") { mode = "code"; out += " "; i += 1; continue; }
      out += c === "\n" ? "\n" : " ";
      i += 1;
      continue;
    }
    // string
    if (c === "\\") { i += 2; out += "  "; continue; }
    if (c === '"' || c === "'") { mode = "code"; out += " "; i += 1; continue; }
    out += c === "\n" ? "\n" : " ";
    i += 1;
    continue;
  }
  return out;
}

const BRANCH_PATTERNS = [
  /\bif\s*\(/g,               // if / else if
  /\?\s*[^:?]+\s*:/g,          // ternary (rough; excludes ?? and ?:)
  /\bcase\s[^:]+:/g,           // switch case
  /&&/g,
  /\|\|/g,
  /\?\?/g,
  /\bcatch\s*\(/g,
  /\bfor\s*\(/g,
  /\bwhile\s*\(/g,
];

function branchConstructsPerLine(source) {
  const lines = stripNoise(source).split("\n");
  const perLine = new Map(); // line number -> construct count
  lines.forEach((text, idx) => {
    let count = 0;
    for (const pattern of BRANCH_PATTERNS) {
      pattern.lastIndex = 0;
      let m;
      while ((m = pattern.exec(text)) !== null) count += 1;
    }
    if (count > 0) perLine.set(idx + 1, count);
  });
  return perLine;
}

// --- parse lcov ----------------------------------------------------------
const raw = readFileSync(lcovPath, "utf8");
const records = [];
for (const block of raw.split("end_of_record")) {
  if (!block.trim()) continue;
  const record = { file: null, lines: { found: 0, hit: 0 }, hitLines: new Set(), functions: { found: 0, hit: 0 }, branches: { found: 0, hit: 0 } };
  let branchFound = 0;
  let branchHit = 0;
  for (const line of block.split("\n")) {
    const line0 = line.trim();
    const value = (prefix) => (line0.startsWith(prefix) ? Number(line0.slice(prefix.length)) : undefined);
    if (line0.startsWith("SF:")) record.file = line0.slice(3);
    else if (value("LF:") !== undefined) record.lines.found = value("LF:");
    else if (value("LH:") !== undefined) record.lines.hit = value("LH:");
    else if (line0.startsWith("DA:")) {
      const [, hitStr] = line0.slice(3).split(",");
      if (Number(hitStr) > 0) record.hitLines.add(Number(line0.slice(3).split(",")[0]));
    } else if (value("FNF:") !== undefined) record.functions.found = value("FNF:");
    else if (value("FNH:") !== undefined) record.functions.hit = value("FNH:");
    else if (value("BRF:") !== undefined) branchFound = value("BRF:");
    else if (value("BRH:") !== undefined) branchHit = value("BRH:");
    else if (line0.startsWith("BRDA:")) {
      record.branches.found += 1;
      const [, , , taken] = line0.slice(5).split(",");
      if (taken !== "0" && taken !== "-") record.branches.hit += 1;
    }
  }
  record.branches.found += branchFound;
  record.branches.hit += branchHit;
  if (record.file) records.push(record);
}

const gateFiles = records.filter((r) => {
  const rel = relative(backendDir, resolve(backendDir, r.file));
  return (rel.startsWith(`src${sep}`) || rel.startsWith("src/")) && rel.endsWith(".ts");
});

if (gateFiles.length === 0) {
  console.error(`no src/ records found in ${lcovPath} — did the test run import any src files?`);
  process.exit(1);
}

// --- branch metric -------------------------------------------------------
const brdaRecords = gateFiles.filter((r) => r.branches.found > 0);
const mode = brdaRecords.length > 0 ? "BRDA (true branch coverage)" : "decision-point proxy (Bun 1.3.x emits no BRDA records — see oven-sh/bun#7100)";

let totalBranches = 0;
let coveredBranches = 0;

const rows = [];
for (const record of gateFiles) {
  const abs = resolve(backendDir, record.file);
  let branchTotal = record.branches.found;
  let branchCovered = record.branches.hit;
  if (record.branches.found === 0) {
    // proxy: count constructs in source, covered when the line has a DA hit
    const perLine = branchConstructsPerLine(readFileSync(abs, "utf8"));
    for (const [lineNumber, count] of perLine) {
      branchTotal += count;
      if (record.hitLines.has(lineNumber)) branchCovered += count;
    }
  }
  totalBranches += branchTotal;
  coveredBranches += branchCovered;
  rows.push({
    file: relative(backendDir, abs),
    branchPct: branchTotal === 0 ? 100 : (100 * branchCovered) / branchTotal,
    linePct: record.lines.found === 0 ? 100 : (100 * record.lines.hit) / record.lines.found,
    funcPct: record.functions.found === 0 ? 100 : (100 * record.functions.hit) / record.functions.found,
  });
}

rows.sort((a, b) => a.file.localeCompare(b.file));
const overallBranch = totalBranches === 0 ? 100 : (100 * coveredBranches) / totalBranches;

console.log(`\nBranch coverage gate (threshold ${threshold}%) — mode: ${mode}\n`);
console.log("File".padEnd(40) + "Branch %".padStart(10) + "Line %".padStart(10) + "Func %".padStart(10));
for (const row of rows) {
  const flag = row.branchPct < threshold || row.linePct < threshold || row.funcPct < threshold ? "  <-- BELOW" : "";
  console.log(row.file.slice(0, 39).padEnd(40) + row.branchPct.toFixed(2).padStart(10) + row.linePct.toFixed(2).padStart(10) + row.funcPct.toFixed(2).padStart(10) + flag);
}
console.log("-".repeat(70));
const overallLine = gateFiles.reduce((acc, r) => acc + r.lines.found, 0) === 0 ? 100
  : (100 * gateFiles.reduce((acc, r) => acc + r.lines.hit, 0)) / gateFiles.reduce((acc, r) => acc + r.lines.found, 0);
const overallFunc = gateFiles.reduce((acc, r) => acc + r.functions.found, 0) === 0 ? 100
  : (100 * gateFiles.reduce((acc, r) => acc + r.functions.hit, 0)) / gateFiles.reduce((acc, r) => acc + r.functions.found, 0);
console.log("ALL src/ FILES".padEnd(40) + overallBranch.toFixed(2).padStart(10) + overallLine.toFixed(2).padStart(10) + overallFunc.toFixed(2).padStart(10));

const okBranch = overallBranch >= threshold;
const okLine = overallLine >= threshold;
const okFunc = overallFunc >= threshold;
const okPerFile = rows.every((r) => r.branchPct >= threshold && r.linePct >= threshold && r.funcPct >= threshold);

if (okBranch && okLine && okFunc && okPerFile) {
  console.log(`\nPASS: branch ${overallBranch.toFixed(2)}% >= ${threshold}% (line ${overallLine.toFixed(2)}%, func ${overallFunc.toFixed(2)}%)`);
  process.exit(0);
}
console.error(`\nFAIL: branch ${overallBranch.toFixed(2)}% (need ${threshold}%), line ${overallLine.toFixed(2)}%, func ${overallFunc.toFixed(2)}%${okPerFile ? "" : ", some files below threshold"}`);
process.exit(1);
