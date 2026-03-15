"use client";

import { CopilotKit } from "@copilotkit/react-core";

const getHeaders = async (): Promise<Record<string, string>> => {
  const token = typeof window !== 'undefined'
    ? localStorage.getItem('auth_token')
    : null;
  return token ? { Authorization: `Bearer ${token}` } : {};
};

export function CopilotProvider({ children }: { children: React.ReactNode }) {
  return (
    <CopilotKit
      runtimeUrl="/api/copilotkit"
      headers={getHeaders as any}
    >
      {children}
    </CopilotKit>
  );
}
