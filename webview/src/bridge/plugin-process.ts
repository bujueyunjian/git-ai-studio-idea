// alias 目标:@tauri-apps/plugin-process
// 插件不自管进程生命周期(IDE 负责),relaunch / exit 一律 no-op。
export async function relaunch(): Promise<void> {}
export async function exit(_code?: number): Promise<void> {}
