// alias 目标:@tauri-apps/api/webviewWindow
// 插件里主窗口由 IDE 管理,show/unminimize/setFocus 在 webview 内无意义 → 安全 no-op。

const stub = {
  label: "main",
  async show(): Promise<void> {},
  async hide(): Promise<void> {},
  async unminimize(): Promise<void> {},
  async setFocus(): Promise<void> {},
  async listen(_event: string, _cb?: unknown): Promise<() => void> {
    return () => {};
  },
  async emit(_event: string, _payload?: unknown): Promise<void> {},
};

export class WebviewWindow {
  static getByLabel(_label: string): typeof stub | null {
    return null;
  }
}

export function getCurrentWebviewWindow(): typeof stub {
  return stub;
}
