import { useMutation, useQuery } from "@tanstack/react-query";
import { Database, Info, Languages, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import i18n, { setLanguage, type SupportedLanguage } from "../i18n";
import { clearStatsCache, getGitAiConfig } from "../lib/api";

/**
 * 精简设置页(IDE 插件版)。只保留在 IDE 里有意义、且后端真实可用的项:
 * 界面语言、git-ai 自动更新只读状态、清统计缓存、关于。
 * 桌面专属(主题/托盘/自启/宠物/通知 watcher/应用内更新)已移除——主题由 IDE 驱动,其余在 IDE 里无意义。
 */
export default function SettingsLean() {
  const { t } = useTranslation();

  const cfgQ = useQuery({
    queryKey: ["git_ai_config"],
    queryFn: getGitAiConfig,
    staleTime: 30_000,
    retry: false,
  });
  const autoUpdateEnabled = !(cfgQ.data?.disable_auto_updates ?? false);

  const clearM = useMutation({
    mutationFn: () => clearStatsCache("current_repo"),
    onSuccess: (count) => toast.success(t("settings.cache.clearSuccess", { count })),
    onError: (e) =>
      toast.error(t("settings.cache.clearFailed"), {
        description: (e as Error).message,
      }),
  });

  return (
    <div className="mx-auto max-w-2xl space-y-4 p-6">
      {/* 界面语言 —— 唯一必须的设置(纯前端 i18next) */}
      <section className="rounded-lg border border-border bg-card p-4">
        <h2 className="mb-3 flex items-center gap-2 text-sm font-medium">
          <Languages className="h-4 w-4 text-slate-500" /> {t("settings.language.label")}
        </h2>
        <select
          value={(i18n.language === "en" ? "en" : "zh-CN") as SupportedLanguage}
          onChange={(e) => setLanguage(e.target.value as SupportedLanguage)}
          className="rounded-md border border-slate-200 bg-white px-2 py-1 text-sm dark:border-border dark:bg-card"
          aria-label={t("settings.language.label")}
        >
          <option value="zh-CN">{t("settings.language.zh_CN")}</option>
          <option value="en">{t("settings.language.en")}</option>
        </select>
        <p className="mt-2 text-[11px] text-slate-400">{t("settings.language.hint")}</p>
      </section>

      {/* git-ai 自动更新(只读;由外部 git-ai CLI 的 ~/.git-ai/config.json 控制) */}
      <section className="rounded-lg border border-border bg-card p-4">
        <h2 className="mb-1 flex items-center gap-2 text-sm font-medium">
          <RefreshCw className="h-4 w-4 text-slate-500" /> {t("settings.gitAiUpdate.title")}
        </h2>
        <p className="text-xs text-slate-500">
          {t("settings.gitAiUpdate.statusLabel", {
            status: autoUpdateEnabled
              ? t("settings.gitAiUpdate.statusEnabled")
              : t("settings.gitAiUpdate.statusDisabled"),
          })}
        </p>
      </section>

      {/* 统计缓存 */}
      <section className="rounded-lg border border-border bg-card p-4">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="flex items-center gap-2 text-sm font-medium">
              <Database className="h-4 w-4 text-slate-500" />
              {t("settings.cache.title")}
            </h2>
            <p className="mt-0.5 text-xs text-slate-500">{t("settings.cache.description")}</p>
          </div>
          <button
            type="button"
            disabled={clearM.isPending}
            onClick={() => clearM.mutate()}
            className="shrink-0 rounded-md border border-border px-2.5 py-1 text-xs hover:bg-muted disabled:opacity-60"
          >
            {t("settings.cache.clear")}
          </button>
        </div>
      </section>

      {/* 关于 */}
      <section className="rounded-lg border border-border bg-card p-4">
        <h2 className="mb-1 flex items-center gap-2 text-sm font-medium">
          <Info className="h-4 w-4 text-slate-500" /> {t("settings.about.title")}
        </h2>
        <a
          href="https://github.com/bujueyunjian/git-ai-studio-idea"
          target="_blank"
          rel="noreferrer"
          className="text-xs text-primary hover:underline"
        >
          {t("settings.about.sourceCode")}
        </a>
        <p className="mt-1 text-[11px] text-slate-400">{t("settings.about.privacy")}</p>
      </section>
    </div>
  );
}
