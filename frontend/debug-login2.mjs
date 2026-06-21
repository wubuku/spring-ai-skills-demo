import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto('http://localhost:4001', { waitUntil: 'networkidle', timeout: 30000 });

// Click login
await page.locator('button:has-text("登录")').click({ timeout: 5000 });
await page.waitForTimeout(2000); // wait longer for modal

// Check inputs now
const inputs = await page.locator('input, textarea').all();
for (const inp of inputs) {
  const visible = await inp.isVisible().catch(() => false);
  const placeholder = await inp.getAttribute('placeholder').catch(() => '');
  const type = await inp.getAttribute('type').catch(() => '');
  console.log(`Input: visible=${visible} type=${type} placeholder="${placeholder}"`);
}

await page.screenshot({ path: '/tmp/debug-login2.png', fullPage: true });
await browser.close();
