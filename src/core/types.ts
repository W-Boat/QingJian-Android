export type ChapterStatus = 'DRAFT' | 'FINALIZED';
export type WritingMode = 'HUMAN_LED' | 'AI_DRIVEN';
export type FeedbackVote = 'NONE' | 'UP' | 'DOWN';
export type ForeshadowStage = 'SETUP' | 'PLANT' | 'CLUE' | 'ECHO' | 'PAYOFF';
export type LoreCategory = 'CHARACTER' | 'WORLD_RULE' | 'SCENE' | 'EVENT' | 'ANNOTATION';
export type SecondaryKeyLogic = 'NONE' | 'AND_ANY' | 'AND_ALL' | 'OR' | 'NOT_ANY' | 'NOT_ALL';
export type BuiltInSkillType = 'STYLE_DISTILL' | 'ARMOR_BREAK' | 'DE_AI_TONE';
export type AiProviderType = 'GEMINI_SERVER' | 'DEEPSEEK' | 'CHATGPT_CODEX_OAUTH' | 'OPENAI';
export type ThinkingLevelType = 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH';
export type ExportFormatType = 'JSON' | 'MARKDOWN' | 'TXT' | 'EPUB';

export const FORESHADOW_STAGE_LABELS: Record<ForeshadowStage, string> = {
  SETUP: '铺垫',
  PLANT: '埋设',
  CLUE: '线索',
  ECHO: '呼应',
  PAYOFF: '回收',
};

export const LORE_CATEGORY_LABELS: Record<LoreCategory, string> = {
  CHARACTER: '人物',
  WORLD_RULE: '世界规则',
  SCENE: '场景',
  EVENT: '事件',
  ANNOTATION: '批注',
};

export interface ParagraphBlock {
  id: string;
  index: number;
  markdown: string;
  vote: FeedbackVote;
  annotation: string;
}

export interface ChapterDocument {
  chapterId: string;
  volumeTitle: string;
  title: string;
  order: number;
  status: ChapterStatus;
  summary: string;
  markdownContent: string;
  blockFeedback: Record<string, FeedbackVote>;
  blockAnnotations: Record<string, string>;
  updatedAt: number;
}

export interface HandoffDirection {
  id: string;
  title: string;
  premise: string;
  tension: string;
}

export interface HandoffBrief {
  chapterId: string;
  chapterTitle: string;
  happenedFacts: string[];
  writingLessons: string[];
  nextChapterHooks: string[];
  nextDirections: HandoffDirection[];
  updatedAt: number;
}

export interface ForeshadowAnchor {
  id: string;
  stage: ForeshadowStage;
  chapterId: string;
  chapterTitle: string;
  blockId: string;
  excerpt: string;
  note: string;
  createdAt: number;
}

export interface ForeshadowThread {
  id: string;
  title: string;
  description: string;
  anchors: ForeshadowAnchor[];
  isResolved: boolean;
}

export interface LoreEntry {
  id: string;
  category: LoreCategory;
  title: string;
  content: string;
  sourceChapterId: string;
  sourceBlockId: string;
  sourceExcerpt: string;
}

export interface LoreExtractionCandidate {
  id: string;
  category: LoreCategory;
  title: string;
  summary: string;
  chapterId: string;
  chapterTitle: string;
  blockId: string;
  verbatimExcerpt: string;
}

export interface CausalNode {
  id: string;
  chapterId: string;
  title: string;
  detail: string;
  x: number;
  y: number;
}

export interface CausalEdge {
  id: string;
  fromNodeId: string;
  toNodeId: string;
  relationLabel: string;
  confirmed: boolean;
  aiSuggested: boolean;
}

export interface CausalGraphData {
  nodes: CausalNode[];
  edges: CausalEdge[];
}

export interface PlotPlanIdea {
  id: string;
  title: string;
  sourceChapterId: string;
  premise: string;
  keyBeats: string[];
  branchTag: string;
}

export interface WorldbookEntry {
  id: string;
  title: string;
  content: string;
  enabled: boolean;
  alwaysActive: boolean;
  primaryKeys: string[];
  secondaryKeys: string[];
  secondaryLogic: SecondaryKeyLogic;
  priority: number;
  recursiveScan: boolean;
  preventFurtherRecursion?: boolean;
  category: string;
}

export interface ContextWindowConfig {
  neighborParagraphsBefore: number;
  neighborParagraphsAfter: number;
  includeOutline: boolean;
  includeFinalizedHandoff: boolean;
  includeAuthorFeedback: boolean;
  worldbookCharBudget: number;
  totalContextCharBudget: number;
}

export interface StyleProfile {
  sampleTitle: string;
  avgSentenceChars: number;
  shortSentenceRatio: number;
  longSentenceRatio: number;
  dialogueRatio: number;
  narrativeDistance: string;
  pacingSignature: string;
  distilledRules: string[];
}

export interface ImportedSkillPackage {
  id: string;
  name: string;
  description: string;
  promptGuidelines: string;
  referenceFiles: Record<string, string>;
  enabled: boolean;
}

export interface SandboxJsScript {
  id: string;
  name: string;
  version: string;
  description: string;
  sourceCode: string;
  timeoutMs: number;
  approvedHash: string | null;
  enabled: boolean;
}

export interface ScriptLintIssue {
  rule: string;
  severity: string;
  excerpt: string;
  suggestion: string;
}

export interface ScriptExecutionReport {
  scriptId: string;
  scriptName: string;
  engineName: string;
  durationMs: number;
  issues: ScriptLintIssue[];
  summaryForAi: string;
}

export interface BookSkillsState {
  enabledBuiltIn: BuiltInSkillType[];
  styleProfile: StyleProfile | null;
  customCorrectionRules: string[];
  importedPackages: ImportedSkillPackage[];
  scripts: SandboxJsScript[];
}

export interface TavernCharacterCard {
  id: string;
  specVersion: string;
  name: string;
  description: string;
  personality: string;
  scenario: string;
  firstMessage: string;
  tags: string[];
  linkedLoreId?: string;
}

export interface RpTurnMessage {
  id: string;
  isAuthor: boolean;
  speaker: string;
  content: string;
  triggeredWorldbookTitles: string[];
}

export interface BookWorkspaceSnapshot {
  chapters: ChapterDocument[];
  handoffs: HandoffBrief[];
  loreEntries: LoreEntry[];
  loreCandidates: LoreExtractionCandidate[];
  foreshadows: ForeshadowThread[];
  causalGraph: CausalGraphData;
  plotPlans: PlotPlanIdea[];
  worldbookEntries: WorldbookEntry[];
}

export interface GitCommitNode {
  hash: string;
  shortHash: string;
  message: string;
  author: string;
  timestamp: number;
  parentHash: string | null;
  snapshot: BookWorkspaceSnapshot;
}

export interface GitBranchRef {
  name: string;
  headCommitHash: string;
}

export interface RestorePointRecord {
  id: string;
  label: string;
  reason: string;
  branchName: string;
  commitHash: string;
  createdAt: number;
  snapshot: BookWorkspaceSnapshot;
}

export interface PipelineRunRecord {
  runId: string;
  chapterId: string;
  taskType: string;
  stage: 'STAGE_DRAFT' | 'STAGE_REVIEW' | 'STAGE_COMPLETED';
  status: 'PENDING' | 'RUNNING' | 'DRAFT_SAVED' | 'REVIEW_FAILED' | 'COMPLETED' | 'FAILED';
  promptSummary: string;
  persistedDraft: string | null;
  reviewReport: string | null;
  errorMessage: string | null;
  draftAttempts: number;
  reviewAttempts: number;
  updatedAt: number;
}

export interface AiAccountProfile {
  id: string;
  label: string;
  provider: AiProviderType;
  model: string;
  thinkingLevel: ThinkingLevelType;
  endpoint: string;
  oauthEmail?: string;
  oauthAccountId?: string;
}

export interface AiUsageLog {
  id: string;
  profileLabel: string;
  modelName: string;
  thinkingLevel: ThinkingLevelType;
  taskLabel: string;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  timestamp: number;
}

export interface NovelProject {
  id: string;
  title: string;
  author: string;
  genre: string;
  synopsis: string;
  coverBg: string;
  coverAccent: string;
  wordCountGoalPerChapter: number;
  editorWidthPx: number;
  writingMode: WritingMode;
  aiWizardCompleted: boolean;
  workspace: BookWorkspaceSnapshot;
  skills: BookSkillsState;
  contextConfig: ContextWindowConfig;
  rpCards: TavernCharacterCard[];
  currentBranch: string;
  branches: GitBranchRef[];
  commits: GitCommitNode[];
  restorePoints: RestorePointRecord[];
  activePipelineRun: PipelineRunRecord | null;
  updatedAt: number;
}

export function splitChapterBlocks(chapter: ChapterDocument): ParagraphBlock[] {
  const parts = chapter.markdownContent
    .replace(/\r\n/g, '\n')
    .split(/\n\s*\n/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  return parts.map((md, idx) => {
    const id = `blk_${chapter.chapterId}_${idx + 1}`;
    return {
      id,
      index: idx,
      markdown: md,
      vote: chapter.blockFeedback[id] ?? 'NONE',
      annotation: chapter.blockAnnotations[id] ?? '',
    };
  });
}

export function countChars(markdown: string): number {
  return markdown.replace(/\s+/g, '').length;
}
