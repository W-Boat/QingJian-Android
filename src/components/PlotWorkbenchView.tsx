import React, { useMemo, useRef, useState } from 'react';
import {
  ArrowUpRight,
  BookMarked,
  Check,
  GitFork,
  Layers,
  MapPin,
  Network,
  Plus,
  Search,
  Sparkles,
  Trash2,
  Upload,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import { requestAiCompletion } from '../core/aiClient';
import { evaluateWorldbook, importTavernWorldbookJson } from '../core/worldbookEngine';
import {
  FORESHADOW_STAGE_LABELS,
  LORE_CATEGORY_LABELS,
  splitChapterBlocks,
  type AiAccountProfile,
  type AiUsageLog,
  type ForeshadowStage,
  type LoreCategory,
  type LoreExtractionCandidate,
  type NovelProject,
  type SecondaryKeyLogic,
  type WorldbookEntry,
} from '../core/types';

interface PlotWorkbenchViewProps {
  project: NovelProject;
  activeChapterId: string;
  activeProfile: AiAccountProfile;
  onJumpToParagraph: (chapterId: string, blockId: string) => void;
  onCreateChapterFromPlan: (title: string, summary: string) => void;
  onUpdateProject: (updater: (prev: NovelProject) => NovelProject) => void;
  onLogUsage: (usage: AiUsageLog) => void;
  onToast: (msg: string) => void;
}

type PlotTab = 'FACTS' | 'FORESHADOW' | 'CAUSAL' | 'IDEAS' | 'WORLDBOOK';

export const PlotWorkbenchView: React.FC<PlotWorkbenchViewProps> = ({
  project,
  activeChapterId,
  activeProfile,
  onJumpToParagraph,
  onCreateChapterFromPlan,
  onUpdateProject,
  onLogUsage,
  onToast,
}) => {
  const [tab, setTab] = useState<PlotTab>('FACTS');
  const [loreFilter, setLoreFilter] = useState<'ALL' | LoreCategory>('ALL');
  const [extractBusy, setExtractBusy] = useState(false);

  // Causal graph pan & zoom state
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const dragStartRef = useRef<{ x: number; y: number; origX: number; origY: number } | null>(null);
  const [newNodeTitle, setNewNodeTitle] = useState('');
  const [newEdgeFrom, setNewEdgeFrom] = useState(project.workspace.causalGraph.nodes[0]?.id ?? '');
  const [newEdgeTo, setNewEdgeTo] = useState(project.workspace.causalGraph.nodes[1]?.id ?? '');
  const [newEdgeLabel, setNewEdgeLabel] = useState('');

  // Plot idea form
  const [ideaTitle, setIdeaTitle] = useState('');
  const [ideaPremise, setIdeaPremise] = useState('');
  const [ideaTag, setIdeaTag] = useState('分支构思');

  // Worldbook test & editor state
  const [wbTestCorpus, setWbTestCorpus] = useState(
    '子时将近，沈砚推开白塔三层暗阁，看到那枚守塔司青铜印与夜禁禁令。',
  );
  const [showWbCreate, setShowWbCreate] = useState(false);
  const [wbDraft, setWbDraft] = useState<{
    title: string;
    content: string;
    alwaysActive: boolean;
    primaryKeys: string;
    secondaryKeys: string;
    secondaryLogic: SecondaryKeyLogic;
    priority: number;
    recursiveScan: boolean;
    category: string;
  }>({
    title: '',
    content: '',
    alwaysActive: false,
    primaryKeys: '',
    secondaryKeys: '',
    secondaryLogic: 'AND_ANY',
    priority: 75,
    recursiveScan: true,
    category: '世界规则',
  });
  const [showWbImport, setShowWbImport] = useState(false);
  const [wbImportJson, setWbImportJson] = useState('');

  const activeChapter =
    project.workspace.chapters.find((c) => c.chapterId === activeChapterId) ??
    project.workspace.chapters[0];

  const wbPreview = useMemo(
    () =>
      evaluateWorldbook(
        project.workspace.worldbookEntries,
        wbTestCorpus,
        project.contextConfig.worldbookCharBudget,
        3,
      ),
    [project.workspace.worldbookEntries, wbTestCorpus, project.contextConfig.worldbookCharBudget],
  );

  const handleAiExtractLore = async () => {
    setExtractBusy(true);
    try {
      const blocks = splitChapterBlocks(activeChapter);
      const res = await requestAiCompletion({
        profile: activeProfile,
        systemPrompt:
          '你是青简资料提取 Subagent。请从章节提取 1-3 条真实出现的待审事实候选（类别限定为 CHARACTER/WORLD_RULE/SCENE/EVENT/ANNOTATION），返回纯 JSON 数组，每项含 category, title, summary, verbatimExcerpt。严禁 emoji。',
        userPrompt: `请从《${activeChapter.title}》提取资料候选：\n${activeChapter.markdownContent}`,
        taskLabel: '五类资料候选提取',
      });
      onLogUsage(res.usage);

      const arrMatch = res.text.match(/\[[\s\S]*\]/);
      if (!arrMatch) {
        throw new Error('模型未返回合法的 JSON 候选数组，请重试');
      }
      const parsed = JSON.parse(arrMatch[0]) as Array<Partial<LoreExtractionCandidate>>;
      const extracted = parsed
        .filter((item) => item.title && item.summary)
        .map((item, idx): LoreExtractionCandidate => {
          const excerpt = String(item.verbatimExcerpt ?? '').trim();
          const matchedBlock =
            blocks.find((b) => excerpt && b.markdown.includes(excerpt.slice(0, 12))) ??
            blocks[1] ??
            blocks[0];
          return {
            id: `cand_${Date.now()}_${idx}`,
            category: (['CHARACTER', 'WORLD_RULE', 'SCENE', 'EVENT', 'ANNOTATION'].includes(
              String(item.category),
            )
              ? item.category
              : 'EVENT') as LoreCategory,
            title: String(item.title),
            summary: String(item.summary),
            chapterId: activeChapter.chapterId,
            chapterTitle: activeChapter.title,
            blockId: matchedBlock?.id ?? '',
            verbatimExcerpt: excerpt || (matchedBlock?.markdown ?? '').slice(0, 56),
          };
        });
      if (extracted.length === 0) {
        throw new Error('本章未识别出新的资料候选');
      }

      onUpdateProject((proj) => ({
        ...proj,
        workspace: {
          ...proj.workspace,
          loreCandidates: [...extracted, ...proj.workspace.loreCandidates],
        },
      }));
      onToast(`已从《${activeChapter.title}》提取 ${extracted.length} 条待审资料候选`);
    } catch (err) {
      onToast(err instanceof Error ? err.message : 'AI 资料提取失败');
    } finally {
      setExtractBusy(false);
    }
  };

  const approveLoreCandidate = (cand: LoreExtractionCandidate) => {
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        loreCandidates: proj.workspace.loreCandidates.filter((c) => c.id !== cand.id),
        loreEntries: [
          {
            id: `lore_${Date.now()}`,
            category: cand.category,
            title: cand.title,
            content: cand.summary,
            sourceChapterId: cand.chapterId,
            sourceBlockId: cand.blockId,
            sourceExcerpt: cand.verbatimExcerpt,
          },
          ...proj.workspace.loreEntries,
        ],
      },
    }));
    onToast(`已批准「${cand.title}」归入正式事实档案`);
  };

  const advanceForeshadowStage = (threadId: string, nextStage: ForeshadowStage) => {
    const firstBlock = splitChapterBlocks(activeChapter)[0];
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        foreshadows: proj.workspace.foreshadows.map((f) => {
          if (f.id !== threadId) return f;
          return {
            ...f,
            isResolved: f.isResolved || nextStage === 'PAYOFF',
            anchors: [
              ...f.anchors,
              {
                id: `fa_${Date.now()}`,
                stage: nextStage,
                chapterId: activeChapter.chapterId,
                chapterTitle: activeChapter.title,
                blockId: firstBlock?.id ?? '',
                excerpt: (firstBlock?.markdown ?? activeChapter.title).slice(0, 56),
                note: `在剧情工作台推进至「${FORESHADOW_STAGE_LABELS[nextStage]}」`,
                createdAt: Date.now(),
              },
            ],
          };
        }),
      },
    }));
    onToast(
      nextStage === 'PAYOFF'
        ? '伏笔已登记「回收」并自动结案'
        : `伏笔已登记「${FORESHADOW_STAGE_LABELS[nextStage]}」`,
    );
  };

  const handleImportWorldbookJson = () => {
    try {
      const imported = importTavernWorldbookJson(wbImportJson);
      if (imported.length === 0) {
        onToast('未解析到有效世界书条目');
        return;
      }
      onUpdateProject((proj) => ({
        ...proj,
        workspace: {
          ...proj.workspace,
          worldbookEntries: [...imported, ...proj.workspace.worldbookEntries],
        },
      }));
      setWbImportJson('');
      setShowWbImport(false);
      onToast(`已导入 ${imported.length} 条酒馆世界书条目`);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '酒馆世界书 JSON 解析失败');
    }
  };

  const handleAddWorldbookEntry = () => {
    if (!wbDraft.title.trim() || !wbDraft.content.trim()) return;
    const entry: WorldbookEntry = {
      id: `wb_${Date.now()}`,
      title: wbDraft.title.trim(),
      content: wbDraft.content.trim(),
      enabled: true,
      alwaysActive: wbDraft.alwaysActive,
      primaryKeys: wbDraft.primaryKeys.split(/[,，]/).map((s) => s.trim()).filter(Boolean),
      secondaryKeys: wbDraft.secondaryKeys.split(/[,，]/).map((s) => s.trim()).filter(Boolean),
      secondaryLogic: wbDraft.secondaryLogic,
      priority: wbDraft.priority,
      recursiveScan: wbDraft.recursiveScan,
      category: wbDraft.category.trim() || '设定条目',
    };
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        worldbookEntries: [entry, ...proj.workspace.worldbookEntries],
      },
    }));
    setWbDraft({
      title: '',
      content: '',
      alwaysActive: false,
      primaryKeys: '',
      secondaryKeys: '',
      secondaryLogic: 'AND_ANY',
      priority: 75,
      recursiveScan: true,
      category: '世界规则',
    });
    setShowWbCreate(false);
    onToast(`已新增世界书条目「${entry.title}」`);
  };

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#F7F7F5] dark:bg-[#0E1012]">
      {/* Header & Strict Facts vs Ideas Tabs */}
      <div className="border-b border-zinc-200 bg-white px-5 pt-4 dark:border-zinc-800 dark:bg-[#13161A]">
        <div className="mx-auto max-w-6xl">
          <div className="flex flex-wrap items-center justify-between gap-2 pb-3">
            <div>
              <h1 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
                剧情工作台与世界书 · {project.title}
              </h1>
              <p className="text-xs text-zinc-500">
                严格隔离「已定稿事实档案」与「待选构思策划」，所有伏笔与因果连线均可追溯原文段落
              </p>
            </div>
            <div className="font-mono-tabular text-xs text-zinc-500">
              事实 {project.workspace.loreEntries.length} · 伏笔{' '}
              {project.workspace.foreshadows.length} · 构思 {project.workspace.plotPlans.length} ·
              世界书 {project.workspace.worldbookEntries.length}
            </div>
          </div>

          <div className="flex gap-1 overflow-x-auto">
            {[
              { id: 'FACTS', label: '事实档案（已定稿）', icon: BookMarked },
              { id: 'FORESHADOW', label: '伏笔五阶段档案', icon: MapPin },
              { id: 'CAUSAL', label: '因果关系画布', icon: Network },
              { id: 'IDEAS', label: '剧情策划（未落稿构思）', icon: GitFork },
              { id: 'WORLDBOOK', label: '酒馆级世界书引擎', icon: Layers },
            ].map((item) => {
              const Icon = item.icon;
              const active = tab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setTab(item.id as PlotTab)}
                  className={`flex items-center gap-1.5 border-b-2 px-3.5 py-2.5 text-xs font-medium whitespace-nowrap shrink-0 transition ${
                    active
                      ? 'border-zinc-900 text-zinc-900 dark:border-zinc-100 dark:text-zinc-100'
                      : 'border-transparent text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-200'
                  }`}
                >
                  <Icon className="h-3.5 w-3.5" />
                  {item.label}
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-5 py-6">
        <div className="mx-auto max-w-6xl">
          {/* TAB 1: FACTS & 5-CATEGORY EXTRACTION CANDIDATES */}
          {tab === 'FACTS' && (
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
              {/* Pending AI Extraction Candidates */}
              <div className="space-y-3 lg:col-span-5">
                <div className="flex items-center justify-between">
                  <div>
                    <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                      AI 五类提取待审候选 ({project.workspace.loreCandidates.length})
                    </h2>
                    <p className="text-[11px] text-zinc-500">
                      附原文摘录与跳转锚点，经作者审阅批准后才写入事实库
                    </p>
                  </div>
                  <button
                    type="button"
                    disabled={extractBusy}
                    onClick={handleAiExtractLore}
                    style={{ backgroundColor: 'var(--qj-accent)' }}
                    className="flex items-center gap-1 rounded-md px-2.5 py-1.5 text-xs font-medium text-white whitespace-nowrap shrink-0 disabled:opacity-50"
                  >
                    <Sparkles className="h-3.5 w-3.5" />
                    {extractBusy ? '提取中…' : '从本章提取'}
                  </button>
                </div>

                {project.workspace.loreCandidates.length === 0 ? (
                  <div className="rounded-xl border border-zinc-200 bg-white p-6 text-center text-xs text-zinc-500 dark:border-zinc-800 dark:bg-[#16191D]">
                    当前无待审资料候选，可点击「从本章提取」由 Subagent 扫描正文。
                  </div>
                ) : (
                  project.workspace.loreCandidates.map((cand) => (
                    <div
                      key={cand.id}
                      className="rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#16191D]"
                    >
                      <div className="flex items-center justify-between text-[11px] text-zinc-500">
                        <span>
                          {LORE_CATEGORY_LABELS[cand.category]} · {cand.chapterTitle}
                        </span>
                        <button
                          type="button"
                          onClick={() => onJumpToParagraph(cand.chapterId, cand.blockId)}
                          className="flex items-center gap-0.5 text-zinc-700 hover:underline dark:text-zinc-300"
                        >
                          定位原段 <ArrowUpRight className="h-3 w-3" />
                        </button>
                      </div>
                      <h3 className="mt-1 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                        {cand.title}
                      </h3>
                      <p className="mt-1 text-xs leading-relaxed text-zinc-600 dark:text-zinc-400">
                        {cand.summary}
                      </p>
                      <div className="mt-2.5 rounded-md bg-zinc-50 px-3 py-2 font-prose text-xs text-zinc-600 dark:bg-zinc-900 dark:text-zinc-400">
                        原文摘录：「{cand.verbatimExcerpt}」
                      </div>
                      <div className="mt-3 flex justify-end gap-2">
                        <button
                          type="button"
                          onClick={() =>
                            onUpdateProject((p) => ({
                              ...p,
                              workspace: {
                                ...p.workspace,
                                loreCandidates: p.workspace.loreCandidates.filter(
                                  (c) => c.id !== cand.id,
                                ),
                              },
                            }))
                          }
                          className="rounded-md border border-zinc-200 px-2.5 py-1 text-xs text-zinc-500 hover:bg-zinc-50 dark:border-zinc-700"
                        >
                          驳回
                        </button>
                        <button
                          type="button"
                          onClick={() => approveLoreCandidate(cand)}
                          style={{ backgroundColor: 'var(--qj-accent)' }}
                          className="flex items-center gap-1 rounded-md px-3 py-1 text-xs font-medium text-white"
                        >
                          <Check className="h-3.5 w-3.5" />
                          批准归档
                        </button>
                      </div>
                    </div>
                  ))
                )}
              </div>

              {/* Confirmed Lore Facts */}
              <div className="space-y-3 lg:col-span-7">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                    已确认事实档案（单一权威事实来源）
                  </h2>
                  <div className="flex flex-wrap gap-1">
                    {(['ALL', 'CHARACTER', 'WORLD_RULE', 'SCENE', 'EVENT', 'ANNOTATION'] as const).map(
                      (cat) => (
                        <button
                          key={cat}
                          type="button"
                          onClick={() => setLoreFilter(cat)}
                          className={`rounded-md px-2 py-1 text-[11px] font-medium transition ${
                            loreFilter === cat
                              ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                              : 'text-zinc-500 hover:bg-zinc-200/60 dark:hover:bg-zinc-800'
                          }`}
                        >
                          {cat === 'ALL' ? '全部' : LORE_CATEGORY_LABELS[cat]}
                        </button>
                      ),
                    )}
                  </div>
                </div>

                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {project.workspace.loreEntries
                    .filter((e) => loreFilter === 'ALL' || e.category === loreFilter)
                    .map((entry) => (
                      <div
                        key={entry.id}
                        className="flex flex-col justify-between rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#16191D]"
                      >
                        <div>
                          <div className="flex items-center justify-between text-[11px] text-zinc-500">
                            <span>{LORE_CATEGORY_LABELS[entry.category]}</span>
                            <button
                              type="button"
                              onClick={() =>
                                onJumpToParagraph(entry.sourceChapterId, entry.sourceBlockId)
                              }
                              className="flex items-center gap-0.5 hover:text-zinc-900 dark:hover:text-zinc-100"
                            >
                              跳转出处 <ArrowUpRight className="h-3 w-3" />
                            </button>
                          </div>
                          <h3 className="mt-1 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                            {entry.title}
                          </h3>
                          <p className="mt-1 text-xs leading-relaxed text-zinc-600 dark:text-zinc-400">
                            {entry.content}
                          </p>
                        </div>
                        <p className="mt-3 border-t border-zinc-100 pt-2 text-[11px] text-zinc-400 dark:border-zinc-800">
                          依据：「{entry.sourceExcerpt}」
                        </p>
                      </div>
                    ))}
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: 5-STAGE FORESHADOW ARCHIVE */}
          {tab === 'FORESHADOW' && (
            <div className="space-y-4">
              {project.workspace.foreshadows.map((thread) => (
                <div
                  key={thread.id}
                  className="rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-[#16191D]"
                >
                  <div className="flex flex-wrap items-start justify-between gap-3 border-b border-zinc-100 pb-3 dark:border-zinc-800">
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                          {thread.title}
                        </h3>
                        <span
                          className={`text-xs font-medium ${
                            thread.isResolved
                              ? 'text-emerald-700 dark:text-emerald-400'
                              : 'text-amber-700 dark:text-amber-400'
                          }`}
                        >
                          · {thread.isResolved ? '已回收结案' : '跨章追踪中'}
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-zinc-600 dark:text-zinc-400">
                        {thread.description}
                      </p>
                    </div>

                    {!thread.isResolved && (
                      <div className="flex flex-wrap items-center gap-1.5">
                        {(['CLUE', 'ECHO', 'PAYOFF'] as ForeshadowStage[]).map((st) => (
                          <button
                            key={st}
                            type="button"
                            onClick={() => advanceForeshadowStage(thread.id, st)}
                            className={`rounded-md px-2.5 py-1 text-xs font-medium ${
                              st === 'PAYOFF'
                                ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                                : 'border border-zinc-200 text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300'
                            }`}
                          >
                            + 登记{FORESHADOW_STAGE_LABELS[st]}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* 5-stage horizontal progress track */}
                  <div className="mt-4 grid grid-cols-5 gap-2 text-center text-[11px]">
                    {(['SETUP', 'PLANT', 'CLUE', 'ECHO', 'PAYOFF'] as ForeshadowStage[]).map(
                      (stage) => {
                        const hit = thread.anchors.some((a) => a.stage === stage);
                        return (
                          <div
                            key={stage}
                            className={`rounded-md border py-1.5 font-medium ${
                              hit
                                ? 'border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900'
                                : 'border-zinc-200 text-zinc-400 dark:border-zinc-800'
                            }`}
                          >
                            {FORESHADOW_STAGE_LABELS[stage]}
                          </div>
                        );
                      },
                    )}
                  </div>

                  {/* Anchor timeline with 1-click jump & highlight */}
                  <div className="mt-4 space-y-2">
                    {thread.anchors.map((anchor) => (
                      <div
                        key={anchor.id}
                        className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-zinc-200 bg-zinc-50/70 px-3.5 py-2.5 text-xs dark:border-zinc-800 dark:bg-zinc-900/60"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="font-medium text-zinc-900 dark:text-zinc-100">
                            【{FORESHADOW_STAGE_LABELS[anchor.stage]}】{anchor.chapterTitle} ·{' '}
                            <span className="font-normal text-zinc-500">{anchor.note}</span>
                          </div>
                          <p className="mt-0.5 truncate font-prose text-zinc-600 dark:text-zinc-400">
                            「{anchor.excerpt}」
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => onJumpToParagraph(anchor.chapterId, anchor.blockId)}
                          style={{ color: 'var(--qj-accent)' }}
                          className="flex items-center gap-0.5 text-xs font-medium whitespace-nowrap shrink-0 hover:underline"
                        >
                          跳转高亮原段 <ArrowUpRight className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* TAB 3: INTERACTIVE PAN/ZOOM CAUSAL GRAPH */}
          {tab === 'CAUSAL' && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-zinc-200 bg-white px-4 py-3 text-xs dark:border-zinc-800 dark:bg-[#16191D]">
                <div className="flex flex-wrap items-center gap-3 text-zinc-600 dark:text-zinc-400">
                  <span>拖拽画布平移 · 按钮缩放</span>
                  <span>实线箭头 = 作者已确认因果</span>
                  <span>虚线箭头 = AI 自动建议（点击下方确认按钮转实线）</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => setZoom((z) => Math.max(0.6, +(z - 0.15).toFixed(2)))}
                    className="rounded border border-zinc-200 p-1.5 dark:border-zinc-700"
                  >
                    <ZoomOut className="h-3.5 w-3.5" />
                  </button>
                  <span className="font-mono-tabular w-12 text-center">
                    {Math.round(zoom * 100)}%
                  </span>
                  <button
                    type="button"
                    onClick={() => setZoom((z) => Math.min(1.8, +(z + 0.15).toFixed(2)))}
                    className="rounded border border-zinc-200 p-1.5 dark:border-zinc-700"
                  >
                    <ZoomIn className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>

              {/* Pan & Zoom SVG Canvas */}
              <div
                onPointerDown={(e) => {
                  dragStartRef.current = {
                    x: e.clientX,
                    y: e.clientY,
                    origX: pan.x,
                    origY: pan.y,
                  };
                }}
                onPointerMove={(e) => {
                  if (!dragStartRef.current) return;
                  setPan({
                    x: dragStartRef.current.origX + (e.clientX - dragStartRef.current.x),
                    y: dragStartRef.current.origY + (e.clientY - dragStartRef.current.y),
                  });
                }}
                onPointerUp={() => {
                  dragStartRef.current = null;
                }}
                onPointerLeave={() => {
                  dragStartRef.current = null;
                }}
                className="relative h-[360px] w-full cursor-grab overflow-hidden rounded-xl border border-zinc-200 bg-white active:cursor-grabbing dark:border-zinc-800 dark:bg-[#13161A]"
              >
                <svg className="h-full w-full">
                  <defs>
                    <marker
                      id="arrow-solid"
                      markerWidth="10"
                      markerHeight="7"
                      refX="9"
                      refY="3.5"
                      orient="auto"
                    >
                      <polygon points="0 0, 10 3.5, 0 7" fill="#1F7A6E" />
                    </marker>
                    <marker
                      id="arrow-dashed"
                      markerWidth="10"
                      markerHeight="7"
                      refX="9"
                      refY="3.5"
                      orient="auto"
                    >
                      <polygon points="0 0, 10 3.5, 0 7" fill="#D97706" />
                    </marker>
                  </defs>
                  <g transform={`translate(${pan.x}, ${pan.y}) scale(${zoom})`}>
                    {/* Edges */}
                    {project.workspace.causalGraph.edges.map((edge) => {
                      const from = project.workspace.causalGraph.nodes.find(
                        (n) => n.id === edge.fromNodeId,
                      );
                      const to = project.workspace.causalGraph.nodes.find(
                        (n) => n.id === edge.toNodeId,
                      );
                      if (!from || !to) return null;
                      const x1 = from.x + 95;
                      const y1 = from.y + 34;
                      const x2 = to.x + 95;
                      const y2 = to.y + 34;
                      return (
                        <g key={edge.id}>
                          <line
                            x1={x1}
                            y1={y1}
                            x2={x2}
                            y2={y2}
                            stroke={edge.confirmed ? '#1F7A6E' : '#D97706'}
                            strokeWidth={2}
                            strokeDasharray={edge.confirmed ? undefined : '6 4'}
                            markerEnd={edge.confirmed ? 'url(#arrow-solid)' : 'url(#arrow-dashed)'}
                          />
                          <text
                            x={(x1 + x2) / 2}
                            y={(y1 + y2) / 2 - 6}
                            textAnchor="middle"
                            fontSize={11}
                            className="fill-zinc-600 dark:fill-zinc-400"
                          >
                            {edge.relationLabel}
                          </text>
                        </g>
                      );
                    })}

                    {/* Nodes */}
                    {project.workspace.causalGraph.nodes.map((node) => (
                      <g key={node.id} transform={`translate(${node.x}, ${node.y})`}>
                        <rect
                          width={190}
                          height={68}
                          rx={10}
                          className="fill-white stroke-zinc-300 dark:fill-[#1B2026] dark:stroke-zinc-700"
                          strokeWidth={1.25}
                        />
                        <text
                          x={12}
                          y={24}
                          fontSize={12.5}
                          fontWeight={600}
                          className="fill-zinc-900 dark:fill-zinc-100"
                        >
                          {node.title}
                        </text>
                        <text
                          x={12}
                          y={46}
                          fontSize={10.5}
                          className="fill-zinc-500 dark:fill-zinc-400"
                        >
                          {node.detail.slice(0, 16)}
                        </text>
                      </g>
                    ))}
                  </g>
                </svg>
              </div>

              {/* Edge confirmation & manual authoring */}
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                <div className="rounded-xl border border-zinc-200 bg-white p-4 text-xs dark:border-zinc-800 dark:bg-[#16191D]">
                  <h3 className="mb-2.5 font-semibold text-zinc-900 dark:text-zinc-100">
                    因果连线审核（虚线 AI 建议 → 实线正式确认）
                  </h3>
                  <div className="space-y-2">
                    {project.workspace.causalGraph.edges.map((edge) => {
                      const from = project.workspace.causalGraph.nodes.find(
                        (n) => n.id === edge.fromNodeId,
                      );
                      const to = project.workspace.causalGraph.nodes.find(
                        (n) => n.id === edge.toNodeId,
                      );
                      return (
                        <div
                          key={edge.id}
                          className="flex items-center justify-between gap-2 rounded-lg border border-zinc-200 px-3 py-2 dark:border-zinc-800"
                        >
                          <div>
                            <div className="font-medium text-zinc-900 dark:text-zinc-100">
                              {from?.title} → {to?.title}
                            </div>
                            <div className="text-[11px] text-zinc-500">
                              {edge.relationLabel} · {edge.confirmed ? '实线已确认' : '虚线 AI 建议'}
                            </div>
                          </div>
                          {!edge.confirmed && (
                            <button
                              type="button"
                              onClick={() => {
                                onUpdateProject((p) => ({
                                  ...p,
                                  workspace: {
                                    ...p.workspace,
                                    causalGraph: {
                                      ...p.workspace.causalGraph,
                                      edges: p.workspace.causalGraph.edges.map((e) =>
                                        e.id === edge.id ? { ...e, confirmed: true } : e,
                                      ),
                                    },
                                  },
                                }));
                                onToast('已手动确认因果连线，箭头已转为实线');
                              }}
                              style={{ backgroundColor: 'var(--qj-accent)' }}
                              className="rounded-md px-2.5 py-1 font-medium text-white whitespace-nowrap shrink-0"
                            >
                              确认转实线
                            </button>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>

                <div className="rounded-xl border border-zinc-200 bg-white p-4 text-xs space-y-3 dark:border-zinc-800 dark:bg-[#16191D]">
                  <h3 className="font-semibold text-zinc-900 dark:text-zinc-100">
                    手动添加事件节点与连线
                  </h3>
                  <div className="flex gap-2">
                    <input
                      value={newNodeTitle}
                      onChange={(e) => setNewNodeTitle(e.target.value)}
                      placeholder="新事件节点标题"
                      className="flex-1 rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                    />
                    <button
                      type="button"
                      onClick={() => {
                        if (!newNodeTitle.trim()) return;
                        const count = project.workspace.causalGraph.nodes.length;
                        onUpdateProject((p) => ({
                          ...p,
                          workspace: {
                            ...p.workspace,
                            causalGraph: {
                              ...p.workspace.causalGraph,
                              nodes: [
                                ...p.workspace.causalGraph.nodes,
                                {
                                  id: `n_${Date.now()}`,
                                  chapterId: activeChapter.chapterId,
                                  title: newNodeTitle.trim(),
                                  detail: `关联章节：${activeChapter.title}`,
                                  x: 60 + (count % 3) * 230,
                                  y: 60 + Math.floor(count / 3) * 130,
                                },
                              ],
                            },
                          },
                        }));
                        setNewNodeTitle('');
                        onToast('已添加事件节点');
                      }}
                      className="rounded-md border border-zinc-300 px-3 py-1.5 font-medium dark:border-zinc-700"
                    >
                      + 节点
                    </button>
                  </div>

                  <div className="grid grid-cols-2 gap-2">
                    <select
                      value={newEdgeFrom}
                      onChange={(e) => setNewEdgeFrom(e.target.value)}
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-1.5 dark:border-zinc-700 dark:bg-zinc-900"
                    >
                      {project.workspace.causalGraph.nodes.map((n) => (
                        <option key={n.id} value={n.id}>
                          因：{n.title}
                        </option>
                      ))}
                    </select>
                    <select
                      value={newEdgeTo}
                      onChange={(e) => setNewEdgeTo(e.target.value)}
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-1.5 dark:border-zinc-700 dark:bg-zinc-900"
                    >
                      {project.workspace.causalGraph.nodes.map((n) => (
                        <option key={n.id} value={n.id}>
                          果：{n.title}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="flex gap-2">
                    <input
                      value={newEdgeLabel}
                      onChange={(e) => setNewEdgeLabel(e.target.value)}
                      placeholder="因果关系说明"
                      className="flex-1 rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                    />
                    <button
                      type="button"
                      onClick={() => {
                        if (!newEdgeFrom || !newEdgeTo || newEdgeFrom === newEdgeTo) return;
                        onUpdateProject((p) => ({
                          ...p,
                          workspace: {
                            ...p.workspace,
                            causalGraph: {
                              ...p.workspace.causalGraph,
                              edges: [
                                ...p.workspace.causalGraph.edges,
                                {
                                  id: `e_${Date.now()}`,
                                  fromNodeId: newEdgeFrom,
                                  toNodeId: newEdgeTo,
                                  relationLabel: newEdgeLabel.trim() || '触发因果',
                                  confirmed: true,
                                  aiSuggested: false,
                                },
                              ],
                            },
                          },
                        }));
                        setNewEdgeLabel('');
                        onToast('已创建实线因果连线');
                      }}
                      style={{ backgroundColor: 'var(--qj-accent)' }}
                      className="rounded-md px-3 py-1.5 font-medium text-white whitespace-nowrap shrink-0"
                    >
                      连接节点
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* TAB 4: DEDICATED PLOT PLANNING VIEW (ISOLATED IDEAS) */}
          {tab === 'IDEAS' && (
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
              <div className="space-y-3 lg:col-span-8">
                <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                  剧情策划板（严格与已定稿事实分离，仅存放未落稿走向与分支设想）
                </h2>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  {project.workspace.plotPlans.map((plan) => (
                    <div
                      key={plan.id}
                      className="flex flex-col justify-between rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#16191D]"
                    >
                      <div>
                        <div className="flex items-center justify-between text-[11px] text-zinc-500">
                          <span>未落稿构思 · {plan.branchTag}</span>
                          <button
                            type="button"
                            onClick={() =>
                              onUpdateProject((p) => ({
                                ...p,
                                workspace: {
                                  ...p.workspace,
                                  plotPlans: p.workspace.plotPlans.filter((x) => x.id !== plan.id),
                                },
                              }))
                            }
                            className="text-zinc-400 hover:text-rose-500"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                        <h3 className="mt-1 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                          {plan.title}
                        </h3>
                        <p className="mt-1.5 text-xs leading-relaxed text-zinc-600 dark:text-zinc-400">
                          {plan.premise}
                        </p>
                        {plan.keyBeats.length > 0 && (
                          <ul className="mt-2.5 list-disc space-y-0.5 pl-4 text-[11px] text-zinc-500">
                            {plan.keyBeats.map((beat, idx) => (
                              <li key={idx}>{beat}</li>
                            ))}
                          </ul>
                        )}
                      </div>
                      <button
                        type="button"
                        onClick={() =>
                          onCreateChapterFromPlan(
                            plan.title.replace(/^[^：]+：/, ''),
                            `${plan.premise} 关键节拍：${plan.keyBeats.join('；')}`,
                          )
                        }
                        style={{ color: 'var(--qj-accent)' }}
                        className="mt-4 self-start text-xs font-medium hover:underline"
                      >
                        采纳此构思并新建下一章 →
                      </button>
                    </div>
                  ))}
                </div>
              </div>

              <div className="lg:col-span-4">
                <div className="rounded-xl border border-zinc-200 bg-white p-4 text-xs space-y-3 dark:border-zinc-800 dark:bg-[#16191D]">
                  <h3 className="font-semibold text-zinc-900 dark:text-zinc-100">
                    新增未落稿分支构思
                  </h3>
                  <input
                    value={ideaTitle}
                    onChange={(e) => setIdeaTitle(e.target.value)}
                    placeholder="构思标题（如：卷末分支 C：假死脱身）"
                    className="w-full rounded-md border border-zinc-200 bg-zinc-50 px-3 py-2 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                  <input
                    value={ideaTag}
                    onChange={(e) => setIdeaTag(e.target.value)}
                    placeholder="分支标签"
                    className="w-full rounded-md border border-zinc-200 bg-zinc-50 px-3 py-2 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                  <textarea
                    rows={3}
                    value={ideaPremise}
                    onChange={(e) => setIdeaPremise(e.target.value)}
                    placeholder="简述走向前提与核心冲突"
                    className="w-full rounded-md border border-zinc-200 bg-zinc-50 p-2.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                  <button
                    type="button"
                    onClick={() => {
                      if (!ideaTitle.trim()) return;
                      onUpdateProject((p) => ({
                        ...p,
                        workspace: {
                          ...p.workspace,
                          plotPlans: [
                            {
                              id: `plan_${Date.now()}`,
                              title: ideaTitle.trim(),
                              sourceChapterId: activeChapter.chapterId,
                              premise: ideaPremise.trim() || '待补充详细节拍',
                              keyBeats: [],
                              branchTag: ideaTag.trim() || '分支构思',
                            },
                            ...p.workspace.plotPlans,
                          ],
                        },
                      }));
                      setIdeaTitle('');
                      setIdeaPremise('');
                      onToast('已添加剧情构思（未污染事实档案）');
                    }}
                    style={{ backgroundColor: 'var(--qj-accent)' }}
                    className="w-full rounded-md py-2 font-medium text-white"
                  >
                    存入策划板
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* TAB 5: TAVERN-GRADE ADVANCED WORLDBOOK ENGINE */}
          {tab === 'WORLDBOOK' && (
            <div className="space-y-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                    酒馆级世界书引擎（常驻 / 主次关键词 AND·OR·NOT / 优先级 / 递归扫描 / 预算压缩）
                  </h2>
                  <p className="text-xs text-zinc-500">
                    兼容 SillyTavern World Info JSON 导入，与「角色对戏排练」实时共享同一套世界书
                  </p>
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setShowWbImport((v) => !v)}
                    className="flex items-center gap-1 rounded-md border border-zinc-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
                  >
                    <Upload className="h-3.5 w-3.5" />
                    导入酒馆 JSON
                  </button>
                  <button
                    type="button"
                    onClick={() => setShowWbCreate((v) => !v)}
                    style={{ backgroundColor: 'var(--qj-accent)' }}
                    className="flex items-center gap-1 rounded-md px-3 py-1.5 text-xs font-medium text-white"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    新增条目
                  </button>
                </div>
              </div>

              {showWbImport && (
                <div className="rounded-xl border border-zinc-200 bg-white p-4 text-xs space-y-2.5 dark:border-zinc-800 dark:bg-[#16191D]">
                  <div className="font-semibold text-zinc-900 dark:text-zinc-100">
                    粘贴酒馆（SillyTavern）世界书 JSON
                  </div>
                  <textarea
                    rows={4}
                    value={wbImportJson}
                    onChange={(e) => setWbImportJson(e.target.value)}
                    placeholder='{"entries":{"0":{"comment":"司礼监密档","key":["司礼监"],"keysecondary":["勘合"],"selective":true,"selectiveLogic":0,"order":95,"content":"..."}}}'
                    className="font-mono-tabular w-full rounded-md border border-zinc-200 bg-zinc-50 p-2.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => setShowWbImport(false)}
                      className="rounded border border-zinc-200 px-3 py-1 dark:border-zinc-700"
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      onClick={handleImportWorldbookJson}
                      style={{ backgroundColor: 'var(--qj-accent)' }}
                      className="rounded px-3 py-1 font-medium text-white"
                    >
                      解析并导入
                    </button>
                  </div>
                </div>
              )}

              {showWbCreate && (
                <div className="rounded-xl border border-zinc-200 bg-white p-4 text-xs space-y-3 dark:border-zinc-800 dark:bg-[#16191D]">
                  <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
                    <input
                      value={wbDraft.title}
                      onChange={(e) => setWbDraft({ ...wbDraft, title: e.target.value })}
                      placeholder="条目标题"
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 dark:border-zinc-700 dark:bg-zinc-900"
                    />
                    <input
                      value={wbDraft.primaryKeys}
                      onChange={(e) => setWbDraft({ ...wbDraft, primaryKeys: e.target.value })}
                      placeholder="主触发词（逗号分隔）"
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 dark:border-zinc-700 dark:bg-zinc-900"
                    />
                    <input
                      value={wbDraft.secondaryKeys}
                      onChange={(e) => setWbDraft({ ...wbDraft, secondaryKeys: e.target.value })}
                      placeholder="次级条件词（逗号分隔）"
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 dark:border-zinc-700 dark:bg-zinc-900"
                    />
                  </div>
                  <div className="flex flex-wrap items-center gap-4">
                    <select
                      value={wbDraft.secondaryLogic}
                      onChange={(e) =>
                        setWbDraft({
                          ...wbDraft,
                          secondaryLogic: e.target.value as SecondaryKeyLogic,
                        })
                      }
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-1 dark:border-zinc-700 dark:bg-zinc-900"
                    >
                      <option value="NONE">仅主词触发 (NONE)</option>
                      <option value="AND_ANY">主词 AND 任一次级词 (AND_ANY)</option>
                      <option value="AND_ALL">主词 AND 全部次级词 (AND_ALL)</option>
                      <option value="OR">主词 OR 次级词 (OR)</option>
                      <option value="NOT_ANY">主词 NOT 任一次级词 (NOT_ANY)</option>
                      <option value="NOT_ALL">主词 NOT 全部次级词 (NOT_ALL)</option>
                    </select>
                    <label className="flex items-center gap-1">
                      优先级
                      <input
                        type="number"
                        value={wbDraft.priority}
                        onChange={(e) =>
                          setWbDraft({ ...wbDraft, priority: Number(e.target.value) || 50 })
                        }
                        className="font-mono-tabular w-16 rounded border border-zinc-200 bg-zinc-50 px-1.5 py-0.5 dark:border-zinc-700 dark:bg-zinc-900"
                      />
                    </label>
                    <label className="flex items-center gap-1">
                      <input
                        type="checkbox"
                        checked={wbDraft.alwaysActive}
                        onChange={(e) => setWbDraft({ ...wbDraft, alwaysActive: e.target.checked })}
                      />
                      常驻条目
                    </label>
                    <label className="flex items-center gap-1">
                      <input
                        type="checkbox"
                        checked={wbDraft.recursiveScan}
                        onChange={(e) =>
                          setWbDraft({ ...wbDraft, recursiveScan: e.target.checked })
                        }
                      />
                      允许递归触发下一层
                    </label>
                  </div>
                  <textarea
                    rows={3}
                    value={wbDraft.content}
                    onChange={(e) => setWbDraft({ ...wbDraft, content: e.target.value })}
                    placeholder="世界书设定正文（可含指向其他条目的关键词以触发递归扫描）"
                    className="w-full rounded-md border border-zinc-200 bg-zinc-50 p-2.5 dark:border-zinc-700 dark:bg-zinc-900"
                  />
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => setShowWbCreate(false)}
                      className="rounded border border-zinc-200 px-3 py-1 dark:border-zinc-700"
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      onClick={handleAddWorldbookEntry}
                      style={{ backgroundColor: 'var(--qj-accent)' }}
                      className="rounded px-3.5 py-1 font-medium text-white"
                    >
                      保存条目
                    </button>
                  </div>
                </div>
              )}

              {/* Live Worldbook Hit Tester */}
              <div className="rounded-xl border border-zinc-200 bg-white p-4 text-xs dark:border-zinc-800 dark:bg-[#16191D]">
                <div className="mb-2 flex items-center justify-between">
                  <span className="flex items-center gap-1.5 font-semibold text-zinc-800 dark:text-zinc-200">
                    <Search className="h-3.5 w-3.5" />
                    实时命中预览沙盘（验证主次 AND/OR/NOT、递归扫描与预算截断）
                  </span>
                  <span className="font-mono-tabular text-zinc-500">
                    命中 {wbPreview.matchedTraces.length} 条 · 已用 {wbPreview.totalCharsUsed} /{' '}
                    {wbPreview.charBudget} 字
                  </span>
                </div>
                <input
                  value={wbTestCorpus}
                  onChange={(e) => setWbTestCorpus(e.target.value)}
                  placeholder="输入测试段落观察命中链..."
                  className="w-full rounded-md border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
                <div className="mt-2.5 flex flex-wrap gap-2">
                  {wbPreview.matchedTraces.map((tr) => (
                    <div
                      key={tr.entry.id}
                      className="rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1 text-[11px] dark:border-zinc-700 dark:bg-zinc-900"
                    >
                      <span className="font-medium text-zinc-900 dark:text-zinc-100">
                        {tr.entry.title}
                      </span>
                      <span className="ml-1.5 text-zinc-500">
                        ({tr.triggerReason} · P{tr.entry.priority})
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              {/* Entries List */}
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                {project.workspace.worldbookEntries.map((entry) => (
                  <div
                    key={entry.id}
                    className="rounded-xl border border-zinc-200 bg-white p-4 text-xs dark:border-zinc-800 dark:bg-[#16191D]"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="font-semibold text-zinc-900 dark:text-zinc-100">
                        {entry.title}
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="font-mono-tabular text-[11px] text-zinc-500">
                          P{entry.priority}
                        </span>
                        <label className="flex items-center gap-1 text-[11px] text-zinc-500">
                          <input
                            type="checkbox"
                            checked={entry.enabled}
                            onChange={(e) =>
                              onUpdateProject((p) => ({
                                ...p,
                                workspace: {
                                  ...p.workspace,
                                  worldbookEntries: p.workspace.worldbookEntries.map((w) =>
                                    w.id === entry.id ? { ...w, enabled: e.target.checked } : w,
                                  ),
                                },
                              }))
                            }
                          />
                          启用
                        </label>
                      </div>
                    </div>
                    <div className="mt-1 text-[11px] text-zinc-500">
                      {entry.alwaysActive
                        ? '常驻激活'
                        : `主词: ${entry.primaryKeys.join(' / ') || '无'} · 次级(${entry.secondaryLogic}): ${entry.secondaryKeys.join(' / ') || '无'}`}
                      {entry.recursiveScan ? ' · 递归扫描' : ''}
                    </div>
                    <p className="mt-2 leading-relaxed text-zinc-600 dark:text-zinc-400">
                      {entry.content}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
