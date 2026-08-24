import assert from "node:assert/strict";
import { launchChromium } from "./browser.mjs";

const baseUrl = process.env.TRADITIONAL_UI_URL || "http://localhost:8080";
const timeoutMs = Number(process.env.TRADITIONAL_UI_E2E_TIMEOUT_MS || 240000);
const user1Token = Buffer.from("user1:password1", "utf8").toString("base64");
const user2Token = Buffer.from("user2:password2", "utf8").toString("base64");

const requests = [];
const consoleErrors = [];

function authorization(token) {
  return { Authorization: `Bearer ${token}` };
}

async function readJson(response) {
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = { raw: text };
  }
  return { response, body };
}

async function clearCart(token) {
  const { response, body } = await readJson(
    await fetch(`${baseUrl}/api/products/checkout`, {
      method: "POST",
      headers: authorization(token),
    }),
  );
  assert.equal(response.status, 200);
  assert.equal(typeof body.success, "boolean");
  const cart = await getCart(token);
  assert.equal(cart.itemCount, 0);
}

async function getCart(token) {
  const { response, body } = await readJson(
    await fetch(`${baseUrl}/api/products/cart`, {
      headers: authorization(token),
    }),
  );
  assert.equal(response.status, 200);
  return body;
}

async function waitForChatResponse(page) {
  return page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/chat/text") &&
      response.request().method() === "POST",
    { timeout: timeoutMs },
  );
}

async function askForMutation(page) {
  const responsePromise = waitForChatResponse(page);
  await page.locator("#userInput").fill(
    "请使用合适的 Skill，准备把商品 ID 为 3 的 Sony WH-1000XM5 加入当前用户购物车。"
      + "只能先生成待确认写操作，不要假设已经成功。",
  );
  await page.locator("#sendBtn").click();
  const response = await responsePromise;
  assert.equal(response.status(), 200);
  const body = await response.json();
  assert.match(body.response, /等待用户确认|确认后/);
  assert.equal(body.confirmation?.method, "POST");
  assert.equal(body.confirmation?.url, "/api/products/cart");
  assert.equal(String(body.confirmation?.queryParams?.productId), "3");
  await page.locator("#messages .confirm-btn").last().waitFor({
    state: "visible",
    timeout: timeoutMs,
  });
  return body;
}

async function waitForAssistantResult(page, assistantCountBeforeConfirm) {
  await page.waitForFunction(
    (previousCount) => {
      const messages = [
        ...document.querySelectorAll("#messages .message.assistant"),
      ];
      if (messages.length <= previousCount) {
        return false;
      }
      const last = messages.at(-1);
      const text = last?.textContent || "";
      return (
        !last?.querySelector(".loading") &&
        /API 执行|已添加|购物车|操作成功|操作失败|✅|❌/.test(text)
      );
    },
    assistantCountBeforeConfirm,
    { timeout: timeoutMs },
  );
  return page.locator("#messages .message.assistant").last().innerText();
}

await clearCart(user1Token);
await clearCart(user2Token);

const browser = await launchChromium();
const context = await browser.newContext();
const page = await context.newPage();

page.on("console", (message) => {
  if (message.type() === "error") {
    consoleErrors.push(message.text());
  }
});
page.on("request", (request) => {
  requests.push({
    method: request.method(),
    url: request.url(),
    authorization: request.headers().authorization,
  });
});

try {
  await page.goto(baseUrl, { waitUntil: "networkidle", timeout: 30000 });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: "networkidle", timeout: 30000 });

  await page.locator("#loginBtn").click();
  await page.locator("#username").fill("user1");
  await page.locator("#password").fill("password1");
  const verifyPromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/auth/verify") &&
      response.request().method() === "GET",
    { timeout: 30000 },
  );
  await page.locator("#loginModal .submit-btn").click();
  const verifyResponse = await verifyPromise;
  assert.equal(verifyResponse.status(), 200);
  await page.locator("#userInfo").waitFor({ state: "visible" });

  const apiIndexBeforeCancel = requests.filter(
    (request) =>
      request.method === "GET" &&
      request.url.endsWith("/api/skills/api-index"),
  ).length;
  const legacyApiIndexBeforeCancel = requests.filter(
    (request) =>
      request.method === "GET" &&
      request.url.endsWith("/api/agui/skills/api-index"),
  ).length;
  const mutationBeforeCancel = requests.filter(
    (request) =>
      request.method === "POST" &&
      request.url.includes("/api/products/cart?"),
  ).length;
  const explainBeforeCancel = requests.filter(
    (request) => request.method === "POST" && request.url.endsWith("/api/explain-result"),
  ).length;

  await askForMutation(page);
  const apiIndexAfterConfirmation = requests.filter(
    (request) =>
      request.method === "GET" &&
      request.url.endsWith("/api/skills/api-index"),
  ).length;
  assert.ok(apiIndexAfterConfirmation > apiIndexBeforeCancel);
  assert.equal(
    requests.filter(
      (request) =>
        request.method === "GET" &&
        request.url.endsWith("/api/agui/skills/api-index"),
    ).length,
    legacyApiIndexBeforeCancel,
  );

  await page.locator("#messages .cancel-btn").last().click();
  assert.equal(
    requests.filter(
      (request) =>
        request.method === "POST" &&
        request.url.includes("/api/products/cart?"),
    ).length,
    mutationBeforeCancel,
  );
  assert.equal(
    requests.filter(
      (request) =>
        request.method === "POST" && request.url.endsWith("/api/explain-result"),
    ).length,
    explainBeforeCancel,
  );
  assert.match(await page.locator("#messages").innerText(), /已取消该操作/);

  await page.evaluate(
    (token) => localStorage.setItem("auth_token", token),
    user2Token,
  );
  const mutationResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes("/api/products/cart?") &&
      response.request().method() === "POST",
    { timeout: timeoutMs },
  );
  const explainResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/explain-result") &&
      response.request().method() === "POST",
    { timeout: timeoutMs },
  );
  await askForMutation(page);
  const apiIndexBeforeConfirm = requests.filter(
    (request) =>
      request.method === "GET" &&
      request.url.endsWith("/api/skills/api-index"),
  ).length;
  const assistantCountBeforeConfirm = await page
    .locator("#messages .message.assistant")
    .count();
  await page.locator("#messages .confirm-btn").last().click();

  const mutationResponse = await mutationResponsePromise;
  assert.equal(mutationResponse.status(), 200);
  const explainResponse = await explainResponsePromise;
  assert.equal(explainResponse.status(), 200);
  assert.ok(
    requests.filter(
      (request) =>
        request.method === "GET" &&
        request.url.endsWith("/api/skills/api-index"),
    ).length > apiIndexBeforeConfirm,
  );
  assert.equal(
    requests.filter(
      (request) =>
        request.method === "GET" &&
        request.url.endsWith("/api/agui/skills/api-index"),
    ).length,
    legacyApiIndexBeforeCancel,
  );

  const mutationRequest = requests.findLast(
    (request) =>
      request.method === "POST" &&
      request.url.includes("/api/products/cart?"),
  );
  assert.ok(mutationRequest);
  assert.match(mutationRequest.url, /\/api\/products\/cart\?productId=3$/);
  assert.equal(mutationRequest.authorization, `Bearer ${user2Token}`);

  const resultText = await waitForAssistantResult(
    page,
    assistantCountBeforeConfirm,
  );
  assert.doesNotMatch(resultText, /抱歉，服务出问题了/);
  assert.match(resultText, /✅|成功|已添加|购物车/);

  const user2Cart = await getCart(user2Token);
  assert.equal(user2Cart.success, true);
  assert.equal(user2Cart.itemCount, 1);
  assert.equal(user2Cart.items?.[0]?.id, 3);

  await clearCart(user2Token);
  const cleanedCart = await getCart(user2Token);
  assert.equal(cleanedCart.itemCount, 0);

  assert.deepEqual(consoleErrors, []);
  console.log(
    JSON.stringify(
      {
        suite: "traditional-ui-mutation-e2e",
        baseUrl,
        assertions: [
          "real loadSkill/buildHttpRequest produced a confirmation button",
          "cancel sent no business POST or result explanation request",
          "confirm sent POST /api/products/cart?productId=3",
          "confirm used the latest localStorage token",
          "result explanation rendered in the DOM",
          "cart was verified and cleaned through protected APIs",
        ],
      },
      null,
      2,
    ),
  );
} finally {
  await context.close();
  await browser.close();
  for (const token of [user1Token, user2Token]) {
    try {
      await clearCart(token);
    } catch (error) {
      console.error(`[e2e] failed to clean cart: ${error.message}`);
    }
  }
}
