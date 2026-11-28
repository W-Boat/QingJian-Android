import React, { useEffect, useState } from 'react';
import {
  BookOpen,
  Feather,
  Moon,
  Network,
  Settings,
  Sliders,
  Sun,
  Users,
} from 'lucide-react';
import { BookshelfView } from './components/BookshelfView';
import { MuyaEditorView } from './components/MuyaEditorView';
import { PlotWorkbenchView } from './components/PlotWorkbenchView';
import { RoleplayView } from './components/RoleplayView';
import { MIUIX_ACCENTS, SettingsModal } from './components/SettingsModal';
import { SkillsSandboxView } from './components/SkillsSandboxView';
import { analyzeStyleDistillation, getDefaultSandboxScripts } from './core/sandboxJs';
import { createInitialAiProfiles, createSeededWhiteTowerProject } from './core/seedData';
import type { AiAccountProfile, AiUsageLog, NovelProject } from './core/types';

type ActiveTab = 'BOOKSHELF' | 'EDITOR' | 'PLOT' | 'SKILLS' | 'ROLEPLAY';

const STORAGE_KEY_BOOKS = 'qingjian_books_v1';
const STORAGE_KEY_PREFS = 'qingjian_prefs_v1';

interface StoredPrefs {
  isDark: boolean;
  accentHex: string;
  profiles: AiAccountProfile[];
  activeProfileId: string;
  usageLogs: AiUsageLog[];
}

function loadInitialBooks(): NovelProject[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_BOOKS);
    if (raw) {
      const parsed = JSON.parse(raw) as NovelProject[];
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    }
  } catch {
    // fallback to seeded novel
  }
  return [createSeededWhiteTowerProject()];
}

function loadInitialPrefs(): StoredPrefs {
  const defaultProfiles = createInitialAiProfiles();
  try {
    const raw = localStorage.getItem(STORAGE_KEY_PREFS);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<StoredPrefs>;
      return {
        isDark: Boolean(parsed.isDark),
        accentHex: parsed.accentHex || MIUIX_ACCENTS[0].hex,
        profiles:
          Array.isArray(parsed.profiles) && parsed.profiles.length > 0
            ? parsed.profiles
            : defaultProfiles,
        activeProfileId: parsed.activeProfileId || defaultProfiles[0].id,
        usageLogs: Array.isArray(parsed.usageLogs) ? parsed.usageLogs : [],
      };
    }
  } catch {
    // fallback defaults
  }
  return {
    isDark: false,
    accentHex: MIUIX_ACCENTS[0].hex,
    profiles: defaultProfiles,
    activeProfileId: defaultProfiles[0].id,
    usageLogs: [],
  };
}

export default function App() {
  const [books, setBooks] = useState<NovelProject[]>(loadInitialBooks);
  const [prefs, setPrefs] = useState<StoredPrefs>(loadInitialPrefs);
  const [diskSynced, setDiskSynced] = useState(false);

  const [activeTab, setActiveTab] = useState<ActiveTab>('EDITOR');
  const [activeBookId, setActiveBookId] = useState<string>(() => books[0]?.id ?? 'book_white_tower');
  const [activeChapterId, setActiveChapterId] = useState<string>(
    () => books[0]?.workspace.chapters[1]?.chapterId ?? books[0]?.workspace.chapters[0]?.chapterId ?? 'ch_001',
  );
  const [highlightedBlockId, setHighlightedBlockId] = useState<string | null>(null);
  const [pendingSandboxReport, setPendingSandboxReport] = useState<string | null>(null);
  const [showSettings, setShowSettings] = useState(false);
  const [toastMsg, setToastMsg] = useState<string | null>(null);

  const activeBook = books.find((b) => b.id === activeBookId) ?? books[0];
  const activeProfile =
    prefs.profiles.find((p) => p.id === prefs.activeProfileId) ?? prefs.profiles[0];

  // Hydrate real disk workspace from Express `/api/workspace` on startup
  useEffect(() => {
    let active = true;
    fetch('/api/workspace')
      .then((r) => (r.ok ? r.json() : null))
      .then((data: { initialized?: boolean; books?: NovelProject[]; prefs?: Partial<StoredPrefs> } | null) => {
        if (!active || !data) return;
        if (data.initialized && Array.isArray(data.books) && data.books.length > 0) {
          setBooks(data.books);
          setActiveBookId(data.books[0].id);
          const firstCh = data.books[0].workspace.chapters[0]?.chapterId;
          if (firstCh) setActiveChapterId(firstCh);
        }
        if (data.prefs) {
          setPrefs((prev) => ({
            ...prev,
            ...data.prefs,
            profiles:
              Array.isArray(data.prefs?.profiles) && data.prefs.profiles.length > 0
                ? data.prefs.profiles
                : prev.profiles,
            usageLogs: Array.isArray(data.prefs?.usageLogs) ? data.prefs.usageLogs : prev.usageLogs,
          }));
        }
      })
      .catch(() => {
        // offline preview fallback to localStorage cache
      })
      .finally(() => {
        if (active) setDiskSynced(true);
      });
    return () => {
      active = false;
    };
  }, []);

  // Persist state to localStorage + real human-readable disk files via `/api/workspace`
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY_BOOKS, JSON.stringify(books));
    } catch {
      // ignore storage quota errors
    }
  }, [books]);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY_PREFS, JSON.stringify(prefs));
    } catch {
      // ignore
    }
  }, [prefs]);

  useEffect(() => {
    if (!diskSynced) return;
    const timer = setTimeout(() => {
      fetch('/api/workspace', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ books, prefs }),
      }).catch(() => {
        // ignore transient network errors during autosave
      });
    }, 600);
    return () => clearTimeout(timer);
  }, [books, prefs, diskSynced]);

  // Sync dark mode class and Miuix accent CSS variable
  useEffect(() => {
    document.documentElement.classList.toggle('dark', prefs.isDark);
    document.documentElement.style.setProperty('--qj-accent', prefs.accentHex);
  }, [prefs.isDark, prefs.accentHex]);

  const showToast = (msg: string) => {
    setToastMsg(msg);
  };

  useEffect(() => {
    if (!toastMsg) return;
    const t = setTimeout(() => setToastMsg(null), 3600);
    return () => clearTimeout(t);
  }, [toastMsg]);

  const updateActiveProject = (updater: (prev: NovelProject) => NovelProject) => {
    setBooks((prev) => prev.map((b) => (b.id === activeBook.id ? updater(b) : b)));
  };

  const handleLogUsage = (usage: AiUsageLog) => {
    setPrefs((prev) => ({
      ...prev,
      usageLogs: [usage, ...prev.usageLogs.slice(0, 99)],
    }));
  };

  const handleCreateBook = (params: {
    title: string;
    author: string;
    genre: string;
    synopsis: string;
    coverBg: string;
    coverAccent: string;
    wordCountGoalPerChapter: number;
  }) => {
    const id = `book_${Date.now()}`;
    const ch1Id = `ch_${Date.now()}_1`;
    const initialMd = `## 第一章 序幕\n\n在此落下《${params.title}》的第一行正文。单击段落可展开工具条，双击唤起局部改稿，长按 650ms 进入完整编辑。`;
    const workspace = {
      chapters: [
        {
          chapterId: ch1Id,
          volumeTitle: '第一卷',
          title: '第一章 序幕',
          order: 1,
          status: 'DRAFT' as const,
          summary: params.synopsis,
          markdownContent: initialMd,
          blockFeedback: {},
          blockAnnotations: {},
          updatedAt: Date.now(),
        },
      ],
      handoffs: [],
      loreEntries: [],
      loreCandidates: [],
      foreshadows: [],
      causalGraph: { nodes: [], edges: [] },
      plotPlans: [],
      worldbookEntries: [],
    };
    const initHash = `init${Date.now().toString(16)}`;
    const newBook: NovelProject = {
      id,
      title: params.title,
      author: params.author,
      genre: params.genre,
      synopsis: params.synopsis,
      coverBg: params.coverBg,
      coverAccent: params.coverAccent,
      wordCountGoalPerChapter: params.wordCountGoalPerChapter,
      editorWidthPx: 720,
      writingMode: 'HUMAN_LED',
      aiWizardCompleted: false,
      workspace,
      skills: {
        enabledBuiltIn: ['ARMOR_BREAK', 'DE_AI_TONE'],
        styleProfile: analyzeStyleDistillation('初始样稿', initialMd),
        customCorrectionRules: [],
        importedPackages: [],
        scripts: getDefaultSandboxScripts(),
      },
      contextConfig: {
        neighborParagraphsBefore: 3,
        neighborParagraphsAfter: 2,
        includeOutline: true,
        includeFinalizedHandoff: true,
        includeAuthorFeedback: true,
        worldbookCharBudget: 2400,
        totalContextCharBudget: 12000,
      },
      rpCards: [],
      currentBranch: 'main',
      branches: [{ name: 'main', headCommitHash: initHash }],
      commits: [
        {
          hash: initHash,
          shortHash: initHash.slice(0, 7),
          message: `初始化《${params.title}》本地 Git 仓库`,
          author: params.author,
          timestamp: Date.now(),
          parentHash: null,
          snapshot: JSON.parse(JSON.stringify(workspace)),
        },
      ],
      restorePoints: [],
      activePipelineRun: null,
      updatedAt: Date.now(),
    };

    setBooks((prev) => [newBook, ...prev]);
    setActiveBookId(id);
    setActiveChapterId(ch1Id);
    setActiveTab('SKILLS');
    showToast(`已创建《${params.title}》本地仓库，请先完成 AI 技能配置向导`);
  };

  const navItems: Array<{ id: ActiveTab; label: string; icon: React.ComponentType<{ className?: string }> }> = [
    { id: 'BOOKSHELF', label: '书架', icon: BookOpen },
    { id: 'EDITOR', label: '执笔', icon: Feather },
    { id: 'PLOT', label: '剧情', icon: Network },
    { id: 'SKILLS', label: '技能', icon: Sliders },
    { id: 'ROLEPLAY', label: '对戏', icon: Users },
  ];

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-[#F7F7F5] text-zinc-900 md:flex-row dark:bg-[#0E1012] dark:text-zinc-100">
      {/* Desktop & Tablet Left Navigation Rail (>= 768px) */}
      <nav className="hidden w-[68px] shrink-0 flex-col items-center justify-between border-r border-zinc-200 bg-white py-4 md:flex dark:border-zinc-800 dark:bg-[#13161A]">
        <div className="flex flex-col items-center gap-5">
          {/* QingJian monogram mark */}
          <button
            type="button"
            onClick={() => setActiveTab('BOOKSHELF')}
            style={{ backgroundColor: 'var(--qj-accent)' }}
            title="青简 Android 长篇小说工作台"
            className="font-prose flex h-10 w-10 items-center justify-center rounded-xl text-base font-bold text-white shadow-xs"
          >
            简
          </button>

          <div className="flex flex-col items-center gap-1.5">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveTab(item.id)}
                  className={`flex w-13 flex-col items-center gap-1 rounded-xl py-2 text-[11px] font-medium transition ${
                    isActive
                      ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                      : 'text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100'
                  }`}
                >
                  <Icon className="h-4 w-4" />
                  <span>{item.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="flex flex-col items-center gap-2">
          <button
            type="button"
            onClick={() => setPrefs((p) => ({ ...p, isDark: !p.isDark }))}
            title={prefs.isDark ? '切换浅色模式' : '切换深色模式'}
            className="rounded-lg p-2 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
          >
            {prefs.isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </button>
          <button
            type="button"
            onClick={() => setShowSettings(true)}
            title="引擎与 Miuix 外观设置"
            className="rounded-lg p-2 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
          >
            <Settings className="h-4 w-4" />
          </button>
        </div>
      </nav>

      {/* Main Content Stage */}
      <main className="min-h-0 min-w-0 flex-1 overflow-hidden">
        {activeTab === 'BOOKSHELF' && (
          <BookshelfView
            books={books}
            activeBookId={activeBook.id}
            onSelectBook={(bookId, openEditor) => {
              const chosen = books.find((b) => b.id === bookId);
              setActiveBookId(bookId);
              if (chosen && chosen.workspace.chapters.length > 0) {
                setActiveChapterId(
                  chosen.workspace.chapters[chosen.workspace.chapters.length - 1].chapterId,
                );
              }
              if (openEditor) setActiveTab('EDITOR');
            }}
            onCreateBook={handleCreateBook}
            onUpdateBookSettings={(bookId, patch) => {
              setBooks((prev) => prev.map((b) => (b.id === bookId ? { ...b, ...patch } : b)));
            }}
            onDeleteBook={(bookId) => {
              const remaining = books.filter((b) => b.id !== bookId);
              if (remaining.length === 0) return;
              setBooks(remaining);
              if (activeBookId === bookId) {
                setActiveBookId(remaining[0].id);
                setActiveChapterId(remaining[0].workspace.chapters[0]?.chapterId ?? '');
              }
              showToast('已删除书稿仓库');
            }}
            onToast={showToast}
          />
        )}

        {activeTab === 'EDITOR' && activeBook && (
          <MuyaEditorView
            project={activeBook}
            activeChapterId={activeChapterId}
            highlightedBlockId={highlightedBlockId}
            activeProfile={activeProfile}
            pendingSandboxReport={pendingSandboxReport}
            onClearSandboxReport={() => setPendingSandboxReport(null)}
            onSelectChapter={(chId) => setActiveChapterId(chId)}
            onClearHighlight={() => setHighlightedBlockId(null)}
            onUpdateProject={updateActiveProject}
            onLogUsage={handleLogUsage}
            onToast={showToast}
          />
        )}

        {activeTab === 'PLOT' && activeBook && (
          <PlotWorkbenchView
            project={activeBook}
            activeChapterId={activeChapterId}
            activeProfile={activeProfile}
            onJumpToParagraph={(chId, blkId) => {
              setActiveChapterId(chId);
              setHighlightedBlockId(blkId);
              setActiveTab('EDITOR');
            }}
            onCreateChapterFromPlan={(title, summary) => {
              const order = activeBook.workspace.chapters.length + 1;
              const newId = `ch_${String(order).padStart(3, '0')}_${Date.now().toString().slice(-3)}`;
              const fullTitle = `第${order}章 ${title}`;
              updateActiveProject((proj) => ({
                ...proj,
                workspace: {
                  ...proj.workspace,
                  chapters: [
                    ...proj.workspace.chapters,
                    {
                      chapterId: newId,
                      volumeTitle:
                        proj.workspace.chapters[proj.workspace.chapters.length - 1]?.volumeTitle ??
                        '第一卷',
                      title: fullTitle,
                      order,
                      status: 'DRAFT',
                      summary,
                      markdownContent: `## ${fullTitle}\n\n（由剧情策划板构思创建，可在顶部按摘要生成整章初稿或直接手写）`,
                      blockFeedback: {},
                      blockAnnotations: {},
                      updatedAt: Date.now(),
                    },
                  ],
                },
              }));
              setActiveChapterId(newId);
              setActiveTab('EDITOR');
              showToast(`已采纳构思并新建《${fullTitle}》`);
            }}
            onUpdateProject={updateActiveProject}
            onLogUsage={handleLogUsage}
            onToast={showToast}
          />
        )}

        {activeTab === 'SKILLS' && activeBook && (
          <SkillsSandboxView
            project={activeBook}
            activeChapterId={activeChapterId}
            activeProfile={activeProfile}
            onUpdateProject={updateActiveProject}
            onSendReportToEditor={(reportSummary) => {
              setPendingSandboxReport(reportSummary);
              setActiveTab('EDITOR');
              showToast('审查报告已回喂至执笔工作台，可一键交由 AI 批量修复');
            }}
            onLogUsage={handleLogUsage}
            onToast={showToast}
          />
        )}

        {activeTab === 'ROLEPLAY' && activeBook && (
          <RoleplayView
            project={activeBook}
            activeProfile={activeProfile}
            onUpdateProject={updateActiveProject}
            onAppendDraftToChapter={(text) => {
              updateActiveProject((proj) => ({
                ...proj,
                workspace: {
                  ...proj.workspace,
                  chapters: proj.workspace.chapters.map((c) =>
                    c.chapterId === activeChapterId
                      ? {
                          ...c,
                          markdownContent: `${c.markdownContent.trim()}\n\n${text.trim()}`,
                          updatedAt: Date.now(),
                        }
                      : c,
                  ),
                },
              }));
              setActiveTab('EDITOR');
              showToast('已将对戏段落追加至当前章草稿');
            }}
            onLogUsage={handleLogUsage}
            onToast={showToast}
          />
        )}
      </main>

      {/* Phone Single-Hand Bottom Navigation Bar (< 768px) */}
      <nav className="flex items-center justify-around border-t border-zinc-200 bg-white px-2 py-1.5 md:hidden dark:border-zinc-800 dark:bg-[#13161A]">
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = activeTab === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => setActiveTab(item.id)}
              style={isActive ? { color: 'var(--qj-accent)' } : undefined}
              className={`flex flex-col items-center gap-0.5 rounded-lg px-2.5 py-1 text-[10px] font-medium ${
                isActive ? '' : 'text-zinc-500 dark:text-zinc-400'
              }`}
            >
              <Icon className="h-4 w-4" />
              <span>{item.label}</span>
            </button>
          );
        })}
        <button
          type="button"
          onClick={() => setShowSettings(true)}
          className="flex flex-col items-center gap-0.5 rounded-lg px-2.5 py-1 text-[10px] font-medium text-zinc-500 dark:text-zinc-400"
        >
          <Settings className="h-4 w-4" />
          <span>设置</span>
        </button>
      </nav>

      {/* Settings Modal */}
      {showSettings && (
        <SettingsModal
          isDark={prefs.isDark}
          onToggleDark={(isDark) => setPrefs((p) => ({ ...p, isDark }))}
          accentHex={prefs.accentHex}
          onChangeAccent={(accentHex) => setPrefs((p) => ({ ...p, accentHex }))}
          profiles={prefs.profiles}
          activeProfileId={prefs.activeProfileId}
          onSelectProfile={(activeProfileId) => setPrefs((p) => ({ ...p, activeProfileId }))}
          onUpsertProfile={(profile) =>
            setPrefs((p) => ({
              ...p,
              profiles: p.profiles.some((x) => x.id === profile.id)
                ? p.profiles.map((x) => (x.id === profile.id ? profile : x))
                : [...p.profiles, profile],
            }))
          }
          onDeleteProfile={(id) =>
            setPrefs((p) => {
              const next = p.profiles.filter((x) => x.id !== id);
              return {
                ...p,
                profiles: next,
                activeProfileId:
                  p.activeProfileId === id ? (next[0]?.id ?? '') : p.activeProfileId,
              };
            })
          }
          usageLogs={prefs.usageLogs}
          onClose={() => setShowSettings(false)}
          onToast={showToast}
        />
      )}

      {/* Clean Toast Notification */}
      {toastMsg && (
        <div className="pointer-events-none fixed right-4 bottom-16 z-50 md:bottom-5">
          <div className="rounded-lg border border-zinc-800 bg-zinc-900 px-4 py-2.5 text-xs font-medium text-white shadow-lg dark:border-zinc-200 dark:bg-zinc-100 dark:text-zinc-900">
            {toastMsg}
          </div>
        </div>
      )}
    </div>
  );
}
