import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
async function shot(n) { await page.screenshot({path:`/tmp/${n}.png`,fullPage:true}); }
async function waitStable(label, ms=120000) {
  const t0=Date.now(); let prev='',cnt=0;
  while(Date.now()-t0<ms){
    await page.waitForTimeout(3000);
    const txt=await page.textContent('body');
    if(txt===prev){cnt++;if(cnt>=2)return txt;}
    else{cnt=0;prev=txt;}
  }
  return prev;
}

async function login() {
  await page.goto('http://localhost:4001',{waitUntil:'networkidle',timeout:30000});
  const loginBtn = page.locator('button:has-text("登录")');
  if (await loginBtn.isVisible({timeout:3000}).catch(()=>false)) {
    await loginBtn.click();
    await page.waitForTimeout(1500);
    await page.locator('input[placeholder*="user1"]').fill('admin');
    await page.locator('input[placeholder*="password"]').fill('admin123');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(2000);
  }
  // Verify logged in
  const token = await page.evaluate(()=>localStorage.getItem('auth_token'));
  if (!token) throw new Error('Login failed - no token');
}

async function openChat() {
  await page.locator('[data-testid="copilot-chat-toggle"]').click({timeout:5000});
  await page.waitForTimeout(1500);
  const ta = page.locator('textarea').first();
  await ta.waitFor({state:'visible',timeout:10000});
  return ta;
}

async function sendMessage(ta, msg) {
  await ta.fill(msg);
  await ta.press('Enter');
}

// Monitor network
const failed = [];
page.on('response', r => {
  if (r.status() >= 400 && r.url().includes('localhost:8080/api/'))
    failed.push({url: r.url().replace('http://localhost:8080',''), status: r.status()});
});

// Track console logs for URL validation
const valLogs = [];
page.on('console', msg => {
  if (msg.text().includes('[validateUrl]') || msg.text().includes('URL validation'))
    valLogs.push(msg.text());
});

try {
  await login();
  const ta = await openChat();

  // Step 1: Ask products
  console.log('=== 查商品 ===');
  await sendMessage(ta, '我有什么商品可以买？');
  const t1 = await waitStable('products');
  const hasProducts = t1.includes('iPhone') || t1.includes('Sony');
  console.log(`Products: ${hasProducts?'✅':'❌'}`);

  // Step 2: Add to cart
  console.log('=== 加购物车 ===');
  const ta2 = page.locator('textarea').first();
  await sendMessage(ta2, '将 iPhone 15 放入我的购物车');
  const t2 = await waitStable('add to cart');
  await shot('result');
  
  // Check for confirmation dialog element (more reliable than body text matching)
  const confirmBtn = page.locator('button:has-text("确认执行")').first();
  const hasConfirm = await confirmBtn.isVisible({timeout:30000}).catch(()=>false);
  const has403 = t2.includes('403');
  
  console.log(`Confirm dialog: ${hasConfirm?'✅':'❌'}`);

  // Step 3: Confirm if available
  let confirmed = false;
  if (hasConfirm) {
    await confirmBtn.click();
    await waitStable('confirm');
    confirmed = true;
  }

  // Step 4: Check cart
  console.log('=== 查购物车 ===');
  const ta4 = page.locator('textarea').first();
  await sendMessage(ta4, '查询我的购物车');
  const t4 = await waitStable('cart');
  const cartOk = t4.includes('iPhone');
  console.log(`Cart: ${cartOk?'✅':'❌'}`);

  // Summary
  console.log(`\nResult: products=${hasProducts?'Y':'N'} addToCart=${confirmed?'Y':'N'} cart=${cartOk?'Y':'N'} 403=${failed.length>0?'Y':'N'}`);
  if (failed.length > 0) console.log('403s:', failed.map(r=>r.url).join(', '));
  if (valLogs.length > 0) console.log('Validation:', valLogs.join(' | '));

} catch(e) {
  console.error('Error:', e.message);
  await shot('error');
} finally {
  await browser.close();
}
