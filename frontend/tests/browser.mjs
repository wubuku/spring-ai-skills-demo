import { chromium } from "playwright";

export function launchChromium() {
  const channel = process.env.PLAYWRIGHT_BROWSER_CHANNEL?.trim();
  return chromium.launch({
    headless: true,
    ...(channel ? { channel } : {}),
  });
}
