import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";

// i18n 必须在任何使用文案的模块加载之前初始化:copy.ts 的 module-load 期间会读 i18n。
import "./i18n";

import App from "./App";
import { RouterProvider } from "./router";
import { UpdateProvider } from "./contexts/UpdateContext";
import "./App.css";

// 插件版:无桌面宠物窗口,始终渲染完整应用(明暗主题由 IDE 经 Kotlin 注入,见 WebUiPanel)。
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      staleTime: 30_000,
      retry: 1,
    },
  },
});

// 渲染期异常隔离:单个组件抛错(如旧版后端返回缺字段)只降级为局部错误卡,
// 绝不让 React 卸载整棵树 → #root 变白屏。本地解析、零上传不变。
// 注:作为崩溃兜底,文案内联(此时 i18n 可能正是失败源,不能依赖 hooks/t())。
class RootErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("Git AI Studio webview render error:", error, info.componentStack);
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: 16, font: "13px ui-monospace, SFMono-Regular, monospace" }}>
          <div style={{ fontWeight: 600, marginBottom: 8, color: "#e06c75" }}>
            界面渲染出错 · Render error（已隔离，本机解析未上传任何数据）
          </div>
          <pre style={{ whiteSpace: "pre-wrap", color: "#9aa0a6", margin: 0 }}>
            {String(this.state.error?.message ?? this.state.error)}
          </pre>
          <button
            type="button"
            onClick={() => this.setState({ error: null })}
            style={{ marginTop: 12, padding: "4px 10px", cursor: "pointer" }}
          >
            重试 · Retry
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

const root = ReactDOM.createRoot(document.getElementById("root") as HTMLElement);

root.render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider>
        <UpdateProvider>
          <RootErrorBoundary>
            <App />
          </RootErrorBoundary>
        </UpdateProvider>
        <Toaster richColors closeButton position="bottom-right" toastOptions={{ duration: 3500 }} />
      </RouterProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
