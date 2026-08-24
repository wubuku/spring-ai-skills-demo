import assert from "node:assert/strict";
import { access, readFile, stat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const requiredFiles = [
  "scripts/transform-v2-css.mjs",
  "patches/copilotkit-v2-v3.css",
];

for (const relativePath of requiredFiles) {
  const absolutePath = resolve(frontendRoot, relativePath);
  await access(absolutePath);
  const fileStat = await stat(absolutePath);
  assert.equal(fileStat.isFile(), true, `${relativePath} must be a regular file`);
  assert.ok(fileStat.size > 0, `${relativePath} must not be empty`);
}

const packageJson = JSON.parse(
  await readFile(resolve(frontendRoot, "package.json"), "utf8"),
);
assert.equal(packageJson.scripts.postinstall, "node scripts/transform-v2-css.mjs");
assert.equal(packageJson.engines.node, ">=22.19.0");
assert.equal(packageJson.dependencies.next, "15.5.23");
assert.equal(packageJson.dependencies.undici, "8.10.0");
for (const packageName of [
  "@copilotkit/react-core",
  "@copilotkit/react-ui",
  "@copilotkit/runtime",
  "@copilotkit/runtime-client-gql",
]) {
  assert.equal(packageJson.dependencies[packageName], "1.60.2");
}
assert.deepEqual(packageJson.overrides.next, {
  postcss: "8.5.26",
  sharp: "0.35.3",
});
assert.deepEqual(packageJson.overrides["@ai-sdk/provider-utils@3.0.32"], {
  undici: "6.28.0",
});

const packageLockSource = await readFile(
  resolve(frontendRoot, "package-lock.json"),
  "utf8",
);
assert.doesNotMatch(
  packageLockSource,
  /registry\.npmmirror\.com/,
  "package-lock.json must use the official npm registry",
);
const packageLock = JSON.parse(packageLockSource);
assert.equal(packageLock.packages[""].engines.node, ">=22.19.0");
assert.equal(packageLock.packages["node_modules/next"].version, "15.5.23");
assert.equal(packageLock.packages["node_modules/undici"].version, "8.10.0");
assert.equal(packageLock.packages["node_modules/postcss"].version, "8.5.26");
assert.equal(packageLock.packages["node_modules/sharp"].version, "0.35.3");
for (const packagePath of [
  "node_modules/@ai-sdk/google-vertex/node_modules/undici",
  "node_modules/@ai-sdk/openai-compatible/node_modules/undici",
  "node_modules/@ai-sdk/provider-utils/node_modules/undici",
]) {
  assert.equal(packageLock.packages[packagePath].version, "6.28.0");
}
for (const packageName of [
  "@copilotkit/react-core",
  "@copilotkit/react-ui",
  "@copilotkit/runtime",
  "@copilotkit/runtime-client-gql",
]) {
  assert.equal(
    packageLock.packages[`node_modules/${packageName}`].version,
    "1.60.2",
  );
}

const nextConfig = await readFile(resolve(frontendRoot, "next.config.js"), "utf8");
assert.match(nextConfig, /outputFileTracingRoot:\s*__dirname/);
assert.match(nextConfig, /patches\/copilotkit-v2-v3\.css/);

const layout = await readFile(resolve(frontendRoot, "app/layout.tsx"), "utf8");
assert.match(layout, /import "\.\.\/patches\/copilotkit-v2-v3\.css";/);

const result = await new Promise((resolvePromise, reject) => {
  const child = spawn(
    process.execPath,
    ["scripts/transform-v2-css.mjs"],
    {
      cwd: frontendRoot,
      env: { ...process.env, CHECK_ONLY: "true" },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  child.on("error", reject);
  child.on("close", (code) => resolvePromise({ code, stdout, stderr }));
});

assert.equal(
  result.code,
  0,
  `CSS generated artifact is stale or missing:\n${result.stdout}${result.stderr}`,
);

console.log("repository-reproducibility: passed");
