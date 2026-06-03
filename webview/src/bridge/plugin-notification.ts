// alias 目标:@tauri-apps/plugin-notification
// 路由到后端 notify 命令(Kotlin 出 IDE 气泡通知)。权限模型在 IDE 里不需要 → 恒已授权。
import { invoke } from "./core";

export async function isPermissionGranted(): Promise<boolean> {
  return true;
}

export async function requestPermission(): Promise<"granted" | "denied"> {
  return "granted";
}

export function sendNotification(options: { title: string; body?: string } | string): void {
  const title = typeof options === "string" ? options : options.title;
  const body = typeof options === "string" ? "" : (options.body ?? "");
  // fire-and-forget;通知失败不应打断 watcher 链路
  void invoke("notify", { title, body }).catch(() => {});
}
