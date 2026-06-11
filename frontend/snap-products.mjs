// One-off screenshot: wait for full product table to render, then capture
import { chromium } from "playwright";

const FRONTEND = "http://localhost:4000";
const OUT = "/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/e2e-screenshots/dedup-success.png";

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1100 } });
const page = await ctx.newPage();

await page.goto(FRONTEND, { waitUntil: "domcontentloaded", timeout: 30000 });
await page.waitForTimeout(2000);

const token = Buffer.from("user1:password1").toString("base64");
await page.evaluate(t => {
  localStorage.setItem("auth_token", t);
  localStorage.setItem("auth_username", "user1");
}, token);
await page.reload({ waitUntil: "domcontentloaded" });
await page.waitForTimeout(3000);

const openBtn = page.getByRole("button", { name: /open chat/i });
await openBtn.first().click({ timeout: 10000 });
await page.waitForTimeout(2000);

const textbox = page.getByRole("textbox").last();
await textbox.fill("我可以买什么商品？");
await textbox.press("Enter");

// Poll for the actual product table to appear (not just thinking)
console.log("Waiting for iPhone 15 in the response...");
for (let i = 0; i < 30; i++) {
  await page.waitForTimeout(1000);
  const dom = await page.content();
  if (dom.includes("iPhone 15") && dom.includes("MacBook Air M3")) {
    console.log(`✓ Found product table at ${i + 1}s`);
    break;
  }
}

// Give the streaming an extra second to settle
await page.waitForTimeout(1500);

await page.screenshot({ path: OUT, fullPage: false });
console.log("Screenshot saved:", OUT);

await browser.close();
