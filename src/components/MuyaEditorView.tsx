import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  BookmarkPlus,
  Check,
  CheckCircle2,
  CheckSquare,
  ChevronLeft,
  ChevronRight,
  Code2,
  Eye,
  FileCheck2,
  FileDiff,
  GitBranch,
  GitCommitHorizontal,
  History,
  Layers,
  MessageSquareText,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Sparkles,
  Square,
  ThumbsDown,
  ThumbsUp,
  Undo2,
  Wand2,
  X,
} from 'lucide-react';
import {
  assembleTransparentContext,
  requestAiCompletion,
} from '../core/aiClient';
import {
  commitWorkspace,
  computeCommitFileByFileDiff,
  createBranchWithSafetyGuard,
  createRestorePointForProject,
  executeResumableTwoStagePipeline,
  loadCommitWithSafetyGuard,
  rollbackToRestorePoint,
  switchBranchWithSafetyGuard,
} from '../core/gitAndPipeline';
import {
  diffLines,
  validateMarkdownStructure,
  type AtomicUndoRecord,
} from '../core/markdownGuard';
import { evaluateWorldbook } from '../core/worldbookEngine';
import {
  countChars,
  FORESHADOW_STAGE_LABELS,
  splitChapterBlocks,
  type AiAccountProfile,
  type AiUsageLog,
  type ChapterDocument,
  type FeedbackVote,
  type ForeshadowStage,
  type HandoffBrief,
  type HandoffDirection,
  type NovelProject,
  type ParagraphBlock,
} from '../core/types';

interface MuyaEditorViewProps {
  project: NovelProject;
  activeChapterId: string;
  highlightedBlockId: string | null;
  activeProfile: AiAccountProfile;
  pendingSandboxReport: string | null;
  onClearSandboxReport: () => void;
  onSelectChapter: (chapterId: string) => void;
  onClearHighlight: () => void;
  onUpdateProject: (updater: (prev: NovelProject) => NovelProject) => void;
  onLogUsage: (usage: AiUsageLog) => void;
  onToast: (msg: string) => void;
}

interface InlineCandidateState {
  blockId: string;
  originalMarkdown: string;
  instruction: string;
  versions: string[];
  activeIdx: number;
  isGenerating: boolean;
}

interface FullDiffModalState {
  title: string;
  subtitle: string;
  originalText: string;
  revisedText: string;
  reviewNotes?: string | null;
  onAccept: (finalText: string) => void;
}

export const MuyaEditorView: React.FC<MuyaEditorViewProps> = ({
  project,
  activeChapterId,
  highlightedBlockId,
  activeProfile,
  pendingSandboxReport,
  onClearSandboxReport,
  onSelectChapter,
  onClearHighlight,
  onUpdateProject,
  onLogUsage,
  onToast,
}) => {
  const chapters = useMemo(
    () => [...project.workspace.chapters].sort((a, b) => a.order - b.order),
    [project.workspace.chapters],
  );
  const activeChapter: ChapterDocument =
    chapters.find((c) => c.chapterId === activeChapterId) ?? chapters[0];

  const blocks = useMemo(() => splitChapterBlocks(activeChapter), [activeChapter]);
  const chapterWordCount = useMemo(
    () => countChars(activeChapter.markdownContent),
    [activeChapter.markdownContent],
  );

  // Editor display & mode state
  const [editorMode, setEditorMode] = useState<'WYSIWYG' | 'SOURCE'>('WYSIWYG');
  const [sourceCursorPos, setSourceCursorPos] = useState<number>(0);
  const [showChapterRail, setShowChapterRail] = useState<boolean>(true);
  const sourceTextareaRef = useRef<HTMLTextAreaElement | null>(null);

  // Selection, gestures & atomic undo
  const [expandedBlockId, setExpandedBlockId] = useState<string | null>(null);
  const [multiSelectMode, setMultiSelectMode] = useState<boolean>(false);
  const [selectedBlockIds, setSelectedBlockIds] = useState<string[]>([]);
  const [undoStack, setUndoStack] = useState<AtomicUndoRecord[]>([]);

  // Gesture refs: true 650ms long-press + 8px move cancel
  const pressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pressOriginRef = useRef<{ x: number; y: number } | null>(null);
  const longPressFiredRef = useRef<boolean>(false);
  const lastTapRef = useRef<{ blockId: string; time: number } | null>(null);

  // Modals & drawers
  const [longPressEditBlock, setLongPressEditBlock] = useState<{
    block: ParagraphBlock;
    draft: string;
  } | null>(null);
  const [rewritePromptTarget, setRewritePromptTarget] = useState<{
    blockIds: string[];
    instruction: string;
  } | null>(null);
  const [inlineCandidate, setInlineCandidate] = useState<InlineCandidateState | null>(null);
  const [fullDiffModal, setFullDiffModal] = useState<FullDiffModalState | null>(null);
  const [annotationModalBlock, setAnnotationModalBlock] = useState<{
    block: ParagraphBlock;
    note: string;
  } | null>(null);
  const [foreshadowModalBlock, setForeshadowModalBlock] = useState<{
    block: ParagraphBlock;
    threadId: string;
    newThreadTitle: string;
    stage: ForeshadowStage;
    note: string;
  } | null>(null);
  const [relatedLoreBlock, setRelatedLoreBlock] = useState<ParagraphBlock | null>(null);

  // Drawers: Context Assembly, Handoff Brief, Next Chapter Wizard, Git Version Control
  const [showContextDrawer, setShowContextDrawer] = useState(false);
  const [handoffEditor, setHandoffEditor] = useState<HandoffBrief | null>(null);
  const [nextChapterWizard, setNextChapterWizard] = useState<{
    title: string;
    volumeTitle: string;
    directionTitle: string;
    summary: string;
    busy: boolean;
  } | null>(null);
  const [showGitModal, setShowGitModal] = useState(false);
  const [gitTab, setGitTab] = useState<'BRANCHES' | 'COMMITS' | 'RESTORE'>('BRANCHES');
  const [commitMessage, setCommitMessage] = useState('');
  const [newBranchName, setNewBranchName] = useState('');
  const [inspectedCommitHash, setInspectedCommitHash] = useState<string | null>(
    project.commits[0]?.hash ?? null,
  );
  const [pipelineBusy, setPipelineBusy] = useState(false);

  // Scroll to highlighted block from Plot Workbench / Foreshadow jump
  useEffect(() => {
    if (!highlightedBlockId) return;
    setEditorMode('WYSIWYG');
    setExpandedBlockId(highlightedBlockId);
    const el = document.getElementById(`block-${highlightedBlockId}`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    const t = setTimeout(() => onClearHighlight(), 4000);
    return () => clearTimeout(t);
  }, [highlightedBlockId, onClearHighlight]);

  // Restore source cursor position when switching WYSIWYG <-> SOURCE
  useEffect(() => {
    if (editorMode === 'SOURCE' && sourceTextareaRef.current) {
      sourceTextareaRef.current.focus();
      const clamped = Math.min(sourceCursorPos, activeChapter.markdownContent.length);
      sourceTextareaRef.current.setSelectionRange(clamped, clamped);
    }
  }, [editorMode, sourceCursorPos, activeChapter.markdownContent.length]);

  const toggleEditorMode = () => {
    if (editorMode === 'WYSIWYG') {
      // Calculate approximate char offset of active/expanded block to preserve cursor
      const targetBlock = blocks.find((b) => b.id === expandedBlockId) ?? blocks[0];
      const offset = targetBlock
        ? Math.max(0, activeChapter.markdownContent.indexOf(targetBlock.markdown))
        : 0;
      setSourceCursorPos(offset);
      setEditorMode('SOURCE');
    } else {
      const pos = sourceTextareaRef.current?.selectionStart ?? 0;
      setSourceCursorPos(pos);
      // Find block near cursor and expand it in WYSIWYG
      const before = activeChapter.markdownContent.slice(0, pos);
      const blockIdx = Math.max(0, before.split(/\n\s*\n/).length - 1);
      const matched = blocks[Math.min(blockIdx, Math.max(0, blocks.length - 1))];
      if (matched) setExpandedBlockId(matched.id);
      setEditorMode('WYSIWYG');
    }
  };

  const applyChapterMarkdown = (
    chapterId: string,
    nextMarkdown: string,
    undoLabel?: string,
  ) => {
    const prevMd =
      project.workspace.chapters.find((c) => c.chapterId === chapterId)?.markdownContent ?? '';
    if (undoLabel && prevMd !== nextMarkdown) {
      setUndoStack((prev) => [
        {
          chapterId,
          label: undoLabel,
          previousMarkdown: prevMd,
          nextMarkdown,
          createdAt: Date.now(),
        },
        ...prev.slice(0, 29),
      ]);
    }
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        chapters: proj.workspace.chapters.map((c) =>
          c.chapterId === chapterId
            ? { ...c, markdownContent: nextMarkdown, updatedAt: Date.now() }
            : c,
        ),
      },
      updatedAt: Date.now(),
    }));
  };

  const handleAtomicUndo = () => {
    const latest = undoStack[0];
    if (!latest) return;
    setUndoStack((prev) => prev.slice(1));
    onSelectChapter(latest.chapterId);
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        chapters: proj.workspace.chapters.map((c) =>
          c.chapterId === latest.chapterId
            ? { ...c, markdownContent: latest.previousMarkdown, updatedAt: Date.now() }
            : c,
        ),
      },
    }));
    onToast(`已撤销：${latest.label}`);
  };

  const replaceBlockInChapter = (blockId: string, nextBlockMarkdown: string, label: string) => {
    const updated = blocks
      .map((b) => (b.id === blockId ? nextBlockMarkdown.trim() : b.markdown))
      .join('\n\n');
    applyChapterMarkdown(activeChapter.chapterId, updated, label);
  };

  const setBlockVote = (blockId: string, vote: FeedbackVote) => {
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        chapters: proj.workspace.chapters.map((c) => {
          if (c.chapterId !== activeChapter.chapterId) return c;
          const current = c.blockFeedback[blockId] ?? 'NONE';
          return {
            ...c,
            blockFeedback: {
              ...c.blockFeedback,
              [blockId]: current === vote ? 'NONE' : vote,
            },
          };
        }),
      },
    }));
  };

  const saveBlockAnnotation = (blockId: string, note: string) => {
    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        chapters: proj.workspace.chapters.map((c) => {
          if (c.chapterId !== activeChapter.chapterId) return c;
          const nextNotes = { ...c.blockAnnotations };
          if (note.trim()) nextNotes[blockId] = note.trim();
          else delete nextNotes[blockId];
          return { ...c, blockAnnotations: nextNotes };
        }),
      },
    }));
    onToast('段落批注已保存');
  };

  const addForeshadowAnchorFromBlock = () => {
    if (!foreshadowModalBlock) return;
    const { block, threadId, newThreadTitle, stage, note } = foreshadowModalBlock;
    const newAnchor = {
      id: `fa_${Date.now()}`,
      stage,
      chapterId: activeChapter.chapterId,
      chapterTitle: activeChapter.title,
      blockId: block.id,
      excerpt: block.markdown.slice(0, 64),
      note: note.trim() || `在《${activeChapter.title}》标记为${FORESHADOW_STAGE_LABELS[stage]}`,
      createdAt: Date.now(),
    };

    onUpdateProject((proj) => {
      const existing = proj.workspace.foreshadows.find((f) => f.id === threadId);
      const nextForeshadows = existing
        ? proj.workspace.foreshadows.map((f) =>
            f.id === threadId
              ? {
                  ...f,
                  anchors: [...f.anchors, newAnchor],
                  isResolved: f.isResolved || stage === 'PAYOFF',
                }
              : f,
          )
        : [
            {
              id: `fs_${Date.now()}`,
              title: newThreadTitle.trim() || '新建跨章伏笔线',
              description: note.trim() || block.markdown.slice(0, 80),
              anchors: [newAnchor],
              isResolved: stage === 'PAYOFF',
            },
            ...proj.workspace.foreshadows,
          ];
      return {
        ...proj,
        workspace: { ...proj.workspace, foreshadows: nextForeshadows },
      };
    });
    setForeshadowModalBlock(null);
    onToast(
      stage === 'PAYOFF'
        ? '已登记伏笔「回收」锚点并自动结案'
        : `已登记伏笔「${FORESHADOW_STAGE_LABELS[stage]}」锚点`,
    );
  };

  // --- Pillar 1: Three-state gestures (Single-tap, Double-tap, 650ms Long-press with 8px scroll cancel) ---
  const clearPressTimer = () => {
    if (pressTimerRef.current) {
      clearTimeout(pressTimerRef.current);
      pressTimerRef.current = null;
    }
    pressOriginRef.current = null;
  };

  const handleBlockPointerDown = (block: ParagraphBlock, e: React.PointerEvent) => {
    if (e.button !== 0) return;
    longPressFiredRef.current = false;
    pressOriginRef.current = { x: e.clientX, y: e.clientY };
    pressTimerRef.current = setTimeout(() => {
      longPressFiredRef.current = true;
      setLongPressEditBlock({ block, draft: block.markdown });
    }, 650);
  };

  const handleBlockPointerMove = (e: React.PointerEvent) => {
    if (!pressOriginRef.current || !pressTimerRef.current) return;
    const dx = e.clientX - pressOriginRef.current.x;
    const dy = e.clientY - pressOriginRef.current.y;
    if (Math.hypot(dx, dy) > 8) {
      clearPressTimer();
    }
  };

  const handleBlockClick = (block: ParagraphBlock, e: React.MouseEvent) => {
    clearPressTimer();
    if (longPressFiredRef.current) {
      longPressFiredRef.current = false;
      return;
    }

    // Ctrl/Meta + Click -> non-adjacent multi-select
    if (e.ctrlKey || e.metaKey || multiSelectMode) {
      setSelectedBlockIds((prev) =>
        prev.includes(block.id) ? prev.filter((id) => id !== block.id) : [...prev, block.id],
      );
      return;
    }

    // Detect double-tap / double-click within 300ms
    const now = Date.now();
    if (lastTapRef.current && lastTapRef.current.blockId === block.id && now - lastTapRef.current.time < 300) {
      lastTapRef.current = null;
      setRewritePromptTarget({
        blockIds: [block.id],
        instruction: pendingSandboxReport
          ? `依据沙箱审查报告精修此段：${pendingSandboxReport.slice(0, 120)}`
          : '消除套话与 AI 总结腔，保留冷峻器物细节与克制节奏',
      });
      return;
    }

    lastTapRef.current = { blockId: block.id, time: now };
    setExpandedBlockId((prev) => (prev === block.id ? null : block.id));
  };

  // --- Single-paragraph inline rewrite & multi-paragraph full-screen diff rewrite ---
  const runParagraphRewrite = async (
    blockIds: string[],
    instruction: string,
    appendAsNewVersion = false,
  ) => {
    const targetBlocks = blocks.filter((b) => blockIds.includes(b.id));
    if (targetBlocks.length === 0) return;

    const assembled = assembleTransparentContext({
      project,
      chapter: activeChapter,
      focusBlocks: targetBlocks,
      allBlocks: blocks,
      instruction,
      extraNotes: pendingSandboxReport ?? undefined,
    });

    if (targetBlocks.length === 1) {
      const target = targetBlocks[0];
      const existingVersions =
        appendAsNewVersion && inlineCandidate?.blockId === target.id
          ? inlineCandidate.versions
          : [];

      setInlineCandidate({
        blockId: target.id,
        originalMarkdown: target.markdown,
        instruction,
        versions: existingVersions,
        activeIdx: Math.max(0, existingVersions.length - 1),
        isGenerating: true,
      });

      try {
        const res = await requestAiCompletion({
          profile: activeProfile,
          systemPrompt: assembled.systemPrompt,
          userPrompt: `${assembled.userPrompt}\n\n请仅改写以下单段正文并保持其原有 Markdown 结构：\n${target.markdown}`,
          taskLabel: '单段局部改稿',
        });
        onLogUsage(res.usage);
        const nextVersions = [...existingVersions, res.text];
        setInlineCandidate({
          blockId: target.id,
          originalMarkdown: target.markdown,
          instruction,
          versions: nextVersions,
          activeIdx: nextVersions.length - 1,
          isGenerating: false,
        });
        if (pendingSandboxReport) onClearSandboxReport();
      } catch (err) {
        setInlineCandidate(null);
        onToast(err instanceof Error ? err.message : '局部改稿失败');
      }
      return;
    }

    // Multi-paragraph (adjacent or non-adjacent) -> Full-screen Diff modal
    setPipelineBusy(true);
    try {
      const selectedSnippet = targetBlocks
        .map((b) => `<<<BLOCK:${b.id}>>>\n${b.markdown}`)
        .join('\n\n');
      const res = await requestAiCompletion({
        profile: activeProfile,
        systemPrompt: assembled.systemPrompt,
        userPrompt: `${assembled.userPrompt}\n\n请联合改写以下 ${targetBlocks.length} 个选中段落（可能不相邻），保持每个 <<<BLOCK:id>>> 标记与原有 Markdown 结构：\n${selectedSnippet}`,
        taskLabel: `多段联合改稿 (${targetBlocks.length} 段)`,
      });
      onLogUsage(res.usage);

      // Map back to full chapter markdown
      const replacementMap = new Map<string, string>();
      const regex = /<<<BLOCK:([^>]+)>>>\s*([\s\S]*?)(?=(?:<<<BLOCK:|$))/g;
      let m: RegExpExecArray | null;
      while ((m = regex.exec(res.text)) !== null) {
        replacementMap.set(m[1].trim(), m[2].trim());
      }
      if (replacementMap.size === 0) {
        // Fallback line-by-line mapping if model omitted markers
        const chunks = res.text.split(/\n\s*\n/).map((s) => s.trim()).filter(Boolean);
        targetBlocks.forEach((b, idx) => {
          if (chunks[idx]) replacementMap.set(b.id, chunks[idx]);
        });
      }

      const revisedChapter = blocks
        .map((b) => replacementMap.get(b.id) ?? b.markdown)
        .join('\n\n');

      setFullDiffModal({
        title: `批量联合改稿差异对比（共 ${targetBlocks.length} 段）`,
        subtitle: `改稿指令：${instruction}`,
        originalText: activeChapter.markdownContent,
        revisedText: revisedChapter,
        onAccept: (finalText) => {
          applyChapterMarkdown(
            activeChapter.chapterId,
            finalText,
            `多段联合改稿 (${targetBlocks.length} 段)`,
          );
          setSelectedBlockIds([]);
          setMultiSelectMode(false);
          setFullDiffModal(null);
          onToast('已接受多段改稿并写入原子撤销栈');
        },
      });
      if (pendingSandboxReport) onClearSandboxReport();
    } catch (err) {
      onToast(err instanceof Error ? err.message : '多段联合改稿失败');
    } finally {
      setPipelineBusy(false);
    }
  };

  // --- Pillar 1: Resumable Two-Stage Pipeline (`STAGE_DRAFT` -> `STAGE_REVIEW`) ---
  const runChapterPipeline = async (opts: {
    targetChapter: ChapterDocument;
    summaryText: string;
    isResume: boolean;
  }) => {
    setPipelineBusy(true);
    const { targetChapter, summaryText, isResume } = opts;
    const existingRun =
      isResume && project.activePipelineRun?.chapterId === targetChapter.chapterId
        ? project.activePipelineRun
        : null;

    const assembled = assembleTransparentContext({
      project,
      chapter: targetChapter,
      focusBlocks: splitChapterBlocks(targetChapter),
      allBlocks: splitChapterBlocks(targetChapter),
      instruction: `根据章节摘要生成整章正文：${summaryText}`,
      extraNotes: pendingSandboxReport ?? undefined,
    });

    const result = await executeResumableTwoStagePipeline({
      existingRun,
      chapterId: targetChapter.chapterId,
      taskType: 'CHAPTER_FULL_DRAFT',
      promptSummary: summaryText,
      generateDraft: async () => {
        const res = await requestAiCompletion({
          profile: activeProfile,
          systemPrompt: assembled.systemPrompt,
          userPrompt: `${assembled.userPrompt}\n\n请基于以下确认摘要撰写完整章节初稿（保留二级标题、场景分隔线 --- 与克制笔调）：\n标题：${targetChapter.title}\n摘要：${summaryText}`,
          taskLabel: '流水线 STAGE_DRAFT · 整章初稿',
        });
        onLogUsage(res.usage);
        return res.text;
      },
      runReview: async (draftText) => {
        const res = await requestAiCompletion({
          profile: activeProfile,
          systemPrompt: '你是青简自动复审 Subagent，请检查初稿的事实一致性、Markdown 结构与去 AI 味表现，给出简明质检报告。',
          userPrompt: `请复审以下初稿：\n${draftText}`,
          taskLabel: '流水线 STAGE_REVIEW · 自动复审',
        });
        onLogUsage(res.usage);
        return res.text;
      },
    });

    onUpdateProject((proj) => ({
      ...proj,
      activePipelineRun: result.run.status === 'COMPLETED' ? null : result.run,
    }));

    setPipelineBusy(false);

    if (result.run.status === 'COMPLETED' && result.run.persistedDraft) {
      const reusedNote = result.reusedPersistedDraft
        ? '（断点续跑生效：已复用持久化初稿，仅重跑复审阶段）'
        : '';
      setFullDiffModal({
        title: `整章生成与自动复审对比 · ${targetChapter.title}`,
        subtitle: `初稿尝试 ${result.run.draftAttempts} 次 · 复审尝试 ${result.run.reviewAttempts} 次 ${reusedNote}`,
        originalText: targetChapter.markdownContent,
        revisedText: result.run.persistedDraft,
        reviewNotes: result.run.reviewReport,
        onAccept: (finalText) => {
          applyChapterMarkdown(
            targetChapter.chapterId,
            finalText,
            `采纳整章生成稿 (${targetChapter.title})`,
          );
          setFullDiffModal(null);
          onToast('已落稿至正文并记录撤销点');
        },
      });
    } else {
      onToast(
        result.run.status === 'REVIEW_FAILED'
          ? 'STAGE_REVIEW 复审失败，初稿已安全落盘，可一键断点续跑'
          : result.run.errorMessage || '流水线执行失败',
      );
    }
  };

  // --- Pillar 2: Finalize Chapter & Generate Editable Handoff Brief ---
  const handleFinalizeOrBackfillHandoff = async (chapter: ChapterDocument) => {
    setPipelineBusy(true);
    const existing = project.workspace.handoffs.find((h) => h.chapterId === chapter.chapterId);
    if (existing) {
      setHandoffEditor(existing);
      setPipelineBusy(false);
      return;
    }

    try {
      const res = await requestAiCompletion({
        profile: activeProfile,
        systemPrompt:
          '你是青简定稿交接 Subagent。请仅返回纯 JSON，包含 happenedFacts(字符串数组), writingLessons(字符串数组), nextChapterHooks(字符串数组), nextDirections({id,title,premise,tension}数组)。严禁 emoji。',
        userPrompt: `请为章节《${chapter.title}》生成结构化交接稿：\n${chapter.markdownContent}`,
        taskLabel: '定稿交接 Subagent',
      });
      onLogUsage(res.usage);

      const jsonMatch = res.text.match(/\{[\s\S]*\}/);
      if (!jsonMatch) {
        throw new Error('模型未返回合法的 JSON 交接稿，请重试');
      }
      const obj = JSON.parse(jsonMatch[0]) as Partial<HandoffBrief>;
      const parsedBrief: HandoffBrief = {
        chapterId: chapter.chapterId,
        chapterTitle: chapter.title,
        happenedFacts: Array.isArray(obj.happenedFacts) ? obj.happenedFacts : [],
        writingLessons: Array.isArray(obj.writingLessons) ? obj.writingLessons : [],
        nextChapterHooks: Array.isArray(obj.nextChapterHooks) ? obj.nextChapterHooks : [],
        nextDirections: Array.isArray(obj.nextDirections) ? obj.nextDirections : [],
        updatedAt: Date.now(),
      };

      onUpdateProject((proj) => ({
        ...proj,
        workspace: {
          ...proj.workspace,
          chapters: proj.workspace.chapters.map((c) =>
            c.chapterId === chapter.chapterId ? { ...c, status: 'FINALIZED' } : c,
          ),
          handoffs: [
            ...proj.workspace.handoffs.filter((h) => h.chapterId !== chapter.chapterId),
            parsedBrief,
          ],
        },
      }));
      setHandoffEditor(parsedBrief);
      onToast(`已将《${chapter.title}》标为定稿并生成可编辑交接稿`);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '定稿交接稿生成失败');
    } finally {
      setPipelineBusy(false);
    }
  };

  const openNextChapterWizardFromDirection = async (dir?: HandoffDirection) => {
    const nextOrder = chapters.length + 1;
    const defaultTitle = `第${nextOrder}章 ${dir ? dir.title.replace(/^[^：]+：/, '').slice(0, 8) : '待拟章名'}`;
    const latestHandoff =
      project.workspace.handoffs.find((h) => h.chapterId === activeChapter.chapterId) ??
      project.workspace.handoffs[project.workspace.handoffs.length - 1];

    const draftSummary = dir
      ? `承接上章交接，沿「${dir.title}」展开：${dir.premise}。核心张力：${dir.tension}。`
      : `承接《${activeChapter.title}》结尾悬念：${latestHandoff?.nextChapterHooks[0] ?? '继续推进核心调查线。'}`;

    setHandoffEditor(null);
    setNextChapterWizard({
      title: defaultTitle,
      volumeTitle: activeChapter.volumeTitle,
      directionTitle: dir?.title ?? '自由续写',
      summary: draftSummary,
      busy: false,
    });
  };

  const confirmCreateNextChapter = async (generateFullDraft: boolean) => {
    if (!nextChapterWizard) return;
    const newId = `ch_${String(chapters.length + 1).padStart(3, '0')}_${Date.now().toString().slice(-3)}`;
    const newChapter: ChapterDocument = {
      chapterId: newId,
      volumeTitle: nextChapterWizard.volumeTitle,
      title: nextChapterWizard.title.trim() || `第${chapters.length + 1}章`,
      order: chapters.length + 1,
      status: 'DRAFT',
      summary: nextChapterWizard.summary.trim(),
      markdownContent: `## ${nextChapterWizard.title.trim() || `第${chapters.length + 1}章`}\n\n（已承接上章交接稿与摘要，可亲自执笔或点击顶部「生成整章初稿」）`,
      blockFeedback: {},
      blockAnnotations: {},
      updatedAt: Date.now(),
    };

    onUpdateProject((proj) => ({
      ...proj,
      workspace: {
        ...proj.workspace,
        chapters: [...proj.workspace.chapters, newChapter],
      },
    }));
    onSelectChapter(newId);
    const wizardCopy = { ...nextChapterWizard };
    setNextChapterWizard(null);

    if (generateFullDraft) {
      await runChapterPipeline({
        targetChapter: newChapter,
        summaryText: wizardCopy.summary,
        isResume: false,
      });
    } else {
      onToast(`已新建《${newChapter.title}》`);
    }
  };

  // Live context preview for Context Assembly Drawer
  const contextPreview = useMemo(() => {
    const focus = blocks.filter((b) =>
      selectedBlockIds.length > 0 ? selectedBlockIds.includes(b.id) : b.id === expandedBlockId,
    );
    return assembleTransparentContext({
      project,
      chapter: activeChapter,
      focusBlocks: focus.length > 0 ? focus : blocks.slice(0, 1),
      allBlocks: blocks,
      instruction: '预览当前上下文拼装结果',
      extraNotes: pendingSandboxReport ?? undefined,
    });
  }, [project, activeChapter, blocks, selectedBlockIds, expandedBlockId, pendingSandboxReport]);

  // Inline candidate live Markdown Structure Guard
  const activeCandidateText = inlineCandidate
    ? inlineCandidate.versions[inlineCandidate.activeIdx] ?? ''
    : '';
  const candidateGuard = useMemo(() => {
    if (!inlineCandidate || inlineCandidate.isGenerating) return null;
    return validateMarkdownStructure(inlineCandidate.originalMarkdown, activeCandidateText);
  }, [inlineCandidate, activeCandidateText]);

  // Git commit file-by-file diff
  const inspectedFileDiffs = useMemo(() => {
    if (!inspectedCommitHash) return [];
    return computeCommitFileByFileDiff(project, inspectedCommitHash);
  }, [project, inspectedCommitHash]);

  const activeChapterHandoff = project.workspace.handoffs.find(
    (h) => h.chapterId === activeChapter.chapterId,
  );

  // Render formatted WYSIWYG markdown block cleanly
  const renderBlockVisual = (md: string) => {
    const trimmed = md.trim();
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      return (
        <div className="my-2 flex items-center gap-3 py-1">
          <div className="h-px flex-1 bg-zinc-300 dark:bg-zinc-700" />
          <span className="text-[10px] tracking-[0.25em] text-zinc-400">场景分隔</span>
          <div className="h-px flex-1 bg-zinc-300 dark:bg-zinc-700" />
        </div>
      );
    }
    const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const cls =
        level === 1
          ? 'font-prose text-2xl font-semibold text-zinc-900 dark:text-zinc-100'
          : level === 2
            ? 'font-prose text-xl font-semibold text-zinc-900 dark:text-zinc-100'
            : 'font-prose text-lg font-medium text-zinc-800 dark:text-zinc-200';
      return <div className={cls}>{headingMatch[2]}</div>;
    }
    if (trimmed.startsWith('>')) {
      return (
        <blockquote className="rounded-r-md border-l-2 border-zinc-400 bg-zinc-50/90 py-2 pr-3 pl-3.5 font-prose text-[15px] leading-7 text-zinc-700 dark:border-zinc-600 dark:bg-zinc-800/40 dark:text-zinc-300">
          {trimmed.replace(/^>\s?/gm, '')}
        </blockquote>
      );
    }
    return (
      <p className="font-prose text-[16.5px] leading-[1.95] text-zinc-800 dark:text-zinc-200">
        {trimmed}
      </p>
    );
  };

  return (
    <div className="flex h-full min-h-0 overflow-hidden">
      {/* Left Chapter & Volume Rail */}
      {showChapterRail && (
        <aside className="hidden w-64 shrink-0 flex-col border-r border-zinc-200 bg-white lg:flex dark:border-zinc-800 dark:bg-[#13161A]">
          <div className="flex items-center justify-between border-b border-zinc-200 px-4 py-3 dark:border-zinc-800">
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                {project.title}
              </div>
              <div className="font-mono-tabular mt-0.5 text-[11px] text-zinc-500">
                分支 {project.currentBranch} · {chapters.length} 章
              </div>
            </div>
            <button
              type="button"
              onClick={() => openNextChapterWizardFromDirection()}
              title="新建章节"
              className="rounded-md border border-zinc-200 p-1.5 text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              <Plus className="h-3.5 w-3.5" />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto p-2.5 space-y-1">
            {chapters.map((ch) => {
              const isSelected = ch.chapterId === activeChapter.chapterId;
              const hasHandoff = project.workspace.handoffs.some((h) => h.chapterId === ch.chapterId);
              const chars = countChars(ch.markdownContent);
              return (
                <div
                  key={ch.chapterId}
                  onClick={() => onSelectChapter(ch.chapterId)}
                  className={`cursor-pointer rounded-lg border px-3 py-2.5 transition ${
                    isSelected
                      ? 'border-zinc-300 bg-zinc-100/80 dark:border-zinc-700 dark:bg-zinc-800/70'
                      : 'border-transparent hover:bg-zinc-50 dark:hover:bg-zinc-800/40'
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-xs font-medium text-zinc-900 dark:text-zinc-100">
                      {ch.title}
                    </span>
                    <span
                      className={`text-[10px] font-medium shrink-0 ${
                        ch.status === 'FINALIZED'
                          ? 'text-emerald-700 dark:text-emerald-400'
                          : 'text-zinc-400'
                      }`}
                    >
                      {ch.status === 'FINALIZED' ? '已定稿' : '草稿'}
                    </span>
                  </div>
                  <div className="mt-1 flex items-center justify-between text-[11px] text-zinc-500 tabular-nums">
                    <span>{chars} 字</span>
                    {hasHandoff ? (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          const found = project.workspace.handoffs.find(
                            (h) => h.chapterId === ch.chapterId,
                          );
                          if (found) setHandoffEditor(found);
                        }}
                        className="text-[10px] underline decoration-dotted hover:text-zinc-900 dark:hover:text-zinc-200"
                      >
                        查看交接稿
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleFinalizeOrBackfillHandoff(ch);
                        }}
                        className="text-[10px] text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200"
                      >
                        补齐交接
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          <div className="border-t border-zinc-200 p-3 dark:border-zinc-800">
            <div className="mb-1.5 flex items-center justify-between text-[11px] text-zinc-500">
              <span>创作模式</span>
              <span className="font-medium text-zinc-800 dark:text-zinc-200">
                {project.writingMode === 'HUMAN_LED' ? '人类主笔' : 'AI 驱动'}
              </span>
            </div>
            <div className="grid grid-cols-2 gap-1 rounded-lg bg-zinc-100 p-0.5 dark:bg-zinc-900">
              <button
                type="button"
                onClick={() =>
                  onUpdateProject((p) => ({ ...p, writingMode: 'HUMAN_LED' }))
                }
                className={`rounded-md py-1 text-[11px] font-medium transition ${
                  project.writingMode === 'HUMAN_LED'
                    ? 'bg-white text-zinc-900 shadow-xs dark:bg-zinc-800 dark:text-zinc-100'
                    : 'text-zinc-500'
                }`}
              >
                人类主笔
              </button>
              <button
                type="button"
                onClick={() =>
                  onUpdateProject((p) => ({ ...p, writingMode: 'AI_DRIVEN' }))
                }
                className={`rounded-md py-1 text-[11px] font-medium transition ${
                  project.writingMode === 'AI_DRIVEN'
                    ? 'bg-white text-zinc-900 shadow-xs dark:bg-zinc-800 dark:text-zinc-100'
                    : 'text-zinc-500'
                }`}
              >
                AI 驱动
              </button>
            </div>
          </div>
        </aside>
      )}

      {/* Center Editor Pane */}
      <div className="flex min-w-0 flex-1 flex-col bg-[#F7F7F5] dark:bg-[#0E1012]">
        {/* Editor Top Action Bar */}
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-200 bg-white px-4 py-2.5 dark:border-zinc-800 dark:bg-[#13161A]">
          <div className="flex min-w-0 items-center gap-2">
            <button
              type="button"
              onClick={() => setShowChapterRail((v) => !v)}
              className="hidden rounded-md p-1 text-zinc-500 hover:bg-zinc-100 lg:inline-flex dark:hover:bg-zinc-800"
              title="折叠/展开章节目录"
            >
              {showChapterRail ? <ChevronLeft className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            </button>
            <select
              aria-label="切换章节"
              value={activeChapter.chapterId}
              onChange={(e) => onSelectChapter(e.target.value)}
              className="max-w-[180px] truncate rounded-md border border-zinc-200 bg-zinc-50 px-2 py-1 text-xs font-medium text-zinc-900 lg:hidden dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
            >
              {chapters.map((c) => (
                <option key={c.chapterId} value={c.chapterId}>
                  {c.title}
                </option>
              ))}
            </select>
            <div className="hidden min-w-0 lg:block">
              <div className="flex items-center gap-2 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                <span className="truncate">{activeChapter.title}</span>
                <span className="text-xs font-normal text-zinc-400">·</span>
                <span className="font-mono-tabular text-xs font-normal text-zinc-500">
                  {chapterWordCount} / {project.wordCountGoalPerChapter} 字
                </span>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            <button
              type="button"
              onClick={toggleEditorMode}
              className="flex items-center gap-1 rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              {editorMode === 'WYSIWYG' ? (
                <>
                  <Code2 className="h-3.5 w-3.5" />
                  源码模式
                </>
              ) : (
                <>
                  <Eye className="h-3.5 w-3.5" />
                  所见即所得
                </>
              )}
            </button>

            <button
              type="button"
              onClick={() => {
                setMultiSelectMode((v) => !v);
                if (multiSelectMode) setSelectedBlockIds([]);
              }}
              className={`flex items-center gap-1 rounded-md border px-2.5 py-1.5 text-xs font-medium whitespace-nowrap shrink-0 transition ${
                multiSelectMode || selectedBlockIds.length > 0
                  ? 'border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900'
                  : 'border-zinc-200 text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800'
              }`}
            >
              <CheckSquare className="h-3.5 w-3.5" />
              多选改稿{selectedBlockIds.length > 0 ? ` (${selectedBlockIds.length})` : ''}
            </button>

            {undoStack.length > 0 && (
              <button
                type="button"
                onClick={handleAtomicUndo}
                className="flex items-center gap-1 rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
              >
                <Undo2 className="h-3.5 w-3.5" />
                撤销 ({undoStack.length})
              </button>
            )}

            <button
              type="button"
              onClick={() => setShowContextDrawer(true)}
              className="flex items-center gap-1 rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              <Layers className="h-3.5 w-3.5" />
              上下文 ({contextPreview.worldbookTrace.matchedTraces.filter((t) => t.includedInBudget).length})
            </button>

            <button
              type="button"
              onClick={() => setShowGitModal(true)}
              className="flex items-center gap-1 rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
            >
              <GitBranch className="h-3.5 w-3.5" />
              {project.currentBranch}
            </button>

            <button
              type="button"
              disabled={pipelineBusy}
              onClick={() => handleFinalizeOrBackfillHandoff(activeChapter)}
              style={{ backgroundColor: 'var(--qj-accent)' }}
              className="flex items-center gap-1 rounded-md px-3 py-1.5 text-xs font-medium text-white whitespace-nowrap shrink-0 hover:opacity-90 disabled:opacity-50"
            >
              <FileCheck2 className="h-3.5 w-3.5" />
              {activeChapterHandoff ? '查看交接稿' : '定稿交接'}
            </button>
          </div>
        </header>

        {/* Pending Sandbox Report Banner */}
        {pendingSandboxReport && (
          <div className="flex items-center justify-between gap-3 border-b border-amber-200 bg-amber-50/90 px-4 py-2 text-xs text-amber-900 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-200">
            <div className="truncate">
              <span className="font-semibold">已载入 JS 沙箱审查报告：</span>
              {pendingSandboxReport.replace(/\n/g, ' · ')}
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button
                type="button"
                onClick={() =>
                  setRewritePromptTarget({
                    blockIds: blocks.filter((b) => !b.markdown.startsWith('#') && !/^-{3,}$/.test(b.markdown)).slice(0, 2).map((b) => b.id),
                    instruction: `严格按沙箱审查报告修复以下问题：\n${pendingSandboxReport}`,
                  })
                }
                className="rounded bg-amber-800 px-2.5 py-1 text-[11px] font-medium text-white"
              >
                交给 AI 批量修复
              </button>
              <button
                type="button"
                onClick={onClearSandboxReport}
                className="text-[11px] underline"
              >
                忽略
              </button>
            </div>
          </div>
        )}

        {/* Resumable Two-Stage Pipeline Recovery Banner */}
        {project.activePipelineRun &&
          project.activePipelineRun.status === 'REVIEW_FAILED' && (
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-rose-200 bg-rose-50 px-4 py-2.5 text-xs text-rose-900 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-200">
              <div className="flex items-center gap-2">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                <span>
                  流水线在 <strong>STAGE_REVIEW</strong> 中断：初稿（
                  {project.activePipelineRun.persistedDraft?.length ?? 0} 字）已安全落盘。
                  {project.activePipelineRun.errorMessage}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={pipelineBusy}
                  onClick={() => {
                    const targetCh =
                      chapters.find(
                        (c) => c.chapterId === project.activePipelineRun?.chapterId,
                      ) ?? activeChapter;
                    runChapterPipeline({
                      targetChapter: targetCh,
                      summaryText: project.activePipelineRun?.promptSummary ?? targetCh.summary,
                      isResume: true,
                    });
                  }}
                  className="flex items-center gap-1 rounded-md bg-rose-700 px-3 py-1 text-xs font-medium text-white hover:bg-rose-800"
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  从失败处续跑（复用已存初稿，仅重跑复审）
                </button>
                <button
                  type="button"
                  onClick={() => onUpdateProject((p) => ({ ...p, activePipelineRun: null }))}
                  className="text-xs underline"
                >
                  放弃
                </button>
              </div>
            </div>
          )}

        {/* Multi-Select Floating Bar */}
        {selectedBlockIds.length > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-200 bg-zinc-900 px-4 py-2 text-xs text-white dark:border-zinc-800 dark:bg-zinc-100 dark:text-zinc-900">
            <span>
              已选中 {selectedBlockIds.length} 个段落（支持不相邻联合改稿 · 平板/桌面可按住 Ctrl/Cmd 单击多选）
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() =>
                  setRewritePromptTarget({
                    blockIds: selectedBlockIds,
                    instruction: '统一语调与叙事节奏，强化前后段落呼应，保持原有 Markdown 结构',
                  })
                }
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="flex items-center gap-1 rounded-md px-3 py-1 text-xs font-medium text-white"
              >
                <Wand2 className="h-3.5 w-3.5" />
                联合改稿（大屏 Diff）
              </button>
              <button
                type="button"
                onClick={() => setSelectedBlockIds([])}
                className="rounded-md border border-white/25 px-2.5 py-1 text-xs dark:border-zinc-900/25"
              >
                清空选中
              </button>
            </div>
          </div>
        )}

        {/* Main Scrollable Document Canvas */}
        <div className="flex-1 overflow-y-auto px-4 py-6 sm:px-8">
          <div
            className="mx-auto space-y-5"
            style={{ maxWidth: `${project.editorWidthPx}px` }}
          >
            {/* Chapter summary + AI-driven pipeline bar */}
            <div className="rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-[#16191D]">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
                  {activeChapter.volumeTitle} · 章节摘要与双阶段生成
                </span>
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    disabled={pipelineBusy}
                    onClick={() =>
                      runChapterPipeline({
                        targetChapter: activeChapter,
                        summaryText: activeChapter.summary || activeChapter.title,
                        isResume: false,
                      })
                    }
                    className="flex items-center gap-1 rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1 text-xs font-medium text-zinc-800 hover:bg-zinc-100 whitespace-nowrap shrink-0 disabled:opacity-50 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-200"
                  >
                    <Sparkles className="h-3.5 w-3.5" />
                    {pipelineBusy ? '流水线运行中…' : '按摘要生成整章 (初稿→复审)'}
                  </button>
                </div>
              </div>
              <textarea
                rows={2}
                value={activeChapter.summary}
                onChange={(e) => {
                  const val = e.target.value;
                  onUpdateProject((proj) => ({
                    ...proj,
                    workspace: {
                      ...proj.workspace,
                      chapters: proj.workspace.chapters.map((c) =>
                        c.chapterId === activeChapter.chapterId ? { ...c, summary: val } : c,
                      ),
                    },
                  }));
                }}
                placeholder="输入本章摘要（AI 驱动模式会先以摘要对齐剧情，再进入初稿→复审两阶段流水线）"
                className="mt-2 w-full resize-none rounded-md border border-zinc-200 bg-zinc-50/70 px-3 py-2 text-xs leading-relaxed text-zinc-700 focus:border-zinc-400 focus:outline-none dark:border-zinc-800 dark:bg-zinc-900/70 dark:text-zinc-300"
              />
            </div>

            {/* Editor Surface: WYSIWYG vs Source */}
            {editorMode === 'SOURCE' ? (
              <div className="rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-[#16191D]">
                <div className="mb-3 flex items-center justify-between text-xs text-zinc-500">
                  <span>Markdown 源码模式（切换时自动保留光标所在段落）</span>
                  <button
                    type="button"
                    onClick={() =>
                      applyChapterMarkdown(
                        activeChapter.chapterId,
                        `${activeChapter.markdownContent.trim()}\n\n---\n\n`,
                        '插入场景分隔线',
                      )
                    }
                    className="rounded border border-zinc-200 px-2 py-0.5 text-[11px] hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
                  >
                    + 插入场景线 (---)
                  </button>
                </div>
                <textarea
                  ref={sourceTextareaRef}
                  rows={22}
                  value={activeChapter.markdownContent}
                  onChange={(e) =>
                    applyChapterMarkdown(activeChapter.chapterId, e.target.value)
                  }
                  className="font-mono-tabular w-full resize-y rounded-lg border border-zinc-200 bg-zinc-50 p-4 text-sm leading-7 text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-100"
                />
              </div>
            ) : (
              <div className="rounded-xl border border-zinc-200 bg-white px-6 py-7 shadow-xs sm:px-10 sm:py-9 dark:border-zinc-800 dark:bg-[#16191D]">
                <div className="mb-6 flex flex-wrap items-center justify-between gap-2 border-b border-zinc-100 pb-3 text-[11px] text-zinc-400 dark:border-zinc-800">
                  <span>
                    手势：单击展开段落条 · 双击触发 AI 局部改稿 · 长按 650ms 完整编辑（滑动 &gt;8dp 自动取消防误触）
                  </span>
                  <button
                    type="button"
                    onClick={() =>
                      applyChapterMarkdown(
                        activeChapter.chapterId,
                        `${activeChapter.markdownContent.trim()}\n\n---\n\n新场景段落……`,
                        '插入场景分隔线',
                      )
                    }
                    className="text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
                  >
                    + 场景分隔线
                  </button>
                </div>

                <div className="space-y-4">
                  {blocks.map((block) => {
                    const isExpanded = expandedBlockId === block.id;
                    const isSelected = selectedBlockIds.includes(block.id);
                    const isHighlighted = highlightedBlockId === block.id;
                    const hasCandidate = inlineCandidate?.blockId === block.id;
                    const linkedForeshadows = project.workspace.foreshadows.filter((f) =>
                      f.anchors.some((a) => a.blockId === block.id),
                    );

                    return (
                      <div key={block.id} id={`block-${block.id}`} className="group">
                        <div
                          onPointerDown={(e) => handleBlockPointerDown(block, e)}
                          onPointerMove={handleBlockPointerMove}
                          onPointerUp={clearPressTimer}
                          onPointerCancel={clearPressTimer}
                          onClick={(e) => handleBlockClick(block, e)}
                          className={`relative cursor-pointer rounded-lg px-3.5 py-2.5 transition select-text ${
                            isHighlighted
                              ? 'bg-amber-50 ring-1 ring-amber-400 dark:bg-amber-950/30 dark:ring-amber-600'
                              : isSelected
                                ? 'bg-emerald-50/70 ring-1 ring-emerald-600/40 dark:bg-emerald-950/30'
                                : isExpanded
                                  ? 'bg-zinc-50 ring-1 ring-zinc-200 dark:bg-zinc-800/40 dark:ring-zinc-700'
                                  : 'hover:bg-zinc-50/70 dark:hover:bg-zinc-800/25'
                          }`}
                        >
                          <div className="flex items-start gap-2.5">
                            {(multiSelectMode || selectedBlockIds.length > 0) && (
                              <span className="mt-1.5 shrink-0 text-zinc-500">
                                {isSelected ? (
                                  <CheckSquare
                                    className="h-4 w-4"
                                    style={{ color: 'var(--qj-accent)' }}
                                  />
                                ) : (
                                  <Square className="h-4 w-4" />
                                )}
                              </span>
                            )}
                            <div className="min-w-0 flex-1">
                              {renderBlockVisual(block.markdown)}

                              {/* Subtle inline metadata footer if vote/annotation/foreshadow exists */}
                              {(block.vote !== 'NONE' ||
                                block.annotation ||
                                linkedForeshadows.length > 0) && (
                                <div className="mt-1.5 flex flex-wrap items-center gap-2 text-[11px] text-zinc-500 dark:text-zinc-400">
                                  {block.vote === 'UP' && (
                                    <span className="inline-flex items-center gap-0.5 text-emerald-700 dark:text-emerald-400">
                                      <ThumbsUp className="h-3 w-3" /> 已赞
                                    </span>
                                  )}
                                  {block.vote === 'DOWN' && (
                                    <span className="inline-flex items-center gap-0.5 text-rose-600 dark:text-rose-400">
                                      <ThumbsDown className="h-3 w-3" /> 已踩
                                    </span>
                                  )}
                                  {linkedForeshadows.map((f) => (
                                    <span key={f.id} className="text-amber-700 dark:text-amber-400">
                                      伏笔：{f.title}
                                    </span>
                                  ))}
                                  {block.annotation && (
                                    <span className="truncate text-zinc-500">
                                      批注：{block.annotation}
                                    </span>
                                  )}
                                </div>
                              )}
                            </div>
                          </div>
                        </div>

                        {/* Single-Tap Expanded Paragraph Action Toolbar */}
                        {isExpanded && (
                          <div className="mt-1.5 flex flex-wrap items-center gap-1 rounded-lg border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 text-xs dark:border-zinc-800 dark:bg-zinc-900">
                            <button
                              type="button"
                              onClick={() =>
                                setLongPressEditBlock({ block, draft: block.markdown })
                              }
                              className="flex items-center gap-1 rounded px-2 py-1 text-zinc-700 hover:bg-zinc-200/70 dark:text-zinc-300 dark:hover:bg-zinc-800"
                            >
                              <Pencil className="h-3.5 w-3.5" />
                              编辑
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                setRewritePromptTarget({
                                  blockIds: [block.id],
                                  instruction: '精修措辞与镜头感，避免套话，保留原有 Markdown 结构',
                                })
                              }
                              style={{ color: 'var(--qj-accent)' }}
                              className="flex items-center gap-1 rounded px-2 py-1 font-medium hover:bg-zinc-200/70 dark:hover:bg-zinc-800"
                            >
                              <Wand2 className="h-3.5 w-3.5" />
                              AI 改稿
                            </button>
                            <button
                              type="button"
                              onClick={() => setBlockVote(block.id, 'UP')}
                              className={`flex items-center gap-1 rounded px-2 py-1 ${
                                block.vote === 'UP'
                                  ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                                  : 'text-zinc-600 hover:bg-zinc-200/70 dark:text-zinc-400 dark:hover:bg-zinc-800'
                              }`}
                            >
                              <ThumbsUp className="h-3.5 w-3.5" />
                              赞
                            </button>
                            <button
                              type="button"
                              onClick={() => setBlockVote(block.id, 'DOWN')}
                              className={`flex items-center gap-1 rounded px-2 py-1 ${
                                block.vote === 'DOWN'
                                  ? 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300'
                                  : 'text-zinc-600 hover:bg-zinc-200/70 dark:text-zinc-400 dark:hover:bg-zinc-800'
                              }`}
                            >
                              <ThumbsDown className="h-3.5 w-3.5" />
                              踩
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                setAnnotationModalBlock({ block, note: block.annotation })
                              }
                              className="flex items-center gap-1 rounded px-2 py-1 text-zinc-600 hover:bg-zinc-200/70 dark:text-zinc-400 dark:hover:bg-zinc-800"
                            >
                              <MessageSquareText className="h-3.5 w-3.5" />
                              批注
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                setForeshadowModalBlock({
                                  block,
                                  threadId: project.workspace.foreshadows[0]?.id ?? 'NEW',
                                  newThreadTitle: '',
                                  stage: 'CLUE',
                                  note: '',
                                })
                              }
                              className="flex items-center gap-1 rounded px-2 py-1 text-zinc-600 hover:bg-zinc-200/70 dark:text-zinc-400 dark:hover:bg-zinc-800"
                            >
                              <BookmarkPlus className="h-3.5 w-3.5" />
                              伏笔
                            </button>
                            <button
                              type="button"
                              onClick={() => setRelatedLoreBlock(block)}
                              className="flex items-center gap-1 rounded px-2 py-1 text-zinc-600 hover:bg-zinc-200/70 dark:text-zinc-400 dark:hover:bg-zinc-800"
                            >
                              <Layers className="h-3.5 w-3.5" />
                              相关资料
                            </button>
                          </div>
                        )}

                        {/* Inline Candidate Card (Pillar 1: directly below original paragraph) */}
                        {hasCandidate && inlineCandidate && (
                          <div className="mt-2.5 rounded-xl border border-zinc-300 bg-zinc-50 p-4 dark:border-zinc-700 dark:bg-zinc-900/90">
                            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-200 pb-2.5 dark:border-zinc-800">
                              <div className="flex items-center gap-2 text-xs font-semibold text-zinc-900 dark:text-zinc-100">
                                <span>行内改稿候选</span>
                                {!inlineCandidate.isGenerating && inlineCandidate.versions.length > 0 && (
                                  <div className="flex items-center gap-1">
                                    {inlineCandidate.versions.map((_, vIdx) => (
                                      <button
                                        key={vIdx}
                                        type="button"
                                        onClick={() =>
                                          setInlineCandidate({
                                            ...inlineCandidate,
                                            activeIdx: vIdx,
                                          })
                                        }
                                        className={`font-mono-tabular rounded px-1.5 py-0.5 text-[11px] ${
                                          inlineCandidate.activeIdx === vIdx
                                            ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                                            : 'bg-zinc-200 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400'
                                        }`}
                                      >
                                        v{vIdx + 1}
                                      </button>
                                    ))}
                                  </div>
                                )}
                              </div>

                              <button
                                type="button"
                                disabled={inlineCandidate.isGenerating}
                                onClick={() =>
                                  runParagraphRewrite(
                                    [block.id],
                                    inlineCandidate.instruction,
                                    true,
                                  )
                                }
                                className="flex items-center gap-1 text-xs text-zinc-600 hover:text-zinc-900 disabled:opacity-50 dark:text-zinc-400 dark:hover:text-zinc-100"
                              >
                                <RefreshCw
                                  className={`h-3.5 w-3.5 ${inlineCandidate.isGenerating ? 'animate-spin' : ''}`}
                                />
                                再生成一版
                              </button>
                            </div>

                            {inlineCandidate.isGenerating ? (
                              <p className="py-6 text-center text-xs text-zinc-500">
                                正在结合上下文与世界书生成局部改稿候选…
                              </p>
                            ) : (
                              <>
                                <textarea
                                  rows={4}
                                  value={activeCandidateText}
                                  onChange={(e) => {
                                    const nextVersions = [...inlineCandidate.versions];
                                    nextVersions[inlineCandidate.activeIdx] = e.target.value;
                                    setInlineCandidate({
                                      ...inlineCandidate,
                                      versions: nextVersions,
                                    });
                                  }}
                                  className="font-prose mt-3 w-full rounded-lg border border-zinc-200 bg-white p-3 text-[15px] leading-7 text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-700 dark:bg-[#16191D] dark:text-zinc-100"
                                />

                                {/* Live Markdown Structure Guard Result */}
                                {candidateGuard?.kind === 'NO_TEXT_CHANGE' && (
                                  <div className="mt-2.5 flex items-center gap-2 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
                                    <AlertTriangle className="h-4 w-4 shrink-0" />
                                    <span>{candidateGuard.message}</span>
                                  </div>
                                )}

                                {candidateGuard?.kind === 'STRUCTURE_BROKEN' && (
                                  <div className="mt-2.5 space-y-1 rounded-lg border border-rose-300 bg-rose-50 px-3 py-2.5 text-xs text-rose-900 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-200">
                                    <div className="flex items-center gap-1.5 font-semibold">
                                      <ShieldAlert className="h-4 w-4 shrink-0" />
                                      Markdown 结构守卫已阻止直接落稿（请手动修复上方文本或重新生成）：
                                    </div>
                                    <ul className="list-disc pl-5 space-y-0.5">
                                      {candidateGuard.issues.map((issue, idx) => (
                                        <li key={idx}>{issue}</li>
                                      ))}
                                    </ul>
                                  </div>
                                )}

                                {candidateGuard?.kind === 'VALID' && (
                                  <div className="mt-2 flex items-center gap-1.5 text-[11px] text-emerald-700 dark:text-emerald-400">
                                    <CheckCircle2 className="h-3.5 w-3.5" />
                                    Markdown 结构校验通过，可安全落稿
                                  </div>
                                )}

                                <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                                  <button
                                    type="button"
                                    onClick={() =>
                                      setFullDiffModal({
                                        title: '单段改稿精细对比',
                                        subtitle: `段落 #${block.index + 1}`,
                                        originalText: inlineCandidate.originalMarkdown,
                                        revisedText: activeCandidateText,
                                        onAccept: (finalText) => {
                                          const check = validateMarkdownStructure(
                                            inlineCandidate.originalMarkdown,
                                            finalText,
                                          );
                                          if (check.kind === 'STRUCTURE_BROKEN') {
                                            onToast(`结构守卫拦截：${check.issues[0]}`);
                                            return;
                                          }
                                          replaceBlockInChapter(
                                            block.id,
                                            finalText,
                                            `接受段落 #${block.index + 1} 改稿`,
                                          );
                                          setInlineCandidate(null);
                                          setFullDiffModal(null);
                                          onToast('已接受改稿并写入撤销栈');
                                        },
                                      })
                                    }
                                    className="flex items-center gap-1 text-xs text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
                                  >
                                    <FileDiff className="h-3.5 w-3.5" />
                                    展开大屏 Diff
                                  </button>

                                  <div className="flex items-center gap-2">
                                    <button
                                      type="button"
                                      onClick={() => setInlineCandidate(null)}
                                      className="rounded-md border border-zinc-200 px-3 py-1.5 text-xs text-zinc-600 hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                                    >
                                      拒绝
                                    </button>
                                    <button
                                      type="button"
                                      disabled={candidateGuard?.kind === 'STRUCTURE_BROKEN'}
                                      onClick={() => {
                                        replaceBlockInChapter(
                                          block.id,
                                          activeCandidateText,
                                          `接受段落 #${block.index + 1} 改稿`,
                                        );
                                        setInlineCandidate(null);
                                        onToast('已接受候选并写入原子撤销记录');
                                      }}
                                      style={{ backgroundColor: 'var(--qj-accent)' }}
                                      className="flex items-center gap-1 rounded-md px-3.5 py-1.5 text-xs font-medium text-white disabled:cursor-not-allowed disabled:opacity-40"
                                    >
                                      <Check className="h-3.5 w-3.5" />
                                      接受落稿
                                    </button>
                                  </div>
                                </div>
                              </>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Append new paragraph button */}
                <div className="mt-8 border-t border-zinc-100 pt-4 dark:border-zinc-800">
                  <button
                    type="button"
                    onClick={() =>
                      applyChapterMarkdown(
                        activeChapter.chapterId,
                        `${activeChapter.markdownContent.trim()}\n\n在此继续书写下一段……`,
                        '追加新段落',
                      )
                    }
                    className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    在章末追加新段落
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* --- Modal: 650ms Long-Press Full Paragraph Editor (preserving original + 1-tap restore) --- */}
      {longPressEditBlock && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
          <div className="w-full max-w-2xl rounded-xl border border-zinc-200 bg-white p-5 shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
            <div className="flex items-center justify-between border-b border-zinc-200 pb-3 dark:border-zinc-800">
              <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                完整段落编辑（保留原文 · 随时一键还原）
              </h3>
              <button
                type="button"
                onClick={() => setLongPressEditBlock(null)}
                className="text-zinc-400 hover:text-zinc-800 dark:hover:text-zinc-200"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="mt-4 space-y-3">
              <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3 dark:border-zinc-800 dark:bg-zinc-900/70">
                <div className="mb-1 flex items-center justify-between text-[11px] text-zinc-500">
                  <span>原始段落对照</span>
                  <button
                    type="button"
                    onClick={() =>
                      setLongPressEditBlock({
                        ...longPressEditBlock,
                        draft: longPressEditBlock.block.markdown,
                      })
                    }
                    className="flex items-center gap-1 font-medium text-zinc-700 hover:underline dark:text-zinc-300"
                  >
                    <RotateCcw className="h-3 w-3" />
                    一键还原原文
                  </button>
                </div>
                <p className="font-prose text-xs leading-6 text-zinc-600 dark:text-zinc-400">
                  {longPressEditBlock.block.markdown}
                </p>
              </div>

              <textarea
                rows={6}
                value={longPressEditBlock.draft}
                onChange={(e) =>
                  setLongPressEditBlock({ ...longPressEditBlock, draft: e.target.value })
                }
                className="font-prose w-full rounded-lg border border-zinc-200 bg-white p-3.5 text-[15px] leading-7 text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-700 dark:bg-zinc-950 dark:text-zinc-100"
              />
            </div>

            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setLongPressEditBlock(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs text-zinc-600 dark:border-zinc-700 dark:text-zinc-300"
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => {
                  replaceBlockInChapter(
                    longPressEditBlock.block.id,
                    longPressEditBlock.draft,
                    `手动编辑段落 #${longPressEditBlock.block.index + 1}`,
                  );
                  setLongPressEditBlock(null);
                  onToast('段落已保存并写入撤销栈');
                }}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                保存改动
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Double-tap / Multi-select Rewrite Prompt --- */}
      {rewritePromptTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
          <div className="w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-5 shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              告诉 AI 这 {rewritePromptTarget.blockIds.length} 个段落怎么改
            </h3>
            <p className="mt-1 text-xs text-zinc-500">
              {rewritePromptTarget.blockIds.length === 1
                ? '单段改稿将以行内候选卡直接展示在原段下方，并自动经过 Markdown 结构守卫校验。'
                : '多段联合改稿将自动弹出大屏差异对比视图（Full-screen Diff）供审阅落稿。'}
            </p>
            <textarea
              rows={3}
              autoFocus
              value={rewritePromptTarget.instruction}
              onChange={(e) =>
                setRewritePromptTarget({ ...rewritePromptTarget, instruction: e.target.value })
              }
              className="mt-3 w-full rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-sm text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
            />
            <div className="mt-2 flex flex-wrap gap-1.5">
              {[
                '删去“不禁/顿时/总而言之”等套话，改用器物细节',
                '放慢节奏，增加环境冷感官与停顿',
                '压缩冗余修饰，让对白更冷峻克制',
              ].map((preset) => (
                <button
                  key={preset}
                  type="button"
                  onClick={() =>
                    setRewritePromptTarget({ ...rewritePromptTarget, instruction: preset })
                  }
                  className="rounded border border-zinc-200 px-2 py-1 text-[11px] text-zinc-600 hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-400 dark:hover:bg-zinc-800"
                >
                  {preset}
                </button>
              ))}
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setRewritePromptTarget(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs text-zinc-600 dark:border-zinc-700 dark:text-zinc-300"
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => {
                  const target = rewritePromptTarget;
                  setRewritePromptTarget(null);
                  runParagraphRewrite(target.blockIds, target.instruction);
                }}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                触发改稿
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Full-Screen Diff View (Batch Rewrite & Full Chapter Draft) --- */}
      {fullDiffModal && (
        <div className="fixed inset-0 z-50 flex flex-col bg-white dark:bg-[#0E1012]">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 px-5 py-3.5 dark:border-zinc-800">
            <div>
              <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                {fullDiffModal.title}
              </h2>
              <p className="text-xs text-zinc-500">{fullDiffModal.subtitle}</p>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setFullDiffModal(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs font-medium text-zinc-700 dark:border-zinc-700 dark:text-zinc-300"
              >
                拒绝返回
              </button>
              <button
                type="button"
                onClick={() => fullDiffModal.onAccept(fullDiffModal.revisedText)}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="flex items-center gap-1 rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                <Check className="h-3.5 w-3.5" />
                接受全部改动（写入原子撤销栈）
              </button>
            </div>
          </div>

          {fullDiffModal.reviewNotes && (
            <div className="border-b border-emerald-200 bg-emerald-50/80 px-5 py-2.5 text-xs text-emerald-900 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-200">
              <strong>STAGE_REVIEW 自动复审质检报告：</strong>
              {fullDiffModal.reviewNotes}
            </div>
          )}

          <div className="grid flex-1 grid-cols-1 divide-y divide-zinc-200 overflow-hidden lg:grid-cols-2 lg:divide-x lg:divide-y-0 dark:divide-zinc-800">
            {/* Unified line diff */}
            <div className="overflow-y-auto p-5">
              <div className="mb-3 text-xs font-semibold text-zinc-500 uppercase">
                逐行差异审阅（Code Review 视图）
              </div>
              <div className="space-y-1 font-prose text-sm leading-7">
                {diffLines(fullDiffModal.originalText, fullDiffModal.revisedText).map((d, idx) => (
                  <div
                    key={idx}
                    className={`rounded px-2.5 py-0.5 whitespace-pre-wrap ${
                      d.op === 'INSERT'
                        ? 'bg-emerald-50 text-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-200'
                        : d.op === 'DELETE'
                          ? 'bg-rose-50 text-rose-900 line-through dark:bg-rose-950/40 dark:text-rose-200'
                          : 'text-zinc-600 dark:text-zinc-400'
                    }`}
                  >
                    <span className="font-mono-tabular mr-2 inline-block w-3 select-none opacity-60">
                      {d.op === 'INSERT' ? '+' : d.op === 'DELETE' ? '-' : ' '}
                    </span>
                    {d.text || ' '}
                  </div>
                ))}
              </div>
            </div>

            {/* Editable final draft pane */}
            <div className="flex flex-col overflow-hidden p-5">
              <div className="mb-3 text-xs font-semibold text-zinc-500 uppercase">
                落稿前手动微调（可直接修整后采纳）
              </div>
              <textarea
                value={fullDiffModal.revisedText}
                onChange={(e) =>
                  setFullDiffModal({ ...fullDiffModal, revisedText: e.target.value })
                }
                className="font-prose flex-1 resize-none rounded-lg border border-zinc-200 bg-zinc-50 p-4 text-sm leading-7 text-zinc-900 focus:outline-none dark:border-zinc-800 dark:bg-[#16191D] dark:text-zinc-100"
              />
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Paragraph Annotation --- */}
      {annotationModalBlock && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl border border-zinc-200 bg-white p-5 shadow-xl dark:border-zinc-800 dark:bg-[#16191D]">
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              段落批注（将自动随上下文喂给 AI）
            </h3>
            <textarea
              rows={3}
              autoFocus
              value={annotationModalBlock.note}
              onChange={(e) =>
                setAnnotationModalBlock({ ...annotationModalBlock, note: e.target.value })
              }
              placeholder="记录修改要求、伏笔提醒或人物语气注意事项"
              className="mt-3 w-full rounded-lg border border-zinc-200 bg-zinc-50 p-2.5 text-sm dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
            />
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setAnnotationModalBlock(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs dark:border-zinc-700"
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => {
                  saveBlockAnnotation(annotationModalBlock.block.id, annotationModalBlock.note);
                  setAnnotationModalBlock(null);
                }}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                保存批注
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Quick Foreshadow Marking --- */}
      {foreshadowModalBlock && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl border border-zinc-200 bg-white p-5 shadow-xl dark:border-zinc-800 dark:bg-[#16191D]">
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              登记跨章伏笔锚点（五阶段全生命周期）
            </h3>
            <div className="mt-3 space-y-3 text-xs">
              <div>
                <label className="mb-1 block text-zinc-500">归属伏笔线</label>
                <select
                  value={foreshadowModalBlock.threadId}
                  onChange={(e) =>
                    setForeshadowModalBlock({ ...foreshadowModalBlock, threadId: e.target.value })
                  }
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-2.5 py-2 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                >
                  {project.workspace.foreshadows.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.title} ({f.isResolved ? '已回收' : '追踪中'})
                    </option>
                  ))}
                  <option value="NEW">+ 新建伏笔线</option>
                </select>
              </div>
              {foreshadowModalBlock.threadId === 'NEW' && (
                <input
                  value={foreshadowModalBlock.newThreadTitle}
                  onChange={(e) =>
                    setForeshadowModalBlock({
                      ...foreshadowModalBlock,
                      newThreadTitle: e.target.value,
                    })
                  }
                  placeholder="输入新伏笔名称"
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-2.5 py-2 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
              )}
              <div>
                <label className="mb-1 block text-zinc-500">本段所处阶段</label>
                <div className="grid grid-cols-5 gap-1">
                  {(['SETUP', 'PLANT', 'CLUE', 'ECHO', 'PAYOFF'] as ForeshadowStage[]).map((st) => (
                    <button
                      key={st}
                      type="button"
                      onClick={() =>
                        setForeshadowModalBlock({ ...foreshadowModalBlock, stage: st })
                      }
                      className={`rounded-md py-1.5 text-center font-medium transition ${
                        foreshadowModalBlock.stage === st
                          ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                          : 'border border-zinc-200 text-zinc-600 dark:border-zinc-700 dark:text-zinc-400'
                      }`}
                    >
                      {FORESHADOW_STAGE_LABELS[st]}
                    </button>
                  ))}
                </div>
              </div>
              <input
                value={foreshadowModalBlock.note}
                onChange={(e) =>
                  setForeshadowModalBlock({ ...foreshadowModalBlock, note: e.target.value })
                }
                placeholder="锚点备注（可选）"
                className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-2.5 py-2 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
              />
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setForeshadowModalBlock(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs dark:border-zinc-700"
              >
                取消
              </button>
              <button
                type="button"
                onClick={addForeshadowAnchorFromBlock}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                写入伏笔档案
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Paragraph Related Lore & Worldbook Hits --- */}
      {relatedLoreBlock && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-5 shadow-xl dark:border-zinc-800 dark:bg-[#16191D]">
            <div className="flex items-center justify-between border-b border-zinc-200 pb-3 dark:border-zinc-800">
              <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                本段命中资料与世界书预览
              </h3>
              <button type="button" onClick={() => setRelatedLoreBlock(null)}>
                <X className="h-4 w-4 text-zinc-400" />
              </button>
            </div>
            <div className="mt-3 max-h-[60vh] space-y-2.5 overflow-y-auto text-xs">
              {evaluateWorldbook(
                project.workspace.worldbookEntries,
                relatedLoreBlock.markdown,
                project.contextConfig.worldbookCharBudget,
                3,
              ).matchedTraces.map((trace) => (
                <div
                  key={trace.entry.id}
                  className="rounded-lg border border-zinc-200 p-3 dark:border-zinc-800"
                >
                  <div className="flex items-center justify-between font-medium text-zinc-900 dark:text-zinc-100">
                    <span>{trace.entry.title}</span>
                    <span className="text-[11px] text-zinc-500">{trace.triggerReason}</span>
                  </div>
                  <p className="mt-1 text-zinc-600 dark:text-zinc-400">{trace.entry.content}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* --- Drawer: Pillar 4 Transparent Context Assembly --- */}
      {showContextDrawer && (
        <div className="fixed inset-0 z-50 flex justify-end bg-black/45">
          <div className="flex h-full w-full max-w-lg flex-col border-l border-zinc-200 bg-white shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
            <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4 dark:border-zinc-800">
              <div>
                <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                  透明上下文拼装面板
                </h3>
                <p className="font-mono-tabular text-[11px] text-zinc-500">
                  当前总字符 {contextPreview.totalChars} / 预算 {contextPreview.budgetChars}
                </p>
              </div>
              <button type="button" onClick={() => setShowContextDrawer(false)}>
                <X className="h-4 w-4 text-zinc-500" />
              </button>
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto p-5 text-xs">
              {/* Sliders & toggles */}
              <div className="space-y-3 rounded-lg border border-zinc-200 bg-zinc-50 p-3.5 dark:border-zinc-800 dark:bg-zinc-900/60">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="text-zinc-500">
                      前文邻近段数：{project.contextConfig.neighborParagraphsBefore}
                    </label>
                    <input
                      type="range"
                      min={0}
                      max={10}
                      value={project.contextConfig.neighborParagraphsBefore}
                      onChange={(e) =>
                        onUpdateProject((p) => ({
                          ...p,
                          contextConfig: {
                            ...p.contextConfig,
                            neighborParagraphsBefore: Number(e.target.value),
                          },
                        }))
                      }
                      className="mt-1 w-full accent-emerald-700"
                    />
                  </div>
                  <div>
                    <label className="text-zinc-500">
                      后文邻近段数：{project.contextConfig.neighborParagraphsAfter}
                    </label>
                    <input
                      type="range"
                      min={0}
                      max={10}
                      value={project.contextConfig.neighborParagraphsAfter}
                      onChange={(e) =>
                        onUpdateProject((p) => ({
                          ...p,
                          contextConfig: {
                            ...p.contextConfig,
                            neighborParagraphsAfter: Number(e.target.value),
                          },
                        }))
                      }
                      className="mt-1 w-full accent-emerald-700"
                    />
                  </div>
                </div>

                <div>
                  <label className="text-zinc-500">
                    世界书字符预算：{project.contextConfig.worldbookCharBudget} 字
                  </label>
                  <input
                    type="range"
                    min={400}
                    max={6000}
                    step={200}
                    value={project.contextConfig.worldbookCharBudget}
                    onChange={(e) =>
                      onUpdateProject((p) => ({
                        ...p,
                        contextConfig: {
                          ...p.contextConfig,
                          worldbookCharBudget: Number(e.target.value),
                        },
                      }))
                    }
                    className="mt-1 w-full accent-emerald-700"
                  />
                </div>

                <div className="flex flex-wrap gap-3 pt-1">
                  {[
                    { key: 'includeOutline', label: '注入大纲摘要' },
                    { key: 'includeFinalizedHandoff', label: '注入定稿交接稿' },
                    { key: 'includeAuthorFeedback', label: '注入赞/踩与批注' },
                  ].map((item) => (
                    <label key={item.key} className="flex items-center gap-1.5 text-zinc-700 dark:text-zinc-300">
                      <input
                        type="checkbox"
                        checked={Boolean(
                          project.contextConfig[item.key as keyof typeof project.contextConfig],
                        )}
                        onChange={(e) =>
                          onUpdateProject((p) => ({
                            ...p,
                            contextConfig: {
                              ...p.contextConfig,
                              [item.key]: e.target.checked,
                            },
                          }))
                        }
                      />
                      {item.label}
                    </label>
                  ))}
                </div>
              </div>

              {/* Worldbook hit trace */}
              <div>
                <h4 className="mb-2 font-semibold text-zinc-800 dark:text-zinc-200">
                  世界书命中追踪（含递归扫描与超限压缩）
                </h4>
                <div className="space-y-1.5">
                  {contextPreview.worldbookTrace.matchedTraces.map((tr) => (
                    <div
                      key={tr.entry.id}
                      className="flex items-center justify-between rounded-md border border-zinc-200 px-3 py-2 dark:border-zinc-800"
                    >
                      <div>
                        <span className="font-medium text-zinc-900 dark:text-zinc-100">
                          {tr.entry.title}
                        </span>
                        <span className="ml-2 text-[11px] text-zinc-500">{tr.triggerReason}</span>
                      </div>
                      <span className="font-mono-tabular text-[11px] text-zinc-500">
                        {tr.includedInBudget
                          ? tr.compressed
                            ? '压缩注入'
                            : '完整注入'
                          : '超预算跳过'}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              {/* Exact context sections */}
              <div>
                <h4 className="mb-2 font-semibold text-zinc-800 dark:text-zinc-200">
                  即将发送给 AI 的精确分块
                </h4>
                <div className="space-y-2">
                  {contextPreview.sections.map((sec, idx) => (
                    <div
                      key={idx}
                      className="rounded-lg border border-zinc-200 p-3 dark:border-zinc-800"
                    >
                      <div className="flex items-center justify-between font-medium text-zinc-900 dark:text-zinc-100">
                        <span>{sec.title}</span>
                        <span className="font-mono-tabular text-[11px] text-zinc-500">
                          {sec.chars} 字符
                        </span>
                      </div>
                      <p className="mt-1 line-clamp-3 whitespace-pre-wrap text-[11px] text-zinc-500">
                        {sec.preview}
                      </p>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Pillar 2 Editable Handoff Brief --- */}
      {handoffEditor && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
          <div className="flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
            <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4 dark:border-zinc-800">
              <div>
                <h3 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                  定稿交接稿 · {handoffEditor.chapterTitle}
                </h3>
                <p className="text-xs text-zinc-500">
                  已发生事实、写作经验、下章承接铺垫与备选走向（均可手动编辑后跨章接力）
                </p>
              </div>
              <button type="button" onClick={() => setHandoffEditor(null)}>
                <X className="h-4 w-4 text-zinc-500" />
              </button>
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto p-5 text-xs">
              <div>
                <label className="mb-1 block font-semibold text-zinc-700 dark:text-zinc-300">
                  1. 本章已发生事实（谁做了什么、揭示了什么、谁在场知情 — 每行一条）
                </label>
                <textarea
                  rows={3}
                  value={handoffEditor.happenedFacts.join('\n')}
                  onChange={(e) =>
                    setHandoffEditor({
                      ...handoffEditor,
                      happenedFacts: e.target.value.split('\n').filter(Boolean),
                    })
                  }
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 p-2.5 leading-6 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
              </div>

              <div>
                <label className="mb-1 block font-semibold text-zinc-700 dark:text-zinc-300">
                  2. 写作经验（结合本章赞/踩与历史经验，明确沿用与避免）
                </label>
                <textarea
                  rows={3}
                  value={handoffEditor.writingLessons.join('\n')}
                  onChange={(e) =>
                    setHandoffEditor({
                      ...handoffEditor,
                      writingLessons: e.target.value.split('\n').filter(Boolean),
                    })
                  }
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 p-2.5 leading-6 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
              </div>

              <div>
                <label className="mb-1 block font-semibold text-zinc-700 dark:text-zinc-300">
                  3. 下一章必须承接的铺垫与开场疑问
                </label>
                <textarea
                  rows={2}
                  value={handoffEditor.nextChapterHooks.join('\n')}
                  onChange={(e) =>
                    setHandoffEditor({
                      ...handoffEditor,
                      nextChapterHooks: e.target.value.split('\n').filter(Boolean),
                    })
                  }
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 p-2.5 leading-6 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
              </div>

              <div>
                <label className="mb-2 block font-semibold text-zinc-700 dark:text-zinc-300">
                  4. 备选后续走向（点选任意走向即可生成下章摘要并开新章）
                </label>
                <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
                  {handoffEditor.nextDirections.map((dir) => (
                    <div
                      key={dir.id}
                      className="flex flex-col justify-between rounded-lg border border-zinc-200 p-3 dark:border-zinc-800"
                    >
                      <div>
                        <div className="font-semibold text-zinc-900 dark:text-zinc-100">
                          {dir.title}
                        </div>
                        <p className="mt-1 text-zinc-600 dark:text-zinc-400">{dir.premise}</p>
                        <p className="mt-1 text-[11px] text-zinc-500">张力：{dir.tension}</p>
                      </div>
                      <button
                        type="button"
                        onClick={() => openNextChapterWizardFromDirection(dir)}
                        style={{ color: 'var(--qj-accent)' }}
                        className="mt-2.5 self-start text-xs font-medium hover:underline"
                      >
                        选此走向新建下一章 →
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            <div className="flex justify-end gap-2 border-t border-zinc-200 px-5 py-3.5 dark:border-zinc-800">
              <button
                type="button"
                onClick={() => setHandoffEditor(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs dark:border-zinc-700"
              >
                关闭
              </button>
              <button
                type="button"
                onClick={() => {
                  onUpdateProject((proj) => ({
                    ...proj,
                    workspace: {
                      ...proj.workspace,
                      handoffs: [
                        ...proj.workspace.handoffs.filter(
                          (h) => h.chapterId !== handoffEditor.chapterId,
                        ),
                        { ...handoffEditor, updatedAt: Date.now() },
                      ],
                    },
                  }));
                  setHandoffEditor(null);
                  onToast('定稿交接稿修改已落盘');
                }}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                保存交接稿
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Next Chapter Summary Confirm -> Full Draft --- */}
      {nextChapterWizard && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
          <div className="w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-5 shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
            <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              新建下一章 · 摘要确认（{nextChapterWizard.directionTitle}）
            </h3>
            <p className="mt-1 text-xs text-zinc-500">
              先确认并微调章节摘要，再决定由人类亲自执笔或启动「初稿 → 自动复审」两阶段流水线。
            </p>
            <div className="mt-3 space-y-3 text-xs">
              <input
                value={nextChapterWizard.title}
                onChange={(e) =>
                  setNextChapterWizard({ ...nextChapterWizard, title: e.target.value })
                }
                placeholder="新章节标题"
                className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
              />
              <textarea
                rows={4}
                value={nextChapterWizard.summary}
                onChange={(e) =>
                  setNextChapterWizard({ ...nextChapterWizard, summary: e.target.value })
                }
                className="w-full rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-xs leading-6 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
              />
            </div>
            <div className="mt-4 flex flex-wrap justify-end gap-2">
              <button
                type="button"
                onClick={() => setNextChapterWizard(null)}
                className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs dark:border-zinc-700"
              >
                取消
              </button>
              <button
                type="button"
                onClick={() => confirmCreateNextChapter(false)}
                className="rounded-lg border border-zinc-300 px-3 py-1.5 text-xs font-medium text-zinc-800 dark:border-zinc-700 dark:text-zinc-200"
              >
                仅建空章（人类主笔）
              </button>
              <button
                type="button"
                onClick={() => confirmCreateNextChapter(true)}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
              >
                确认摘要并生成整章初稿
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Modal: Pillar 6 Git Branches, File-by-File Diff & Mandatory Safety Restore Points --- */}
      {showGitModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
          <div className="flex max-h-[88vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
            <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4 dark:border-zinc-800">
              <div>
                <h3 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                  本地 Git 多路线仓库与防丢失还原点 · {project.title}
                </h3>
                <p className="text-xs text-zinc-500">
                  当前分支 {project.currentBranch} · 切换分支或载入历史提交前强制自动创建整书安全还原点
                </p>
              </div>
              <button type="button" onClick={() => setShowGitModal(false)}>
                <X className="h-4 w-4 text-zinc-500" />
              </button>
            </div>

            <div className="flex gap-1 border-b border-zinc-200 px-5 pt-2 dark:border-zinc-800">
              {[
                { id: 'BRANCHES', label: `提交与多路线分支 (${project.branches.length})` },
                { id: 'COMMITS', label: `历史提交与逐文件 Diff (${project.commits.length})` },
                { id: 'RESTORE', label: `整书还原点 (${project.restorePoints.length})` },
              ].map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setGitTab(t.id as typeof gitTab)}
                  className={`border-b-2 px-3 py-2 text-xs font-medium whitespace-nowrap shrink-0 ${
                    gitTab === t.id
                      ? 'border-zinc-900 text-zinc-900 dark:border-zinc-100 dark:text-zinc-100'
                      : 'border-transparent text-zinc-500'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <div className="flex-1 overflow-y-auto p-5 text-xs">
              {gitTab === 'BRANCHES' && (
                <div className="space-y-5">
                  {/* Commit current workspace */}
                  <div className="rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
                    <div className="mb-2 font-semibold text-zinc-800 dark:text-zinc-200">
                      提交当前书稿快照至分支 {project.currentBranch}
                    </div>
                    <div className="flex gap-2">
                      <input
                        value={commitMessage}
                        onChange={(e) => setCommitMessage(e.target.value)}
                        placeholder="提交说明（如：完成第二章暗阁改稿与伏笔锚点）"
                        className="flex-1 rounded-md border border-zinc-200 bg-zinc-50 px-3 py-1.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                      />
                      <button
                        type="button"
                        onClick={() => {
                          onUpdateProject((p) => commitWorkspace(p, commitMessage));
                          setCommitMessage('');
                          onToast('已写入新 Git 提交');
                        }}
                        style={{ backgroundColor: 'var(--qj-accent)' }}
                        className="flex items-center gap-1 rounded-md px-3.5 py-1.5 font-medium text-white whitespace-nowrap shrink-0"
                      >
                        <GitCommitHorizontal className="h-3.5 w-3.5" />
                        提交变更
                      </button>
                    </div>
                  </div>

                  {/* Create branch */}
                  <div className="rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
                    <div className="mb-2 font-semibold text-zinc-800 dark:text-zinc-200">
                      新建多路线写法分支（如：route-rupture 决裂路线）
                    </div>
                    <div className="flex gap-2">
                      <input
                        value={newBranchName}
                        onChange={(e) => setNewBranchName(e.target.value)}
                        placeholder="分支名称（如：route-rupture）"
                        className="flex-1 rounded-md border border-zinc-200 bg-zinc-50 px-3 py-1.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                      />
                      <button
                        type="button"
                        onClick={() => {
                          if (!newBranchName.trim()) return;
                          onUpdateProject((p) => createBranchWithSafetyGuard(p, newBranchName).project);
                          onToast(`已自动创建安全还原点并切换至新分支 ${newBranchName.trim()}`);
                          setNewBranchName('');
                        }}
                        className="rounded-md border border-zinc-300 px-3.5 py-1.5 font-medium text-zinc-800 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
                      >
                        新建并切换
                      </button>
                    </div>
                  </div>

                  {/* Branch list */}
                  <div className="space-y-2">
                    {project.branches.map((b) => {
                      const isCurrent = b.name === project.currentBranch;
                      return (
                        <div
                          key={b.name}
                          className="flex items-center justify-between rounded-lg border border-zinc-200 px-4 py-3 dark:border-zinc-800"
                        >
                          <div>
                            <div className="flex items-center gap-2 font-medium text-zinc-900 dark:text-zinc-100">
                              <GitBranch className="h-3.5 w-3.5" />
                              <span>{b.name}</span>
                              {isCurrent && (
                                <span
                                  style={{ color: 'var(--qj-accent)' }}
                                  className="text-[11px] font-semibold"
                                >
                                  当前分支
                                </span>
                              )}
                            </div>
                            <div className="font-mono-tabular mt-0.5 text-[11px] text-zinc-500">
                              HEAD {b.headCommitHash.slice(0, 7)}
                            </div>
                          </div>
                          {!isCurrent && (
                            <button
                              type="button"
                              onClick={() => {
                                onUpdateProject(
                                  (p) => switchBranchWithSafetyGuard(p, b.name).project,
                                );
                                onToast(`已自动创建安全还原点并切换至分支 ${b.name}`);
                              }}
                              className="rounded-md border border-zinc-300 px-3 py-1.5 font-medium text-zinc-800 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
                            >
                              切换分支（自动备份）
                            </button>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {gitTab === 'COMMITS' && (
                <div className="grid grid-cols-1 gap-4 md:grid-cols-5">
                  <div className="space-y-2 md:col-span-2">
                    {project.commits.map((c) => (
                      <div
                        key={c.hash}
                        onClick={() => setInspectedCommitHash(c.hash)}
                        className={`cursor-pointer rounded-lg border p-3 transition ${
                          inspectedCommitHash === c.hash
                            ? 'border-zinc-900 bg-zinc-50 dark:border-zinc-200 dark:bg-zinc-800/60'
                            : 'border-zinc-200 hover:bg-zinc-50/70 dark:border-zinc-800'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-mono-tabular text-[11px] font-semibold text-zinc-500">
                            {c.shortHash}
                          </span>
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              onUpdateProject(
                                (p) => loadCommitWithSafetyGuard(p, c.hash).project,
                              );
                              onToast(`已自动创建安全还原点并载入历史提交 ${c.shortHash}`);
                            }}
                            className="text-[11px] font-medium underline"
                          >
                            载入此版本
                          </button>
                        </div>
                        <div className="mt-1 font-medium text-zinc-900 dark:text-zinc-100">
                          {c.message}
                        </div>
                        <div className="mt-1 text-[10px] text-zinc-400">
                          {c.author} · {new Date(c.timestamp).toLocaleString()}
                        </div>
                      </div>
                    ))}
                  </div>

                  <div className="space-y-3 md:col-span-3">
                    <div className="font-semibold text-zinc-700 dark:text-zinc-300">
                      逐文件变更差异（对应仓库真实 .md / .json 文件）
                    </div>
                    {inspectedFileDiffs.length === 0 ? (
                      <p className="rounded-lg border border-zinc-200 p-4 text-zinc-500 dark:border-zinc-800">
                        该提交无文本差异。
                      </p>
                    ) : (
                      inspectedFileDiffs.map((f) => (
                        <div
                          key={f.path}
                          className="overflow-hidden rounded-lg border border-zinc-200 dark:border-zinc-800"
                        >
                          <div className="flex items-center justify-between border-b border-zinc-200 bg-zinc-50 px-3 py-2 dark:border-zinc-800 dark:bg-zinc-900">
                            <span className="font-mono-tabular font-medium text-zinc-800 dark:text-zinc-200">
                              {f.path}
                            </span>
                            <span className="font-mono-tabular text-[11px]">
                              <span className="text-emerald-600">+{f.addedLines}</span>{' '}
                              <span className="text-rose-600">-{f.deletedLines}</span>
                            </span>
                          </div>
                          <pre className="font-mono-tabular max-h-56 overflow-auto p-3 text-[11px] leading-5 text-zinc-700 dark:text-zinc-300">
                            {f.unifiedPatch}
                          </pre>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}

              {gitTab === 'RESTORE' && (
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-zinc-500">
                      支持手动创建整书还原点，且所有切分支与历史载入均强制先自动留档。
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        onUpdateProject((p) => ({
                          ...p,
                          restorePoints: [
                            createRestorePointForProject(p, '作者手动整书快照', 'MANUAL'),
                            ...p.restorePoints,
                          ],
                        }));
                        onToast('已创建手动整书还原点');
                      }}
                      style={{ backgroundColor: 'var(--qj-accent)' }}
                      className="flex items-center gap-1 rounded-md px-3 py-1.5 font-medium text-white whitespace-nowrap shrink-0"
                    >
                      <History className="h-3.5 w-3.5" />
                      立即创建手动还原点
                    </button>
                  </div>

                  {project.restorePoints.length === 0 ? (
                    <p className="rounded-lg border border-zinc-200 p-6 text-center text-zinc-500 dark:border-zinc-800">
                      暂无还原点。点击右上角手动创建，或尝试切换分支触发自动安全保护。
                    </p>
                  ) : (
                    project.restorePoints.map((rp) => (
                      <div
                        key={rp.id}
                        className="flex items-center justify-between rounded-lg border border-zinc-200 px-4 py-3 dark:border-zinc-800"
                      >
                        <div>
                          <div className="font-medium text-zinc-900 dark:text-zinc-100">
                            {rp.label}
                          </div>
                          <div className="font-mono-tabular mt-0.5 text-[11px] text-zinc-500">
                            分支 {rp.branchName} · 提交 {rp.commitHash.slice(0, 7)} ·{' '}
                            {new Date(rp.createdAt).toLocaleString()}
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => {
                            onUpdateProject(
                              (p) => rollbackToRestorePoint(p, rp.id).project,
                            );
                            onToast(`已回滚至还原点：${rp.label}`);
                          }}
                          className="rounded-md border border-zinc-300 px-3 py-1.5 font-medium text-zinc-800 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-200"
                        >
                          一键回滚
                        </button>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
