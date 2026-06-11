// E2E test for the "add to cart" flow the user reported as crashing
// 1. Open page, inject auth token
// 2. Open chat popup
// 3. Ask LLM to add an item to cart (POST /api/products/cart - protected API)
// 4. LLM should call the FRONTEND httpRequest tool which triggers a confirm dialog
// 5. Click the "Confirm" button in the dialog
// 6. Verify cart was actually updated via curl with the user's JWT
import { chromium } from "playwright";

const FRONTEND = "http://localhost:4000";
const BACKEND = "http://localhost:8080";
const SHOT_BEFORE = "/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/e2e-screenshots/cart-before.png";
const SHOT_AFTER = "/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo/e2e-screenshots/cart-after.png";

const token = Buffer.from("user1:password1").toString("base64");

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 1100 } });
const page = await ctx.newPage();

const consoleMsgs = [];
const pageErrors = [];

page.on("console", msg => {
  const entry = `[${msg.type()}] ${msg.text()}`;
  consoleMsgs.push(entry);
  if (msg.type() === "error") console.log("  CONSOLE ERROR:", msg.text());
});
page.on("pageerror", err => {
  pageErrors.push(err.message);
  console.log("  PAGEERROR:", err.message);
});

console.log("=== Cart E2E Test ===\n");

// Step 1: Navigate & inject auth token
console.log("[Step 1] Navigate + inject auth token");
await page.goto(FRONTEND, { waitUntil: "domcontentloaded", timeout: 30000 });
await page.waitForTimeout(2000);
await page.evaluate(t => {
  localStorage.setItem("auth_token", t);
  localStorage.setItem("auth_username", "user1");
}, token);
await page.reload({ waitUntil: "domcontentloaded" });
await page.waitForTimeout(3000);
console.log("  ✓ Auth token injected");

// Step 2: Open chat popup
console.log("[Step 2] Open chat popup");
const openBtn = page.getByRole("button", { name: /open chat/i });
await openBtn.first().click({ timeout: 10000 });
await page.waitForTimeout(2000);
console.log("  ✓ Chat popup open");

// Step 3: Send "add to cart" message
console.log('[Step 3] Send "add iPhone 15 to my cart"');
const textbox = page.getByRole("textbox").last();
await textbox.fill("把 iPhone 15 加到我的购物车");
await textbox.press("Enter");
console.log("  ✓ Message sent");

// Step 4: Wait for confirm dialog OR product response
console.log("[Step 4] Wait for confirm dialog (max 60s)...");
let dialogAppeared = false;
for (let i = 0; i < 60; i++) {
  await page.waitForTimeout(1000);
  // Look for confirm dialog buttons
  const confirmBtn = await page.getByRole("button", { name: /(确认|confirm|确定|执行|add|加入)/i }).count();
  if (confirmBtn > 0) {
    console.log(`  ✓ Confirm dialog appeared at ${i + 1}s`);
    dialogAppeared = true;
    break;
  }
  // Also check for any cart-related text
  const html = await page.content();
  if (html.includes("购物车") && (html.includes("已添加") || html.includes("成功") || html.includes("已加入"))) {
    console.log(`  ✓ Cart response detected at ${i + 1}s`);
    dialogAppeared = true;
    break;
  }
}

await page.screenshot({ path: SHOT_BEFORE });
console.log(`  Screenshot: ${SHOT_BEFORE}`);

// Step 5: If confirm dialog appeared, click it
if (dialogAppeared) {
  console.log("[Step 5] Click confirm button");
  try {
    // Try multiple possible confirm button labels
    const buttons = await page.getByRole("button").all();
    for (const btn of buttons) {
      const text = await btn.textContent();
      if (text && /^(确认|confirm|确定|执行|add to cart|加入购物车|✓|yes|ok)/i.test(text.trim())) {
        await btn.click({ timeout: 3000 });
        console.log(`  ✓ Clicked: "${text.trim()}"`);
        break;
      }
    }
    await page.waitForTimeout(5000);
  } catch (e) {
    console.log("  ⚠️ Could not click confirm:", e.message);
  }
} else {
  console.log("  ⚠️ No confirm dialog appeared in 60s");
}

await page.screenshot({ path: SHOT_AFTER, fullPage: true });
console.log(`  Screenshot: ${SHOT_AFTER}`);

// Step 6: Verify cart via curl
console.log("[Step 6] Verify cart via curl");
const curlRes = await fetch(`${BACKEND}/api/products/cart`, {
  headers: { Authorization: `Bearer ${token}` },
});
const cartData = await curlRes.json();
console.log(`  Cart HTTP: ${curlRes.status}`);
console.log(`  Cart items: ${JSON.stringify(cartData).slice(0, 200)}`);

const hasIphone = JSON.stringify(cartData).includes("iPhone");
console.log(`  iPhone in cart: ${hasIphone ? "YES ✓" : "NO ✗"}`);

// Final summary
console.log("\n=== RESULTS ===");
console.log(`Confirm dialog appeared: ${dialogAppeared ? "YES" : "NO"}`);
console.log(`Page errors: ${pageErrors.length}`);
if (pageErrors.length > 0) {
  pageErrors.forEach(e => console.log(`  - ${e}`));
}
console.log(`iPhone in cart (DB): ${hasIphone ? "YES" : "NO"}`);
console.log(`RESULT: ${pageErrors.length === 0 && hasIphone ? "PASS" : "PARTIAL"}`);

await browser.close();
