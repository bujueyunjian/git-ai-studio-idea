import { Suspense, useEffect, useRef, useState } from "react";
import i18n from "./i18n";

import { Rail } from "./components/Layout/Rail";
import { TopBar } from "./components/Layout/TopBar";
import { TooltipProvider } from "./components/ui/TooltipBubble";
import { restoreLastRepo } from "./lib/api";
import { useRepoChanged } from "./lib/useRepoChanged";
import { useRouter } from "./router";

import DiagnosticPage from "./pages/Diagnostic";
import DashboardPage from "./pages/Dashboard";
import PeoplePage from "./pages/People";
import StatsPage from "./pages/Stats";
import NotesPage from "./pages/Notes";
import HooksPage from "./pages/Hooks";
import SettingsPage from "./pages/SettingsLean";

// 插件(精简)版:只保留 Dashboard / Stats / People / Notes / Diagnostic 五页;
// 桌面专属的后台 watcher、首启引导向导、主题切换、托盘/自启/宠物均已移除。
// 明暗主题由 IDE 经 Kotlin(WebUiPanel)注入,前端不自管。
export default function App() {
  const { current, navigate } = useRouter();
  // 语言切换:订阅 i18next 的 languageChanged,当前语言作为子树 key,切换时重挂载刷新文案。
  const [lang, setLang] = useState<string>(i18n.language);
  useEffect(() => {
    const handler = (l: string) => setLang(l);
    i18n.on("languageChanged", handler);
    return () => i18n.off("languageChanged", handler);
  }, []);

  const handleRepoChanged = useRepoChanged();

  // 启动时自动采用当前工程仓库(后端 restore_last_repo 返回项目 git 根)。
  // 用 ref + 空依赖只跑一次,避免 handleRepoChanged 随路由变化反复触发导致路由横跳。
  const handleRepoChangedRef = useRef(handleRepoChanged);
  handleRepoChangedRef.current = handleRepoChanged;
  useEffect(() => {
    restoreLastRepo()
      .then((r) => {
        if (r) handleRepoChangedRef.current();
      })
      .catch(() => {});
  }, []);

  return (
    <TooltipProvider>
      <div key={lang} className="contents">
        <div className="flex h-full">
          <Rail current={current} onNavigate={navigate} />
          <div className="flex flex-1 flex-col overflow-hidden">
            <TopBar onNavigate={navigate} onRepoChanged={handleRepoChanged} />
            <main className="relative flex-1 overflow-y-auto">
              <Suspense
                fallback={
                  <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                    {i18n.t("common.loading")}
                  </div>
                }
              >
                {renderPage(current)}
              </Suspense>
            </main>
          </div>
        </div>
      </div>
    </TooltipProvider>
  );
}

function renderPage(r: ReturnType<typeof useRouter>["current"]) {
  switch (r) {
    case "dashboard":
      return <DashboardPage />;
    case "stats":
      return <StatsPage />;
    case "people":
      return <PeoplePage />;
    case "notes":
      return <NotesPage />;
    case "diagnostic":
      return <DiagnosticPage />;
    case "hooks":
      return <HooksPage />;
    case "settings":
      return <SettingsPage />;
    default:
      return <DashboardPage />;
  }
}
