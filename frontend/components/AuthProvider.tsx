"use client";

import React, { useState, useEffect, createContext, useContext } from "react";

// 认证上下文
interface AuthContextType {
  token: string | null;
  username: string | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType>({
  token: null,
  username: null,
  login: async () => false,
  logout: () => {},
});

export const useAuth = () => useContext(AuthContext);

// 登录弹窗组件
function LoginModal({
  isOpen,
  onClose,
  onLogin,
}: {
  isOpen: boolean;
  onClose: () => void;
  onLogin: (username: string, password: string) => Promise<boolean>;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    const success = await onLogin(username, password);
    setLoading(false);

    if (success) {
      onClose();
      setUsername("");
      setPassword("");
    } else {
      setError("用户名或密码错误");
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white dark:bg-gray-800 rounded-lg p-6 w-96 shadow-xl">
        <h2 className="text-2xl font-bold mb-4 text-gray-800 dark:text-white">登录</h2>
        <form onSubmit={handleSubmit}>
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              用户名
            </label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md dark:bg-gray-700 dark:text-white"
              placeholder="user1 或 user2 或 admin"
              required
            />
          </div>
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
              密码
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-md dark:bg-gray-700 dark:text-white"
              placeholder="password1 或 password2 或 admin123"
              required
            />
          </div>
          {error && <p className="text-red-500 text-sm mb-4">{error}</p>}
          <div className="flex gap-3">
            <button
              type="submit"
              disabled={loading}
              className="flex-1 bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "登录中..." : "登录"}
            </button>
            <button
              type="button"
              onClick={onClose}
              className="flex-1 bg-gray-300 dark:bg-gray-600 text-gray-800 dark:text-white py-2 px-4 rounded-md hover:bg-gray-400"
            >
              取消
            </button>
          </div>
        </form>
        <div className="mt-4 text-sm text-gray-500 dark:text-gray-400">
          <p>Demo 测试用户：</p>
          <ul className="mt-1 space-y-1">
            <li>user1 / password1 (张三)</li>
            <li>user2 / password2 (李四)</li>
            <li>admin / admin123 (管理员)</li>
          </ul>
        </div>
      </div>
    </div>
  );
}

// 认证状态管理组件
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [username, setUsername] = useState<string | null>(null);
  const [showLogin, setShowLogin] = useState(false);

  const JAVA_BACKEND_URL = typeof window !== 'undefined'
    ? (process.env.NEXT_PUBLIC_JAVA_BACKEND_URL || "http://localhost:8080")
    : "http://localhost:8080";

  const login = async (username: string, password: string): Promise<boolean> => {
    try {
      // 生成 Token：base64(username:password)
      const token = btoa(`${username}:${password}`);

      // 验证 Token（调用后端 API 验证）
      const response = await fetch(`${JAVA_BACKEND_URL}/api/auth/verify`, {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });

      if (response.ok) {
        setToken(token);
        setUsername(username);
        // 保存到 localStorage
        localStorage.setItem("auth_token", token);
        localStorage.setItem("auth_username", username);
        return true;
      }
    } catch (error) {
      console.error("Login error:", error);
    }
    return false;
  };

  const logout = () => {
    setToken(null);
    setUsername(null);
    localStorage.removeItem("auth_token");
    localStorage.removeItem("auth_username");
  };

  // 页面加载时恢复登录状态
  useEffect(() => {
    const savedToken = localStorage.getItem("auth_token");
    const savedUsername = localStorage.getItem("auth_username");
    if (savedToken && savedUsername) {
      setToken(savedToken);
      setUsername(savedUsername);
    }
  }, []);

  return (
    <AuthContext.Provider value={{ token, username, login, logout }}>
      {children}
      <LoginModal
        isOpen={showLogin}
        onClose={() => setShowLogin(false)}
        onLogin={login}
      />
      {/* 提供一个全局方法用于显示登录弹窗 */}
      <script dangerouslySetInnerHTML={{__html: `
        window.showLoginModal = function() {
          document.querySelector('[data-login-modal]')?.classList?.add('show');
        };
      `}} />
    </AuthContext.Provider>
  );
}
