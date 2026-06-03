// alias 目标:@tauri-apps/plugin-updater
// 插件更新由 JetBrains Marketplace / IDE 负责,应用内 updater 一律 no-op:check() 恒返 null = 已是最新。
export type Update = {
  version?: string;
  notes?: string;
  date?: string;
  downloadAndInstall?: (onEvent?: (e: unknown) => void) => Promise<void>;
};

export async function check(_options?: unknown): Promise<Update | null> {
  return null;
}
