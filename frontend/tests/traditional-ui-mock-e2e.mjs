import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { chromium } from "playwright";

const html = await readFile(
  new URL("../../src/main/resources/static/index.html", import.meta.url),
  "utf8",
);
const validToken = Buffer.from("user1:password1", "utf8").toString("base64");
const requests = [];

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
  if (request.url === "/api/auth/verify") {
    const valid = request.headers.authorization === `Bearer ${validToken}`;
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
  if (request.url === "/api/chat/text") {
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(
      JSON.stringify({
        response: "已找到 5 件商品，包括 iPhone 15 和 Sony WH-1000XM5。",
      }),
    );
    return;
  }

  response.writeHead(404, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: "not found" }));
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
const baseUrl = `http://127.0.0.1:${address.port}`;

const browser = await chromium.launch({ headless: true });
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
} finally {
  await context.close();
  await browser.close();
  await new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}

console.log("traditional-ui-mock-e2e: passed");
