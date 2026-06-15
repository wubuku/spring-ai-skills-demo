/** @type {import('next').NextConfig} */
const path = require("path");

const nextConfig = {
  reactStrictMode: true,

  // CopilotKit v2 (1.60.1) 自带的 dist/v2/index.css 是 Tailwind v4 输出，
  // 包含 @layer properties / @layer base 等 v4 专属语法，与项目 Tailwind v3
  // 流水线不兼容。scripts/transform-v2-css.mjs 在 postinstall 阶段把它转成
  // v3 兼容副本写到 patches/copilotkit-v2-v3.css。这里用 webpack alias 把
  // 原文件指向转换后的副本（同时覆盖 package.json `exports` 中的 ./v2/styles.css）。
  webpack: (config) => {
    const patched = path.resolve(__dirname, "patches/copilotkit-v2-v3.css");
    // 别名 `dist/v2/index.css` 路径（v2 entry mjs 直接 import 的）
    config.resolve.alias = config.resolve.alias || {};
    // 同时拦截 css 加载请求
    const origRule = config.module.rules.find((r) => r && r.oneOf);
    if (origRule) {
      // 简单做法：在 webpack 解析阶段将原 CSS 文件重定向
      // Next.js 内部使用 mini-css-extract / css-loader，alias 对 raw source 同样生效
    }
    // 使用 ignore-loader 风格的替代方案在 Next 13+ 不稳；改用 `resolve.alias`
    // 把 path 直接替换为 patched file。Next.js 通过 css-loader 加载 css 时
    // 先走 resolve，alias 生效。
    config.resolve.alias["@copilotkit/react-core/dist/v2/index.css$"] = patched;

    // streamdown 1.6.11 (bundled in @copilotkit/react-core 1.60.x) imports
    // `mermaid` (bare specifier) which the mermaid 11.15.0 package's
    // `exports` field resolves to `./dist/mermaid.core.mjs`. That entry
    // references `./chunks/mermaid.core/xychartDiagram-2RQKCTM6.mjs` etc.,
    // but only the .map siblings ship in the tarball. The full-fat
    // `mermaid.esm.mjs` entry + its `chunks/mermaid.esm/*` siblings ARE
    // present, so alias the bare `mermaid` specifier to the esm entry.
    const mermaidEsm = path.resolve(
      __dirname,
      "node_modules/mermaid/dist/mermaid.esm.mjs"
    );
    config.resolve.alias["mermaid$"] = mermaidEsm;

    return config;
  },
};

module.exports = nextConfig
