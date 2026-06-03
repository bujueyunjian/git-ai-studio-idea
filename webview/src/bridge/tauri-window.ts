// alias 目标:@tauri-apps/api/window
// 插件里只有一个 webview(无桌面宠物窗口),label 恒为 "main" → main.tsx 渲染完整应用。

export class LogicalSize {
  constructor(
    public width: number,
    public height: number,
  ) {}
}

export class PhysicalSize {
  constructor(
    public width: number,
    public height: number,
  ) {}
}

const stub = {
  label: "main",
  async setSize(_size?: unknown): Promise<void> {},
  async show(): Promise<void> {},
  async hide(): Promise<void> {},
  async unminimize(): Promise<void> {},
  async setFocus(): Promise<void> {},
  async onCloseRequested(_cb?: unknown): Promise<() => void> {
    return () => {};
  },
  async listen(_event: string, _cb?: unknown): Promise<() => void> {
    return () => {};
  },
  async emit(_event: string, _payload?: unknown): Promise<void> {},
};

export function getCurrentWindow(): typeof stub {
  return stub;
}
