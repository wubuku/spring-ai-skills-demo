import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto('http://localhost:4001', { waitUntil: 'networkidle', timeout: 30000 });
console.log('Page title:', await page.title());

// Check what's visible
const buttons = await page.locator('button').all();
for (const b of buttons) {
  const text = await b.textContent().catch(() => '');
  const visible = await b.isVisible().catch(() => false);
  if (visible) console.log(`Button: "${text?.trim()?.substring(0, 50)}"`);
}

const inputs = await page.locator('input, textarea').all();
for (const inp of inputs) {
  const visible = await inp.isVisible().catch(() => false);
  const placeholder = await inp.getAttribute('placeholder').catch(() => '');
  const type = await inp.getAttribute('type').catch(() => '');
  if (visible) console.log(`Input: type=${type} placeholder="${placeholder}"`);
}

await page.screenshot({ path: '/tmp/debug-login.png', fullPage: true });
await browser.close();
