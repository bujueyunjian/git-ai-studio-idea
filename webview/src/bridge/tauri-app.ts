// alias 目标:@tauri-apps/api/app
// 插件版本由 Kotlin(WebSchemeHandlerFactory)在 serve index.html 时注入 window.__GITAI_PLUGIN_VERSION__,
// bundle 执行前已就绪;未注入(非插件宿主调试)时返回空串,footer/关于页据此隐藏版本号而非伪显旧版本。
export async function getVersion(): Promise<string> {
  return window.__GITAI_PLUGIN_VERSION__ ?? "";
}

export async function getName(): Promise<string> {
  return "Git AI Studio";
}
