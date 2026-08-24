import assert from "node:assert/strict";
import { launchChromium } from "./browser.mjs";

const baseUrl = process.env.TRADITIONAL_UI_URL || "http://localhost:8080";
const timeoutMs = Number(process.env.TRADITIONAL_UI_E2E_TIMEOUT_MS || 180000);

const browser = await launchChromium();
const context = await browser.newContext();
const page = await context.newPage();
const failures = [];

page.on("console", (message) => {
  if (message.type() === "error") {
    failures.push(`browser console error: ${message.text()}`);
  }
});

try {
  console.log(`[e2e] opening ${baseUrl}`);
  await page.goto(baseUrl, { waitUntil: "domcontentloaded", timeout: 30000 });
  await page.waitForLoadState("networkidle");

  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: "networkidle" });

  await page.locator("h1").filter({ hasText: "AI 购物助手" }).waitFor({ state: "visible" });
  await page.locator("#userInput").waitFor({ state: "visible" });
  await page.locator("#sendBtn").waitFor({ state: "visible" });
  assert.match(await page.locator("#messages").innerText(), /智能购物助手/);
  assert.equal(await page.locator("#loginBtn").isVisible(), true);
  console.log("[e2e] embedded page loaded");

  await page.locator("#loginBtn").click();
  await page.locator("#loginModal").waitFor({ state: "visible" });
  await page.locator("#username").fill("user1");
  await page.locator("#password").fill("password1");

  const verifyResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/auth/verify") &&
      response.request().method() === "GET",
    { timeout: 30000 },
  );
  await page.locator("#loginModal .submit-btn").click();
  const verify = await verifyResponse;
  assert.equal(verify.status(), 200);
  await page.locator("#userInfo").waitFor({ state: "visible" });
  assert.equal(await page.locator("#displayName").textContent(), "user1");
  console.log("[e2e] login completed");

  const chatResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/chat/text") &&
      response.request().method() === "POST",
    { timeout: timeoutMs },
  );
  await page.locator("#userInput").fill(
    "请查询所有商品列表，使用合适的 Skill 和只读 API，直接返回商品结果。",
  );
  await page.locator("#sendBtn").click();

  const chatResponse = await chatResponsePromise;
  assert.equal(chatResponse.status(), 200);
  console.log("[e2e] POST /api/chat/text returned JSON");

  await page.waitForFunction(
    () =>
      [...document.querySelectorAll("#messages .message.assistant")]
        .map((element) => element.textContent || "")
        .some(
          (text) =>
            text.includes("商品") ||
            text.includes("iPhone") ||
            text.includes("Sony") ||
            text.includes("MacBook"),
        ),
    undefined,
    { timeout: timeoutMs },
  );

  const assistantText = await page.locator("#messages .message.assistant").last().innerText();
  assert.doesNotMatch(assistantText, /抱歉，服务出问题了/);
  assert.match(assistantText, /商品|iPhone|Sony|MacBook/);
  console.log("[e2e] assistant rendered a product result");

  if (failures.length > 0) {
    throw new Error(failures.join("\n"));
  }

  console.log(
    JSON.stringify(
      {
        suite: "traditional-ui-e2e",
        baseUrl,
        assertions: [
          "embedded page loaded",
          "login completed through /api/auth/verify",
          "real /api/chat/text returned JSON",
          "assistant rendered product result in DOM",
        ],
      },
      null,
      2,
    ),
  );
} finally {
  await context.close();
  await browser.close();
}
