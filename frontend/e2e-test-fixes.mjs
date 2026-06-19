/**
 * E2E 测试：验证三个 UI 修复
 * 1. [TOOL_CALL] 方括号标签被过滤
 * 2. LLM 思考过程不以纯文本泄漏（要么在 <think> 标签内折叠，要么不输出）
 * 3. 确认对话框 UI 重构
 *
 * 使用 Playwright/DOM 断言执行。不要使用截图验证。
 */

// 等待页面加载完成
async function waitForReady(page) {
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
}

// 打开聊天窗口
async function openChat(page) {
  // 点击 CopilotKit 的聊天按钮
  const chatButton = page.locator('[data-copilotkit-trigger]').or(
    page.locator('button:has-text("Ask"), button:has-text("AI"), button:has-text("Copilot"), button:has-text("助手")')
  ).first();

  if (await chatButton.isVisible({ timeout: 5000 }).catch(() => false)) {
    await chatButton.click();
    await page.waitForTimeout(1000);
  }
}

// 发送消息
async function sendMessage(page, text) {
  const input = page.locator('textarea, input[type="text"]').last();
  await input.fill(text);
  await input.press('Enter');
}

// 收集所有文本消息内容
async function collectMessages(page, timeoutMs = 60000) {
  const messages = [];
  const startTime = Date.now();

  while (Date.now() - startTime < timeoutMs) {
    const textElements = await page.locator('[data-message-role="assistant"] .copilotkit-message, [data-message-role="assistant"]').allTextContents();
    for (const text of textElements) {
      if (text && !messages.includes(text)) {
        messages.push(text);
      }
    }

    // 检查是否还在加载
    const loading = await page.locator('.copilotkit-loading, [data-loading="true"]').isVisible().catch(() => false);
    if (!loading && messages.length > 0) {
      // 等一下看还有没有新消息
      await page.waitForTimeout(3000);
      const newMessages = await page.locator('[data-message-role="assistant"] .copilotkit-message, [data-message-role="assistant"]').allTextContents();
      let hasNew = false;
      for (const text of newMessages) {
        if (text && !messages.includes(text)) {
          messages.push(text);
          hasNew = true;
        }
      }
      if (!hasNew) break;
    }

    await page.waitForTimeout(1000);
  }

  return messages;
}

// 检查是否有泄漏的标签
function checkForLeakedTags(messages) {
  const leaks = [];

  for (const msg of messages) {
    // 检查 [TOOL_CALL] 标签
    if (/\[\s*TOOL_CALL\s*\]/i.test(msg)) {
      leaks.push(`[TOOL_CALL] bracket tag found in: "${msg.substring(0, 100)}..."`);
    }
    if (/\[\s*\/\s*TOOL_CALL\s*\]/i.test(msg)) {
      leaks.push(`[/TOOL_CALL] bracket tag found in: "${msg.substring(0, 100)}..."`);
    }

    // 检查 <parameter> / <invoke> / <function_calls> 标签
    if (/<\s*(parameter|invoke|function_calls|antml_call)\b/i.test(msg)) {
      leaks.push(`XML tool call tag found in: "${msg.substring(0, 100)}..."`);
    }

    // 检查 LLM "内心独白" 泄漏（无标签的推理文本）
    const monologuePatterns = [
      /^用户想要将/,
      /^让我检查一下/,
      /^让我尝试调用/,
      /^用户再次询问/,
      /^根据工具返回的结果/,
      /^我来帮您/,
      /^请稍等/,
    ];
    for (const pattern of monologuePatterns) {
      if (pattern.test(msg.trim())) {
        leaks.push(`Raw monologue found: "${msg.trim().substring(0, 80)}..."`);
      }
    }
  }

  return leaks;
}

// 收集结构化 UI 状态，替代截图确认
async function collectUiState(page) {
  return {
    assistantText: await page
      .locator('[data-message-role="assistant"], .copilotKitAssistantMessage')
      .allTextContents(),
    confirmCards: await page
      .locator('text=确认执行接口操作')
      .count(),
    buttons: await page.locator('button').allTextContents(),
  };
}

console.log('E2E Test Script Ready - Run with Playwright MCP tools');
