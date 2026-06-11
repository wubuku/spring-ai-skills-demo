/**
 * Strict-but-safe E2E: do NOT intercept response.body() (causes Failed to fetch
 * with Next.js + undici SSE). Just send the message and assert DOM contains
 * product names (iPhone/MacBook/Sony) — these names only appear if the LLM
 * actually called httpRequest and got real data back from /api/products.
 */
import { chromium } from "playwright";
import fs from "fs";

const FRONTEND = "http://localhost:4000";
const SHOTS = "/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/e2e-screenshots";

async function run() {
  console.log("=== DOM-Only E2E (no SSE interception) ===\n");
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on("console", m => { if (m.type() === "error") consoleErrors.push(m.text()); });

  console.log("[1] Navigate");
  await page.goto(FRONTEND, { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.waitForTimeout(3000);

  console.log("[2] Inject auth token");
  const token = Buffer.from("user1:password1").toString("base64");
  await page.evaluate(t => {
    localStorage.setItem("auth_token", t);
    localStorage.setItem("auth_username", "user1");
  }, token);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.waitForTimeout(3000);

  console.log("[3] Open chat");
  const openBtn = page.getByRole("button", { name: /open chat/i });
  if (await openBtn.count() > 0) { await openBtn.click(); await page.waitForTimeout(1500); }

  await page.screenshot({ path: `${SHOTS}/m27-01-chat-open.png` });

  console.log("[4] Send: 我可以买什么商品？");
  const ta = page.locator("textarea").first();
  await ta.click({ force: true });
  await page.waitForTimeout(500);
  await ta.fill("我可以买什么商品？");
  await page.waitForTimeout(500);
  await ta.press("Enter");

  await page.screenshot({ path: `${SHOTS}/m27-02-sent.png` });

  console.log("[5] Wait up to 90s for product names in DOM...");
  const PRODUCT_KEYWORDS = ["iPhone", "MacBook", "MatePad", "Sony", "小米电视", "华为"];
  let found = null;
  let elapsed = 0;
  const start = Date.now();
  while (Date.now() - start < 90_000) {
    await page.waitForTimeout(3000);
    elapsed = Math.floor((Date.now() - start) / 1000);
    const text = await page.innerText("body");
    for (const k of PRODUCT_KEYWORDS) {
      if (text.includes(k)) { found = k; break; }
    }
    if (found) { console.log(`  ✓ "${found}" found at ${elapsed}s`); break; }
    if (elapsed % 10 === 0) console.log(`  ... ${elapsed}s`);
  }

  await page.screenshot({ path: `${SHOTS}/m27-03-final.png`, fullPage: true });
  await page.screenshot({ path: `${SHOTS}/m27-03-final-viewport.png` });

  console.log("\n=== RESULTS ===");
  console.log(`Product keyword in DOM: ${found ? "YES ("+found+")" : "NO"}`);
  console.log(`Console errors: ${consoleErrors.length}`);
  consoleErrors.slice(0, 5).forEach(e => console.log(`  - ${e.substring(0, 200)}`));
  const ok = !!found;
  console.log(`\nRESULT: ${ok ? "PASS" : "FAIL"}`);
  await browser.close();
  process.exit(ok ? 0 : 1);
}

run().catch(e => { console.error("ERROR:", e); process.exit(1); });
