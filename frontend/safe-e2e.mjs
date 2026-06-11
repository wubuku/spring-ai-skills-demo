/**
 * Aggressive E2E: open CopilotPopup, send message, poll DOM inside popup
 * 120s timeout, also try the assistant-message selector specifically
 */
import { chromium } from "playwright";
import fs from "fs";

const FRONTEND = "http://localhost:4000";
const SHOTS = "/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/e2e-screenshots";

async function run() {
  console.log("=== Aggressive E2E (open popup, poll assistant message) ===\n");
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on("console", m => { if (m.type() === "error") consoleErrors.push(m.text()); });

  await page.goto(FRONTEND, { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.waitForTimeout(3000);

  const token = Buffer.from("user1:password1").toString("base64");
  await page.evaluate(t => {
    localStorage.setItem("auth_token", t);
    localStorage.setItem("auth_username", "user1");
  }, token);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.waitForTimeout(3000);

  const openBtn = page.getByRole("button", { name: /open chat/i });
  if (await openBtn.count() > 0) { await openBtn.click(); await page.waitForTimeout(2000); }

  await page.screenshot({ path: `${SHOTS}/safe-01.png` });

  const ta = page.locator("textarea").first();
  await ta.click({ force: true });
  await page.waitForTimeout(500);
  await ta.fill("我可以买什么商品？");
  await page.waitForTimeout(500);
  await ta.press("Enter");

  console.log("Sent message. Polling 120s for any text response...");
  const start = Date.now();
  let resp = null;
  while (Date.now() - start < 120_000) {
    await page.waitForTimeout(2000);
    const elapsed = Math.floor((Date.now() - start) / 1000);
    // Try multiple selectors
    const body = await page.innerText("body").catch(() => "");
    const popup = await page.locator("[class*='copilot'], [class*='chat'], copilot-popup, [data-testid*='copilot']")
                          .allInnerTexts().catch(() => []);
    const allText = body + "\n--POPUP--\n" + popup.join("\n");
    // Match either product names OR generic LLM response
    const m = allText.match(/iPhone|MacBook|MatePad|Sony|小米电视|华为|可以购买|商品列表|以下是|您可以/);
    if (m) { resp = m[0]; console.log(`  ✓ Match at ${elapsed}s: ${m[0]}`); break; }
    if (elapsed % 10 === 0) console.log(`  ... ${elapsed}s, body len=${body.length}, popup len=${popup.reduce((a,b)=>a+b.length,0)}`);
  }

  await page.screenshot({ path: `${SHOTS}/safe-02-final.png`, fullPage: true });
  await page.screenshot({ path: `${SHOTS}/safe-02-viewport.png` });

  const finalBody = await page.innerText("body");
  console.log("\n=== FINAL DOM TAIL (last 500 chars) ===");
  console.log(finalBody.slice(-500));
  console.log(`\nMatch: ${resp ? "YES ("+resp+")" : "NO"}`);
  console.log(`Console errors: ${consoleErrors.length}`);
  consoleErrors.slice(0, 3).forEach(e => console.log(`  - ${e.substring(0, 200)}`));
  await browser.close();
  process.exit(resp ? 0 : 1);
}

run().catch(e => { console.error("ERROR:", e); process.exit(1); });
