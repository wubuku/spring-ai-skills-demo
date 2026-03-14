import type { Metadata } from "next";
import { CopilotProvider } from "@/components/CopilotProvider";
import "@copilotkit/react-ui/styles.css";
import "./globals.css";

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
