// alias 目标:@tauri-apps/plugin-dialog
// 目录选择走后端 pick_directory 命令(Kotlin 在 EDT 弹 IDE 原生 FileChooser)。
import { invoke } from "./core";

export interface OpenOptions {
  directory?: boolean;
  multiple?: boolean;
  title?: string;
}

export async function open(options: OpenOptions = {}): Promise<string | string[] | null> {
  if (options.directory) {
    const path = await invoke<string | null>("pick_directory", { title: options.title ?? null });
    return path ?? null;
  }
  // 插件 v1 仅支持选目录(扫描根 / 仓库选择);选文件场景未用到
  return null;
}
