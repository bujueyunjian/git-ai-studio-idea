// alias 目标:@tauri-apps/api/app
// 插件版本由 Kotlin(WebUiPanel.injectBootstrap)注入到 window.__GITAI_PLUGIN_VERSION__。
export async function getVersion(): Promise<string> {
  return window.__GITAI_PLUGIN_VERSION__ ?? "0.1.0";
}

export async function getName(): Promise<string> {
  return "Git AI Studio";
}
