import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { launchChromium } from "./browser.mjs";

const html = await readFile(
  new URL("../../src/main/resources/static/index.html", import.meta.url),
  "utf8",
);
const validToken = Buffer.from("user1:password1", "utf8").toString("base64");
const refreshedToken = Buffer.from("user2:password2", "utf8").toString("base64");
const requests = [];
let mutationAttempt = 0;

const server = createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  const body = Buffer.concat(chunks).toString("utf8");
  requests.push({
    method: request.method,
    url: request.url,
    authorization: request.headers.authorization,
    body,
  });

  if (request.url === "/" || request.url === "/index.html") {
    response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    response.end(html);
    return;
  }
  if (request.url === "/favicon.ico") {
    response.writeHead(204);
    response.end();
    return;
  }
  if (request.url === "/api/auth/verify") {
    const valid = [
      `Bearer ${validToken}`,
      `Bearer ${refreshedToken}`,
    ].includes(request.headers.authorization);
    response.writeHead(valid ? 200 : 401, {
      "content-type": "application/json; charset=utf-8",
    });
    response.end(
      JSON.stringify(
        valid
          ? { valid: true, username: "user1", displayName: "张三" }
          : { valid: false },
      ),
    );
    return;
  }
  if (request.url === "/api/agui/skills/api-index") {
    response.writeHead(200, {
      "content-type": "application/json; charset=utf-8",
    });
    response.end(
      JSON.stringify({
        "GET /api/products": {
          skillName: "search-products",
          path: "/api/products",
          method: "GET",
          description: "搜索商品",
        },
        "POST /api/products/cart": {
          skillName: "add-to-cart",
          path: "/api/products/cart",
          method: "POST",
          description: "加入购物车",
        },
      }),
    );
    return;
  }
  if (request.url === "/api/chat/text") {
    const chatBody = JSON.parse(body || "{}");
    if (chatBody.query?.includes("非法确认")) {
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(
        JSON.stringify({
          response: "模型返回了一个未知写操作。",
          confirmation: {
            method: "POST",
            url: "/api/unknown",
            queryParams: {},
            body: {},
          },
        }),
      );
      return;
    }
    if (chatBody.query?.includes("购物车")) {
      mutationAttempt += 1;
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(
        JSON.stringify({
          response: "已准备写操作，等待用户确认。",
          confirmation: {
            method: "POST",
            url: "/api/products/cart",
            queryParams: { productId: "3" },
            body: {},
          },
        }),
      );
      return;
    }
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(
      JSON.stringify({
        response: "已找到 5 件商品，包括 iPhone 15 和 Sony WH-1000XM5。",
      }),
    );
    return;
  }
  if (request.url === "/api/products/cart?productId=3") {
    const status = mutationAttempt >= 3 ? 409 : 200;
    response.writeHead(status, {
      "content-type": "application/json; charset=utf-8",
    });
    response.end(
      JSON.stringify(
        status === 200
          ? { success: true, message: "已添加到购物车", cartSize: 1 }
          : { success: false, message: "库存冲突" },
      ),
    );
    return;
  }
  if (request.url === "/api/explain-result") {
    const explainBody = JSON.parse(body || "{}");
    const failed = Number(explainBody.statusCode) >= 400;
    response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
    response.end(
      failed
        ? "❌ API 执行失败：库存冲突。"
        : "✅ API 执行成功：商品已加入购物车。",
    );
    return;
  }

  response.writeHead(404, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: "not found" }));
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
const baseUrl = `http://127.0.0.1:${address.port}`;

const browser = await launchChromium();
const context = await browser.newContext();
const page = await context.newPage();
const consoleErrors = [];
page.on("console", (message) => {
  if (message.type() === "error") {
    consoleErrors.push(message.text());
  }
});

try {
  await page.goto(baseUrl, { waitUntil: "networkidle" });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: "networkidle" });

  await page.locator("#loginBtn").click();
  await page.locator("#username").fill("user1");
  await page.locator("#password").fill("password1");
  await page.locator("#loginModal .submit-btn").click();
  await page.locator("#userInfo").waitFor({ state: "visible" });

  await page.locator("#userInput").fill("请列出所有商品");
  await page.locator("#sendBtn").click();
  await page.locator("#messages .message.assistant").last().waitFor({ state: "visible" });
  await page.waitForFunction(() =>
    [...document.querySelectorAll("#messages .message.assistant")]
      .some((element) => element.textContent?.includes("Sony WH-1000XM5")),
  );

  const verifyRequest = requests.find(
    (request) => request.method === "GET" && request.url === "/api/auth/verify",
  );
  assert.equal(verifyRequest.authorization, `Bearer ${validToken}`);

  const chatRequest = requests.find(
    (request) => request.method === "POST" && request.url === "/api/chat/text",
  );
  assert.ok(chatRequest);
  const chatBody = JSON.parse(chatRequest.body);
  assert.equal(chatBody.query, "请列出所有商品");
  assert.match(chatBody.conversationId, /^[A-Za-z0-9._:-]{1,128}$/);
  assert.notEqual(chatBody.conversationId, "default");
  assert.equal(
    await page.evaluate(() => localStorage.getItem("conversation_id")),
    chatBody.conversationId,
  );
  assert.deepEqual(consoleErrors, []);

  const apiIndexRequests = () =>
    requests.filter(
      (request) =>
        request.method === "GET" && request.url === "/api/agui/skills/api-index",
    );
  const mutationRequests = () =>
    requests.filter(
      (request) =>
        request.method === "POST" && request.url === "/api/products/cart?productId=3",
    );
  const explainRequests = () =>
    requests.filter(
      (request) =>
        request.method === "POST" && request.url === "/api/explain-result",
    );

  await page.locator("#userInput").fill("请把商品 3 加入购物车");
  await page.locator("#sendBtn").click();
  await page.locator("#messages .confirm-btn").waitFor({ state: "visible" });
  const mutationCountBeforeCancel = mutationRequests().length;
  const explainCountBeforeCancel = explainRequests().length;
  await page.locator("#messages .cancel-btn").last().click();
  assert.equal(mutationRequests().length, mutationCountBeforeCancel);
  assert.equal(explainRequests().length, explainCountBeforeCancel);
  assert.match(await page.locator("#messages").innerText(), /已取消该操作/);

  await page.evaluate((token) => localStorage.setItem("auth_token", token), refreshedToken);
  await page.locator("#userInput").fill("请把商品 3 加入购物车");
  await page.locator("#sendBtn").click();
  await page.locator("#messages .confirm-btn").waitFor({ state: "visible" });
  await page.locator("#messages .confirm-btn").last().click();
  await page.waitForFunction(() =>
    [...document.querySelectorAll("#messages .message.assistant")]
      .some((element) => element.textContent?.includes("API 执行成功")),
  );
  const confirmedRequest = mutationRequests().at(-1);
  assert.ok(confirmedRequest);
  assert.equal(confirmedRequest.authorization, `Bearer ${refreshedToken}`);
  assert.equal(apiIndexRequests().length >= 1, true);
  assert.equal(explainRequests().length, 1);

  await page.locator("#userInput").fill("请把商品 3 加入购物车");
  await page.locator("#sendBtn").click();
  await page.locator("#messages .confirm-btn").waitFor({ state: "visible" });
  await page.locator("#messages .confirm-btn").last().click();
  await page.waitForFunction(() =>
    [...document.querySelectorAll("#messages .message.assistant")]
      .some((element) => element.textContent?.includes("API 执行失败")),
  );
  const lastAssistant = await page.locator("#messages .message.assistant").last().innerText();
  assert.match(lastAssistant, /❌/);
  assert.doesNotMatch(lastAssistant, /✅ 操作结果：❌/);

  await page.locator("#userInput").fill("非法确认");
  await page.locator("#sendBtn").click();
  await page.waitForFunction(() =>
    [...document.querySelectorAll("#messages .message.assistant")]
      .some((element) => element.textContent?.includes("未知写操作")),
  );
  assert.equal(await page.locator("#messages .confirm-btn").count(), 0);
  assert.equal(mutationRequests().length, 2);
} finally {
  await context.close();
  await browser.close();
  await new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}

console.log("traditional-ui-mock-e2e: passed");
