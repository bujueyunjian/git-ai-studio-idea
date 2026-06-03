// 主题应用 helper。与 index.html 顶部的 FOUC 内联脚本共用 storage key。

export type Theme = "light" | "dark" | "system";

const STORAGE_KEY = "git-ai-studio.theme";

export function loadTheme(): Theme {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === "light" || v === "dark" || v === "system") return v;
  } catch {
    /* ignore */
  }
  return "system";
}

export function persistTheme(t: Theme) {
  try {
    localStorage.setItem(STORAGE_KEY, t);
  } catch {
    /* ignore */
  }
}

// IDE(JCEF)宿主下,明暗由 Kotlin 跟随 IDE 主题授权控制(WebUiPanel),
// 前端的主题切换全部让位,避免与 IDE 主题打架。
function isIdeHost(): boolean {
  return typeof window !== "undefined" && window.__GITAI_HOST__ === "idea";
}

export function applyTheme(t: Theme) {
  if (isIdeHost()) return;
  const isDark =
    t === "dark" ||
    (t === "system" &&
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", !!isDark);
}

let mq: MediaQueryList | null = null;
let listener: ((e: MediaQueryListEvent) => void) | null = null;

/** 切到 system 时挂上 matchMedia 监听;切到 light/dark 时清理。 */
export function subscribeSystemTheme(currentTheme: Theme) {
  if (isIdeHost()) return;
  if (mq && listener) {
    mq.removeEventListener("change", listener);
    mq = null;
    listener = null;
  }
  if (currentTheme !== "system" || typeof window === "undefined" || !window.matchMedia) return;
  mq = window.matchMedia("(prefers-color-scheme: dark)");
  listener = () => applyTheme("system");
  mq.addEventListener("change", listener);
}
