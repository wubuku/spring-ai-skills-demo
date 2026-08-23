import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { validateSkillApiUrl } from "../lib/api-index-validation.mjs";

const apiIndex = {
  "GET /api/products": {
    skillName: "search-products",
    path: "/api/products",
    method: "GET",
    description: "搜索商品",
  },
  "GET /api/products/{id}": {
    skillName: "get-product-detail",
    path: "/api/products/{id}",
    method: "GET",
    description: "查看详情",
  },
  "POST /api/products/cart": {
    skillName: "add-to-cart",
    path: "/api/products/cart",
    method: "POST",
    description: "加入购物车",
  },
};

assert.deepEqual(validateSkillApiUrl("GET", "/api/products/3", apiIndex), { valid: true });
assert.deepEqual(validateSkillApiUrl("GET", "/api/products?keyword=headset", apiIndex), { valid: true });
assert.equal(validateSkillApiUrl("POST", "/api/products/3", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "https://example.com/secret", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "javascript:/api/products", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "/api/products/3#fragment", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "/api/products/", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "/api/products/..", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "/api/products/%2e%2e", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "/api/products/%00", apiIndex).valid, false);
assert.equal(validateSkillApiUrl("GET", "api/products", apiIndex).valid, false);

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const moduleSource = await readFile(
  new URL("../lib/api-index-validation.mjs", import.meta.url),
  "utf8",
);
await page.setContent(`
  <main>
    <button id="check" type="button">检查 API</button>
    <output id="result" aria-live="polite"></output>
  </main>
`);
await page.addScriptTag({ content: moduleSource, type: "module" });
await page.waitForFunction(() => typeof window.__validateSkillApiUrl === "function");

let requested = false;
await page.route("**/api/agui/skills/api-index", async (route) => {
  requested = true;
  await route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(apiIndex),
  });
});
await page.evaluate(async () => {
  const response = await fetch("http://mock.local/api/agui/skills/api-index");
  const index = await response.json();
  const result = window.__validateSkillApiUrl("GET", "/api/products/3", index);
  document.querySelector("#result").textContent = result.valid ? "valid" : result.error;
});
assert.equal(requested, true);
await page.locator("#result").waitFor({ state: "visible" });
assert.equal(await page.locator("#result").textContent(), "valid");
assert.equal(await page.locator("#result").getAttribute("aria-live"), "polite");

await browser.close();
console.log("skills-url-mock: passed");
