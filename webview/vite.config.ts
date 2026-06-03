import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const shim = (p: string) => path.resolve(__dirname, "src/bridge", p);

// 复用 git-ai-studio 桌面前端,只把 Tauri 传输层换成 JCEF 桥 shim:
// 所有 @tauri-apps/* 导入(含动态 import)经 resolve.alias 重定向到 src/bridge/*,
// React 业务源码一行不改。构建产物输出到插件资源 /web,由 WebSchemeHandlerFactory 提供给 JCEF。
export default defineConfig({
  plugins: [react()],
  base: "./",
  resolve: {
    alias: {
      "@tauri-apps/api/core": shim("tauri-core.ts"),
      "@tauri-apps/api/event": shim("tauri-event.ts"),
      "@tauri-apps/api/app": shim("tauri-app.ts"),
      "@tauri-apps/api/window": shim("tauri-window.ts"),
      "@tauri-apps/api/webviewWindow": shim("tauri-webview-window.ts"),
      "@tauri-apps/plugin-updater": shim("plugin-updater.ts"),
      "@tauri-apps/plugin-process": shim("plugin-process.ts"),
      "@tauri-apps/plugin-dialog": shim("plugin-dialog.ts"),
      "@tauri-apps/plugin-notification": shim("plugin-notification.ts"),
      "@": path.resolve(__dirname, "src"),
    },
  },
  build: {
    outDir: path.resolve(__dirname, "../src/main/resources/web"),
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
  },
  clearScreen: false,
});
