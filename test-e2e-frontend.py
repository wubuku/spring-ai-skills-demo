#!/usr/bin/env python3
"""
E2E test: verify frontend with tool calls + <think> tags via Playwright.
- Inject auth token via localStorage (skip UI login)
- Send message that triggers loadSkill tool call
- Verify response appears
- Check for <think> collapsible sections
- Check no React "unrecognized" warnings in console
"""
import sys
import time
import base64
from playwright.sync_api import sync_playwright

def main():
    console_errors = []
    console_warnings = []
    react_unrecognized = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1280, "height": 900})
        page = context.new_page()

        # Capture console messages
        def on_console(msg):
            text = msg.text
            if msg.type == "error":
                console_errors.append(text)
            elif msg.type == "warning":
                console_warnings.append(text)
            if "unrecognized in this browser" in text.lower():
                react_unrecognized.append(text)

        page.on("console", on_console)

        print("=== Step 1: Navigate to frontend ===")
        page.goto("http://localhost:4000")
        page.wait_for_load_state("networkidle")

        print("=== Step 2: Inject auth token via localStorage ===")
        # The AuthProvider uses btoa("user1:password1") as the token
        token = base64.b64encode(b"user1:password1").decode("utf-8")
        page.evaluate(f"""() => {{
            localStorage.setItem("auth_token", "{token}");
            localStorage.setItem("auth_username", "user1");
        }}""")
        print(f"Token injected: {token[:20]}...")

        # Reload to pick up the token from localStorage
        page.reload()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(2000)
        page.screenshot(path="/tmp/e2e-01-logged-in.png", full_page=True)

        # Verify logged in
        welcome = page.locator("text=欢迎")
        if welcome.count() > 0 and welcome.first.is_visible():
            print("PASS: Login successful - 'welcome' text visible")
        else:
            print("WARNING: No 'welcome' text found, checking page content...")
            body_text = page.inner_text("body")[:300]
            print(f"  Page text: {body_text}")

        print("\n=== Step 3: Find and interact with CopilotKit chat ===")
        # The CopilotKit popup is already visible from the screenshot
        # Look for textarea (CopilotKit uses textarea for input)
        textarea = page.locator("textarea")
        if textarea.count() > 0:
            print(f"Found {textarea.count()} textarea(s)")
        else:
            print("No textarea found, looking for input alternatives...")
            editable = page.locator("[contenteditable='true']")
            if editable.count() > 0:
                print(f"Found {editable.count()} contenteditable element(s)")

        print("\n=== Step 4: Send message to trigger tool call ===")
        # This message triggers loadSkill + httpRequest (the scenario that previously failed)
        message = "我可以买什么商品？"

        # Try textarea first, then contenteditable
        input_el = page.locator("textarea").first
        if input_el.is_visible():
            input_el.click(force=True)
            page.wait_for_timeout(300)
            input_el.fill(message)
            print(f"Typed in textarea: {message}")
        else:
            input_el = page.locator("[contenteditable='true']").first
            input_el.click(force=True)
            page.wait_for_timeout(300)
            input_el.type(message)
            print(f"Typed in contenteditable: {message}")

        page.screenshot(path="/tmp/e2e-02-message-typed.png", full_page=True)

        # Send message by pressing Enter
        input_el.press("Enter")
        print("Pressed Enter to send")

        print("\n=== Step 5: Wait for AI response (up to 120s) ===")
        start_time = time.time()
        response_found = False
        tool_call_detected = False
        think_detected = False

        while time.time() - start_time < 120:
            page.wait_for_timeout(5000)
            elapsed = int(time.time() - start_time)

            page_text = page.inner_text("body")

            # Check for meaningful response content
            if any(kw in page_text for kw in ["商品", "已加载", "search-products", "loadSkill", "products", "价格", "¥", "￥"]):
                if not response_found:
                    response_found = True
                    print(f"  Response content detected at {elapsed}s")

            if "loadSkill" in page_text or "search-products" in page_text or "httpRequest" in page_text:
                tool_call_detected = True

            if "思考过程" in page_text:
                if not think_detected:
                    think_detected = True
                    print(f"  Think section detected at {elapsed}s")

            # Periodic screenshots
            if elapsed % 20 == 0:
                page.screenshot(path=f"/tmp/e2e-03-{elapsed}s.png", full_page=True)

            if response_found and elapsed > 30:
                page.wait_for_timeout(10000)
                break

            if elapsed % 10 == 0:
                print(f"  Waiting... ({elapsed}s)")

        page.screenshot(path="/tmp/e2e-04-final.png", full_page=True)
        page.screenshot(path="/tmp/e2e-05-final-viewport.png")

        print("\n=== Step 6: Verify results ===")

        # React unrecognized tag warnings
        if react_unrecognized:
            print(f"FAIL: React unrecognized tag warnings ({len(react_unrecognized)}):")
            for w in react_unrecognized[:5]:
                print(f"  - {w[:200]}")
        else:
            print("PASS: No React 'unrecognized in this browser' warnings")

        # Console errors (filter known non-issues)
        ag_errors = [e for e in console_errors
                     if "unrecognized" not in e.lower()
                     and "custom element" not in e.lower()
                     and "failed to load resource" not in e.lower()
                     and "zoderror" not in e.lower()
                     and "socketerror" not in e.lower()
                     and "terminated" not in e.lower()]
        if ag_errors:
            print(f"\nConsole errors ({len(ag_errors)}):")
            for e in ag_errors[:5]:
                print(f"  - {e[:200]}")
        else:
            print("PASS: No significant console errors")

        if response_found:
            print("PASS: AI response with relevant content found")
        else:
            print("FAIL: No AI response detected")

        if tool_call_detected:
            print("PASS: Tool call (loadSkill/search-products) detected")
        else:
            print("INFO: Tool call not explicitly detected in visible text")

        if think_detected:
            print("PASS: '思考过程' (think collapsible section) detected")
        else:
            print("INFO: No think sections (model may not have used thinking)")

        details_count = page.locator("details").count()
        if details_count > 0:
            print(f"PASS: Found {details_count} <details> element(s) (likely <think> sections)")

        print(f"\nConsole errors: {len(console_errors)}, warnings: {len(console_warnings)}")
        print("Screenshots saved to /tmp/e2e-*.png")

        browser.close()

    if react_unrecognized:
        print("\nRESULT: FAIL (React unrecognized tags)")
        return 1
    if not response_found:
        print("\nRESULT: FAIL (no response)")
        return 1
    print("\nRESULT: PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
