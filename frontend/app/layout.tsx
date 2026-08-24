import type { Metadata } from "next";
import { CopilotProvider } from "@/components/CopilotProvider";
// v2 入口（`@copilotkit/react-core/v2`）在 1.60.2 下会自动 import 它自带的
// `dist/v2/index.css`，那份 CSS 是 Tailwind v4 输出（含 @layer properties /
// @layer base），与项目 Tailwind v3 流水线不兼容，会抛
// "`@layer base` is used but no matching `@tailwind base` directive is present"。
//
// 解决方案：scripts/transform-v2-css.mjs 在 postinstall 阶段把那份 v4 CSS
// 手工转成 v3 兼容（剥掉 @layer properties、@layer base 等 v4 专属语法），
// 写到 patches/copilotkit-v2-v3.css。这里直接 import 转换后的副本。
// popup 容器关键定位（cpk:fixed / inset-0 / z-[1200]）保留在 globals.css 末尾兜底。
import "./globals.css";
import "../patches/copilotkit-v2-v3.css";

export const metadata: Metadata = {
  title: "企业智能助手",
  description: "基于 CopilotKit 的企业智能体前端",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>
        <CopilotProvider>
          {children}
        </CopilotProvider>
      </body>
    </html>
  );
}
