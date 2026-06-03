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

const root = ReactDOM.createRoot(document.getElementById("root") as HTMLElement);

root.render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider>
        <UpdateProvider>
          <App />
        </UpdateProvider>
        <Toaster richColors closeButton position="bottom-right" toastOptions={{ duration: 3500 }} />
      </RouterProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
