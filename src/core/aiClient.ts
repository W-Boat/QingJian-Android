import { buildSkillsSystemPrompt } from './sandboxJs';
import { evaluateWorldbook } from './worldbookEngine';
import type {
  AiAccountProfile,
  AiUsageLog,
  ChapterDocument,
  HandoffBrief,
  NovelProject,
  ParagraphBlock,
  TavernCharacterCard,
} from './types';

export interface AiCompletionResult {
  text: string;
  usage: AiUsageLog;
  usedFallback: boolean;
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

  // Context Compression Guard
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

export async function requestAiCompletion(params: {
  profile: AiAccountProfile;
  systemPrompt: string;
  userPrompt: string;
  taskLabel: string;
  localFallbackGenerator: () => string;
}): Promise<AiCompletionResult> {
  const { profile, systemPrompt, userPrompt, taskLabel, localFallbackGenerator } = params;
  const start = Date.now();

  try {
    const res = await fetch('/api/ai/complete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        systemPrompt,
        userPrompt,
        thinkingLevel: profile.thinkingLevel,
        taskLabel,
      }),
    });

    if (res.ok) {
      const data = (await res.json()) as {
        text: string;
        promptTokens: number;
        completionTokens: number;
        latencyMs: number;
        modelUsed?: string;
      };
      if (data.text && data.text.trim().length > 0) {
        return {
          text: data.text.trim(),
          usedFallback: false,
          usage: {
            id: `use_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            profileLabel: profile.label,
            modelName: data.modelUsed || profile.model,
            thinkingLevel: profile.thinkingLevel,
            taskLabel,
            promptTokens: data.promptTokens,
            completionTokens: data.completionTokens,
            latencyMs: data.latencyMs,
            timestamp: Date.now(),
          },
        };
      }
    }

    const errBody = (await res.json().catch(() => ({}))) as { error?: string; code?: string };
    if (!profile.useLocalFallbackOnError) {
      throw new Error(errBody.error || `AI 服务请求失败 (${res.status})`);
    }
  } catch (err) {
    if (!profile.useLocalFallbackOnError) {
      throw err instanceof Error ? err : new Error('AI 服务连接失败');
    }
  }

  // Deterministic local literary fallback when server API key is not configured
  const fallbackText = localFallbackGenerator();
  const latencyMs = Math.max(120, Date.now() - start);
  return {
    text: fallbackText,
    usedFallback: true,
    usage: {
      id: `use_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
      profileLabel: `${profile.label} (本地演练回退)`,
      modelName: `${profile.model}-local`,
      thinkingLevel: profile.thinkingLevel,
      taskLabel,
      promptTokens: Math.ceil((systemPrompt.length + userPrompt.length) / 2),
      completionTokens: Math.ceil(fallbackText.length / 2),
      latencyMs,
      timestamp: Date.now(),
    },
  };
}

export function buildLocalRewriteFallback(originalMarkdown: string, instruction: string, variantSeed: number): string {
  const cleaned = originalMarkdown
    .replace(/不禁/g, '')
    .replace(/顿时/g, '刹那间')
    .replace(/眼中闪过一丝难以察觉的试探/g, '目光在铜灯灯芯上停了半息，才移回对方袖口')
    .replace(/空气仿佛凝固了一般/g, '塔廊外风声收紧，案上烛火斜偏了一寸')
    .replace(/——总而言之，/g, '——')
    .replace(/总而言之，?/g, '')
    .replace(/综上所述，?/g, '');

  if (cleaned !== originalMarkdown) {
    return cleaned;
  }

  const trimmed = originalMarkdown.trim();
  const headingMatch = trimmed.match(/^(#{1,6}\s+)(.+)$/);
  if (headingMatch) {
    return `${headingMatch[1]}${headingMatch[2]}（修）`;
  }
  if (trimmed.startsWith('>')) {
    return `${trimmed}——按「${instruction.slice(0, 12)}」微调语气，压低句尾余韵。`;
  }

  const suffixes = [
    '灯影在案角微微一晃，冷铁般的沉静落进两人之间。',
    '风雪擦过檐角铜铃，细微的金属余震把未说出口的半句话压在喉间。',
    '指腹沿着器物边缘停住，所有试探都退回到不动声色的克制里。',
  ];
  const extra = suffixes[variantSeed % suffixes.length];
  if (trimmed.endsWith('。') || trimmed.endsWith('」')) {
    return `${trimmed}${extra}`;
  }
  return `${trimmed}。${extra}`;
}

export function buildLocalHandoffFallback(chapter: ChapterDocument): HandoffBrief {
  const blocks = chapter.markdownContent
    .split(/\n\s*\n/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0 && !s.startsWith('#') && !/^-{3,}$/.test(s));

  const facts = blocks.slice(0, 3).map((b) => {
    const clean = b.replace(/^>\s?/, '');
    return clean.length > 48 ? `${clean.slice(0, 48)}…` : clean;
  });

  const upCount = Object.values(chapter.blockFeedback).filter((v) => v === 'UP').length;
  const downCount = Object.values(chapter.blockFeedback).filter((v) => v === 'DOWN').length;

  return {
    chapterId: chapter.chapterId,
    chapterTitle: chapter.title,
    happenedFacts: facts.length > 0 ? facts : [`已完成《${chapter.title}》核心场景推进。`],
    writingLessons: [
      `本章记录赞 ${upCount} 处 / 踩 ${downCount} 处：优先沿用冷峻器物细节与克制对白。`,
      '严格避免“不禁”“顿时”“眼中闪过一丝”及段尾议论总结，保持叙事留白。',
    ],
    nextChapterHooks: [
      `紧接《${chapter.title}》结尾悬念，回应已埋设的核心物证与未解疑问。`,
      '保持人物知情边界一致，不提前泄露仅单方掌握的线索。',
    ],
    nextDirections: [
      {
        id: `dir_${Date.now()}_a`,
        title: '路线一：密室博弈与暗盟',
        premise: '双方围绕关键物证摊牌，以交换旧案底册为条件达成脆弱同盟。',
        tension: '外围耳目逼近，任何细微失口都会导致信任崩塌',
      },
      {
        id: `dir_${Date.now()}_b`,
        title: '路线二：决裂突围与夜遁',
        premise: '主角识破对方隐瞒的关键缺口，先手夺取证物并借地形脱身。',
        tension: '全城封锁升级，退路被截断，被迫转向城西暗线求助',
      },
    ],
    updatedAt: Date.now(),
  };
}

export function buildLocalRpReplyFallback(
  card: TavernCharacterCard,
  userMessage: string,
  hitTitles: string[],
): string {
  const refNote = hitTitles.length > 0 ? `（牵动设定：${hitTitles.slice(0, 2).join('、')}）` : '';
  return `（${card.name}收回落在案角的目光，指尖在器物边缘轻叩一下${refNote}）\n\n「你刚提到“${userMessage.slice(0, 18)}”，倒比我预想的沉得住气。既如此，先把你手里藏着的那半截实话摊开，我们再谈下一步的筹码。」`;
}
