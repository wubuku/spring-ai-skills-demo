// Hydration diagnostic: open page, dump all console + pageerror + the raw HTML
import { chromium } from "playwright";

const FRONTEND = "http://localhost:4000";
const OUT = "/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/e2e-screenshots/hydra-before.png";

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1100 } });
const page = await ctx.newPage();

const consoleMsgs = [];
const pageErrors = [];
const allLogs = [];

page.on("console", msg => {
  const entry = `[${msg.type()}] ${msg.text()}`;
  consoleMsgs.push(entry);
  allLogs.push(entry);
});
page.on("pageerror", err => {
  const entry = `[PAGEERROR] ${err.message}\n${err.stack || ""}`;
  pageErrors.push(entry);
  allLogs.push(entry);
});

await page.goto(FRONTEND, { waitUntil: "domcontentloaded", timeout: 30000 });
await page.waitForTimeout(5000); // give hydration time to fail

// Dump server HTML for comparison
const serverHtml = await page.content();

// Check if there's a hydration-error overlay
const overlayText = await page.evaluate(() => {
  const el = document.querySelector('nextjs-portal') ||
             document.querySelector('[data-nextjs-dialog-overlay]');
  return el ? el.innerText.slice(0, 2000) : null;
});

await page.screenshot({ path: OUT, fullPage: false });

console.log("=== CONSOLE MESSAGES ===");
consoleMsgs.forEach(m => console.log(m));
console.log("\n=== PAGE ERRORS ===");
pageErrors.forEach(m => console.log(m));
console.log("\n=== NEXT DEV OVERLAY ===");
console.log(overlayText || "(no overlay)");
console.log("\n=== HTML LEN ===", serverHtml.length);

await browser.close();
