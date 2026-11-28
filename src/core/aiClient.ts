import { buildSkillsSystemPrompt } from './sandboxJs';
import { evaluateWorldbook } from './worldbookEngine';
import type {
  AiAccountProfile,
  AiUsageLog,
  ChapterDocument,
  NovelProject,
  ParagraphBlock,
} from './types';

export interface AiCompletionResult {
  text: string;
  usage: AiUsageLog;
}

export interface AssembledContextPreview {
  systemPrompt: string;
  userPrompt: string;
  sections: Array<{ title: string; chars: number; preview: string }>;
  worldbookTrace: ReturnType<typeof evaluateWorldbook>;
  totalChars: number;
  budgetChars: number;
}

export function assembleTransparentContext(params: {
  project: NovelProject;
  chapter: ChapterDocument;
  focusBlocks: ParagraphBlock[];
  allBlocks: ParagraphBlock[];
  instruction: string;
  extraNotes?: string;
}): AssembledContextPreview {
  const { project, chapter, focusBlocks, allBlocks, instruction, extraNotes } = params;
  const cfg = project.contextConfig;
  const focusIndices = focusBlocks.map((b) => b.index).sort((a, b) => a - b);
  const minIdx = focusIndices.length > 0 ? Math.max(0, focusIndices[0] - cfg.neighborParagraphsBefore) : 0;
  const maxIdx =
    focusIndices.length > 0
      ? Math.min(allBlocks.length - 1, focusIndices[focusIndices.length - 1] + cfg.neighborParagraphsAfter)
      : Math.min(allBlocks.length - 1, 5);

  const neighborText = allBlocks
    .slice(minIdx, maxIdx + 1)
    .map((b) => `[段落 ${b.index + 1}] ${b.markdown}`)
    .join('\n\n');

  const scanCorpus = [
    chapter.title,
    chapter.summary,
    focusBlocks.map((b) => b.markdown).join('\n'),
    neighborText,
    instruction,
  ].join('\n');

  const wbEval = evaluateWorldbook(
    project.workspace.worldbookEntries,
    scanCorpus,
    cfg.worldbookCharBudget,
    3,
  );

  const sections: Array<{ title: string; chars: number; preview: string }> = [];

  const skillsPrompt = buildSkillsSystemPrompt(project.skills);
  if (skillsPrompt) {
    sections.push({
      title: '写作技能与文风约束',
      chars: skillsPrompt.length,
      preview: skillsPrompt.slice(0, 260),
    });
  }

  if (cfg.includeOutline && chapter.summary.trim()) {
    sections.push({
      title: '当前章节摘要与大纲',
      chars: chapter.summary.length,
      preview: chapter.summary,
    });
  }

  const prevHandoffs = project.workspace.handoffs.filter((h) => h.chapterId !== chapter.chapterId);
  let handoffText = '';
  if (cfg.includeFinalizedHandoff && prevHandoffs.length > 0) {
    const latest = prevHandoffs[prevHandoffs.length - 1];
    handoffText = [
      `【上章定稿交接 · ${latest.chapterTitle}】`,
      `已发生事实：${latest.happenedFacts.join('；')}`,
      `写作经验：${latest.writingLessons.join('；')}`,
      `本章须承接铺垫：${latest.nextChapterHooks.join('；')}`,
    ].join('\n');
    sections.push({
      title: `定稿交接稿（${latest.chapterTitle}）`,
      chars: handoffText.length,
      preview: handoffText.slice(0, 260),
    });
  }

  let feedbackText = '';
  if (cfg.includeAuthorFeedback) {
    const liked = allBlocks.filter((b) => b.vote === 'UP').map((b) => b.markdown.slice(0, 60));
    const disliked = allBlocks.filter((b) => b.vote === 'DOWN').map((b) => b.markdown.slice(0, 60));
    const notes = allBlocks.filter((b) => b.annotation.trim()).map((b) => `${b.id}: ${b.annotation}`);
    const lines = [
      liked.length ? `作者点赞偏好（应沿用）：${liked.join(' / ')}` : '',
      disliked.length ? `作者点踩警示（应规避）：${disliked.join(' / ')}` : '',
      notes.length ? `段落批注：${notes.join('；')}` : '',
    ].filter(Boolean);
    feedbackText = lines.join('\n');
    if (feedbackText) {
      sections.push({
        title: '作者赞/踩偏好与批注',
        chars: feedbackText.length,
        preview: feedbackText.slice(0, 260),
      });
    }
  }

  if (wbEval.injectedText) {
    sections.push({
      title: `世界书命中（${wbEval.matchedTraces.filter((t) => t.includedInBudget).length} 条）`,
      chars: wbEval.injectedText.length,
      preview: wbEval.injectedText.slice(0, 260),
    });
  }

  sections.push({
    title: `上下文邻近段落（前 ${cfg.neighborParagraphsBefore} 段 / 后 ${cfg.neighborParagraphsAfter} 段）`,
    chars: neighborText.length,
    preview: neighborText.slice(0, 260),
  });

  if (extraNotes?.trim()) {
    sections.push({
      title: '脚本审查与附加指令',
      chars: extraNotes.length,
      preview: extraNotes.slice(0, 260),
    });
  }

  const systemPrompt = [
    `你是「青简」长篇小说创作工作台的专属主笔与审校引擎。作品：《${project.title}》（${project.genre}）。`,
    '硬性要求：1) 严格保留原有 Markdown 结构（标题层级、引用块、列表、场景分隔线、成对加粗/斜体）；2) 严禁使用任何 emoji；3) 除非要求输出报告，否则直接输出可落稿的正文。',
    skillsPrompt,
    wbEval.injectedText,
  ]
    .filter(Boolean)
    .join('\n\n');

  const rawUserPrompt = [
    cfg.includeOutline && chapter.summary ? `【本章摘要】\n${chapter.summary}` : '',
    handoffText,
    feedbackText,
    `【邻近上下文】\n${neighborText}`,
    extraNotes?.trim() ? `【附加约束 / 沙箱报告】\n${extraNotes.trim()}` : '',
    `【本次任务指令】\n${instruction}`,
  ]
    .filter(Boolean)
    .join('\n\n');

  const userPrompt =
    rawUserPrompt.length > cfg.totalContextCharBudget
      ? rawUserPrompt.slice(0, cfg.totalContextCharBudget - 40) + '\n\n[上下文引擎已按预算自动压缩]'
      : rawUserPrompt;

  return {
    systemPrompt,
    userPrompt,
    sections,
    worldbookTrace: wbEval,
    totalChars: systemPrompt.length + userPrompt.length,
    budgetChars: cfg.totalContextCharBudget,
  };
}

/**
 * Calls the real `/api/ai/complete` server proxy (Gemini / OpenAI / DeepSeek / Codex Responses).
 * Never fabricates fake local literary fallback responses on error.
 */
export async function requestAiCompletion(params: {
  profile: AiAccountProfile;
  systemPrompt: string;
  userPrompt: string;
  taskLabel: string;
}): Promise<AiCompletionResult> {
  const { profile, systemPrompt, userPrompt, taskLabel } = params;

  const res = await fetch('/api/ai/complete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      provider: profile.provider,
      model: profile.model,
      endpoint: profile.endpoint,
      oauthAccountId: profile.oauthAccountId,
      systemPrompt,
      userPrompt,
      thinkingLevel: profile.thinkingLevel,
      taskLabel,
    }),
  });

  if (!res.ok) {
    const errBody = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(errBody.error || `AI 服务请求失败 (${res.status})`);
  }

  const data = (await res.json()) as {
    text?: string;
    promptTokens?: number;
    completionTokens?: number;
    latencyMs?: number;
    modelUsed?: string;
  };

  const text = (data.text || '').trim();
  if (!text) {
    throw new Error('AI 接口返回了空正文，请检查提示词或重试。');
  }

  return {
    text,
    usage: {
      id: `use_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
      profileLabel: profile.label,
      modelName: data.modelUsed || profile.model,
      thinkingLevel: profile.thinkingLevel,
      taskLabel,
      promptTokens: data.promptTokens ?? Math.ceil((systemPrompt.length + userPrompt.length) / 2),
      completionTokens: data.completionTokens ?? Math.ceil(text.length / 2),
      latencyMs: data.latencyMs ?? 0,
      timestamp: Date.now(),
    },
  };
}
