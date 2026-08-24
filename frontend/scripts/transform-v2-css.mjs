#!/usr/bin/env node
/**
 * Transform CopilotKit v2 styles.css (Tailwind v4) to Tailwind v3 compatible.
 *
 * Strips:
 *   - The `@layer properties { @supports ... { *, ... } }` block (v4 only)
 *   - The `@layer base { ... }` block (moves rules out — Tailwind v3 has no @layer base)
 *   - The `@layer components` and `@layer utilities` headers (keeps their rules)
 *
 * This script is run as a postinstall hook. The result is written to
 * `patches/copilotkit-v2-v3.css` and aliased in next.config.js.
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, "..");

const SRC = join(
  ROOT,
  "node_modules/@copilotkit/react-core/dist/v2/index.css"
);
const DEST = join(ROOT, "patches/copilotkit-v2-v3.css");

function stripLayerProperties(css) {
  // Match `@layer properties { @supports (...) { ..., :root, :host, ... } }` blocks.
  // These are v4-only auto-property inference; strip them entirely.
  return css.replace(
    /@layer\s+properties\s*\{@supports\s*\([^]*?\}\s*\}\s*\}\s*\}/g,
    ""
  );
}

function unwrapLayerBlocks(css) {
  // For each `@layer NAME { ... }` block, keep the inner content and drop the wrapper.
  // We do this for the whole file (top-level `@layer base`, `@layer components`, etc.).
  return css.replace(/@layer\s+(\w+)\s*\{/g, (m) => m.replace("@layer", "/* @layer */"));
}

function extractBracedBlocks(css) {
  // Find a top-level `{ ... }` block; return [content, endIndex].
  // We only call this at the top level, so depth counts only matter from 1→0.
  const start = css.indexOf("{");
  if (start === -1) return null;
  let depth = 1;
  let i = start + 1;
  while (i < css.length && depth > 0) {
    const ch = css[i];
    if (ch === "{") depth++;
    else if (ch === "}") depth--;
    i++;
  }
  if (depth !== 0) return null;
  return [css.slice(start + 1, i - 1), i];
}

function unwrapTopLevelLayerBlocks(css) {
  // Repeatedly: find a `@layer NAME { ... }` at the top level, extract `...`, and
  // keep the inner content. The body itself can contain nested `@layer` (rare
  // in v4 output but we handle 1 level of nesting to be safe).
  let out = "";
  let i = 0;
  while (i < css.length) {
    // Find the next `@layer ` at the current top level.
    const layerMatch = /^@layer\s+(\w+)\s*\{/.exec(css.slice(i));
    if (layerMatch) {
      // Find the matching closing brace for the block.
      const start = i + layerMatch[0].length - 1; // position of `{`
      let depth = 1;
      let j = start + 1;
      while (j < css.length && depth > 0) {
        const ch = css[j];
        if (ch === "{") depth++;
        else if (ch === "}") depth--;
        j++;
      }
      if (depth !== 0) {
        // Unbalanced; bail out.
        out += css.slice(i);
        break;
      }
      const body = css.slice(start + 1, j - 1);
      // If body contains nested `@layer`, recurse on the body.
      out += unwrapTopLevelLayerBlocks(body);
      i = j;
      continue;
    }
    // Otherwise, copy one char and advance.
    out += css[i];
    i++;
  }
  return out;
}

function main() {
  const original = readFileSync(SRC, "utf8");
  let transformed = original;
  transformed = stripLayerProperties(transformed);
  transformed = unwrapTopLevelLayerBlocks(transformed);
  const before = original.length;
  const after = transformed.length;

  if (process.env.CHECK_ONLY === "true") {
    let existing;
    try {
      existing = readFileSync(DEST, "utf8");
    } catch (error) {
      console.error(`v2 css check failed: missing generated file ${DEST}`);
      process.exitCode = 1;
      return;
    }
    if (existing !== transformed) {
      console.error(
        "v2 css check failed: patches/copilotkit-v2-v3.css is stale; run npm install to regenerate it",
      );
      process.exitCode = 1;
      return;
    }
    console.log(`v2 css check: ${after} bytes, generated file is up to date`);
    return;
  }

  mkdirSync(dirname(DEST), { recursive: true });
  writeFileSync(DEST, transformed, "utf8");
  console.log(
    `v2 css: ${before} → ${after} bytes (${((1 - after / before) * 100).toFixed(1)}% smaller)`
  );
}

main();
