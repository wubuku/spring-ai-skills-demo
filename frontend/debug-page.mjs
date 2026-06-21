import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto('http://localhost:4001', { waitUntil: 'networkidle', timeout: 30000 });

// Login
await page.locator('button:has-text("登录")').click({ timeout: 5000 });
await page.waitForTimeout(500);
await page.fill('input[placeholder*="user1"]', 'admin');
await page.fill('input[placeholder*="password"]', 'admin123');
await page.click('button[type="submit"]');
await page.waitForTimeout(2000);

// Get page HTML structure
const html = await page.evaluate(() => {
  return document.body.innerHTML.substring(0, 5000);
});
console.log('=== Page HTML (first 5000 chars) ===');
console.log(html);

// Try to find copilot elements
const copilotElements = await page.evaluate(() => {
  const els = document.querySelectorAll('[class*="copilot"], [class*="Copilot"], [data-testid*="copilot"]');
  return Array.from(els).map(el => ({
    tag: el.tagName,
    class: el.className?.substring?.(0, 100),
    id: el.id,
    visible: el.offsetParent !== null
  }));
});
console.log('\n=== Copilot elements ===');
console.log(JSON.stringify(copilotElements, null, 2));

await browser.close();
