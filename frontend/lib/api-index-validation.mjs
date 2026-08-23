/**
 * Validate a browser httpRequest against the backend Skill API index.
 *
 * The backend owns the index. This module only applies the same exact/path-template
 * matching semantics in the browser and deliberately rejects absolute URLs.
 */
export function validateSkillApiUrl(method, url, apiIndex) {
  const normalizedMethod = String(method || "").toUpperCase();
  const rawUrl = String(url || "");

  if (!normalizedMethod || !rawUrl) {
    return { valid: false, error: "HTTP method 和 URL 不能为空。" };
  }
  if (!["GET", "POST", "PUT", "PATCH", "DELETE"].includes(normalizedMethod)) {
    return { valid: false, error: `不支持的 HTTP 方法：${normalizedMethod}` };
  }
  if (rawUrl.includes("#")) {
    return { valid: false, error: "不允许 URL fragment。" };
  }
  if (/^[a-z][a-z0-9+.-]*:/i.test(rawUrl) || rawUrl.startsWith("//")) {
    return { valid: false, error: "不允许绝对 URL。" };
  }

  const urlPath = rawUrl.split("?", 1)[0];
  if (!urlPath.startsWith("/")) {
    return { valid: false, error: "URL 路径必须是相对的绝对路径。" };
  }
  if (
    urlPath.includes("\\") ||
    /[\s\u0000-\u001f\u007f]/.test(urlPath) ||
    /%(?:00|2e|2f|5c)/i.test(urlPath)
  ) {
    return { valid: false, error: "URL 路径包含非法字符。" };
  }
  if (urlPath.split("/").some((segment) => segment === "." || segment === "..")) {
    return { valid: false, error: "URL 路径不允许目录跳转。" };
  }
  const exactKey = `${normalizedMethod} ${urlPath}`;
  if (apiIndex && apiIndex[exactKey]) {
    return { valid: true };
  }

  const candidates = Object.entries(apiIndex || {});
  for (const [key] of candidates) {
    const separator = key.indexOf(" ");
    if (separator < 0 || key.slice(0, separator) !== normalizedMethod) continue;
    const pattern = key.slice(separator + 1);
    const patternParts = pattern.split("/");
    const pathParts = urlPath.split("/");
    if (patternParts.length !== pathParts.length) continue;
    const matches = patternParts.every((part, index) => {
      if (part === pathParts[index]) return true;
      return pathParts[index] !== "" && /^\{[^/{}]+\}$/.test(part);
    });
    if (matches) return { valid: true };
  }

  const hint = candidates
    .filter(([key]) => key.startsWith(`${normalizedMethod} `))
    .map(([key, entry]) => `  ${key} -> loadSkill("${entry.skillName}")`)
    .join("\n");
  return {
    valid: false,
    error: `URL "${rawUrl}" 不是已注册的 API 端点。请先调用 loadSkill 获取正确的 API 路径。\n可用的 ${normalizedMethod} 端点：\n${hint}`,
  };
}

if (typeof globalThis !== "undefined") {
  globalThis.__validateSkillApiUrl = validateSkillApiUrl;
}
