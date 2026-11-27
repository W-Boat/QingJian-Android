import { describe, expect, it } from 'bun:test';
import {
  buildEpubZipBytes,
  computeCommitFileByFileDiff,
  executeResumableTwoStagePipeline,
  switchBranchWithSafetyGuard,
} from '../gitAndPipeline';
import { diffLines, validateMarkdownStructure } from '../markdownGuard';
import {
  analyzeStyleDistillation,
  approveSandboxScript,
  executeApprovedSandboxScript,
  getDefaultSandboxScripts,
  isScriptApproved,
  parseSkillMarkdown,
} from '../sandboxJs';
import { createSeededWhiteTowerProject } from '../seedData';
import {
  encodeTavernCardToPngBytes,
  evaluateWorldbook,
  importTavernWorldbookJson,
  parseTavernCardFromPngBytes,
} from '../worldbookEngine';

describe('Pillar 1: MarkdownStructureGuard & Diff', () => {
  it('flags NO_TEXT_CHANGE when only markdown emphasis syntax changes', () => {
    const res = validateMarkdownStructure('沈砚推开**白塔**木门。', '沈砚推开*白塔*木门。');
    expect(res.kind).toBe('NO_TEXT_CHANGE');
  });

  it('blocks STRUCTURE_BROKEN when heading, emphasis, scene break, or paragraph split is broken', () => {
    expect(validateMarkdownStructure('## 第一章 寒灯启塔', '第一章 寒灯启塔').kind).toBe('STRUCTURE_BROKEN');
    expect(validateMarkdownStructure('他按住青铜印。', '他按住**青铜印未闭合。').kind).toBe('STRUCTURE_BROKEN');
    expect(validateMarkdownStructure('---', '普通段落').kind).toBe('STRUCTURE_BROKEN');
    expect(validateMarkdownStructure('单段原文。', '拆成段一。\n\n拆成段二。\n\n拆成段三。').kind).toBe('STRUCTURE_BROKEN');
  });

  it('accepts valid structural rewrite and computes line diffs', () => {
    expect(validateMarkdownStructure('> 白塔禁令：子时封塔。', '> 白塔夜禁：子时后违者拿问。').kind).toBe('VALID');
    const diff = diffLines('旧行\n共行', '新行\n共行');
    expect(diff.some((d) => d.op === 'DELETE' && d.text === '旧行')).toBe(true);
    expect(diff.some((d) => d.op === 'INSERT' && d.text === '新行')).toBe(true);
  });
});

describe('Pillar 1: Resumable Two-Stage Pipeline', () => {
  it('reuses persisted draft and skips STAGE_DRAFT when retrying after STAGE_REVIEW failure', async () => {
    let draftCalls = 0;
    let reviewCalls = 0;

    const first = await executeResumableTwoStagePipeline({
      existingRun: null,
      chapterId: 'ch_002',
      taskType: 'CHAPTER_DRAFT',
      promptSummary: '续写三层暗阁对峙',
      generateDraft: async () => {
        draftCalls++;
        return '已落盘的初稿正文内容。';
      },
      runReview: async () => {
        reviewCalls++;
        throw new Error('复审阶段网络超时');
      },
    });

    expect(draftCalls).toBe(1);
    expect(reviewCalls).toBe(1);
    expect(first.run.status).toBe('REVIEW_FAILED');
    expect(first.run.persistedDraft).toBe('已落盘的初稿正文内容。');

    const retry = await executeResumableTwoStagePipeline({
      existingRun: first.run,
      chapterId: 'ch_002',
      taskType: 'CHAPTER_DRAFT',
      promptSummary: '续写三层暗阁对峙',
      generateDraft: async () => {
        draftCalls++;
        return '不应被重新生成';
      },
      runReview: async (persisted) => {
        reviewCalls++;
        return `复审通过：字数 ${persisted.length}`;
      },
    });

    expect(draftCalls).toBe(1);
    expect(reviewCalls).toBe(2);
    expect(retry.reusedPersistedDraft).toBe(true);
    expect(retry.run.status).toBe('COMPLETED');
    expect(retry.run.reviewReport).toContain('复审通过');
  });
});

describe('Pillar 4: Tavern Worldbook Engine & PNG Card Codec', () => {
  it('evaluates primary + secondary AND/NOT logic and recursive triggering', () => {
    const project = createSeededWhiteTowerProject();
    const entries = project.workspace.worldbookEntries;

    const withoutSecondary = evaluateWorldbook(entries, '沈砚白日路过白塔长阶。');
    expect(withoutSecondary.matchedTraces.some((t) => t.entry.id === 'wb_tower_ban')).toBe(false);

    const withSecondary = evaluateWorldbook(entries, '沈砚雪夜违背白塔禁令。');
    expect(withSecondary.matchedTraces.some((t) => t.entry.id === 'wb_tower_ban')).toBe(true);
    const recursiveHit = withSecondary.matchedTraces.find((t) => t.entry.id === 'wb_guard_bureau');
    expect(recursiveHit?.recursionDepth).toBe(1);
  });

  it('imports SillyTavern JSON worldbook and round-trips PNG chara metadata', () => {
    const imported = importTavernWorldbookJson(
      JSON.stringify({
        entries: {
          '0': {
            comment: '司礼监勘合',
            key: ['勘合'],
            keysecondary: ['白塔'],
            selective: true,
            selectiveLogic: 0,
            content: '勘合分阴阳两片。',
            order: 85,
          },
        },
      }),
    );
    expect(imported.length).toBe(1);
    expect(imported[0].secondaryLogic).toBe('AND_ANY');

    const png = encodeTavernCardToPngBytes({
      id: 'c1',
      specVersion: 'chara_card_v2',
      name: '谢晚晴',
      description: '司礼监女史',
      personality: '警觉温婉',
      scenario: '药铺后堂',
      firstMessage: '朱批少了一笔。',
      tags: ['白塔'],
    });
    const decoded = parseTavernCardFromPngBytes(png);
    expect(decoded.name).toBe('谢晚晴');
    expect(decoded.tags).toEqual(['白塔']);
  });
});

describe('Pillar 5: Isolated JS Sandbox & Author Approval Gate', () => {
  it('blocks unapproved and tampered scripts, allows approved execution', async () => {
    const raw = getDefaultSandboxScripts()[0];
    expect(isScriptApproved(raw)).toBe(false);

    let blocked = false;
    try {
      await executeApprovedSandboxScript(raw, '他不禁叹息，总而言之局面已定。');
    } catch {
      blocked = true;
    }
    expect(blocked).toBe(true);

    const approved = approveSandboxScript(raw);
    const report = await executeApprovedSandboxScript(approved, '他不禁叹息，眼中闪过一丝寒光，总而言之局面已定。');
    expect(report.issues.length).toBe(3);

    const tampered = { ...approved, sourceCode: approved.sourceCode + '\n// modified' };
    expect(isScriptApproved(tampered)).toBe(false);

    const pkg = parseSkillMarkdown('---\nname: 节奏控场\ndescription: 悬疑留白\n---\n每章保留未闭合疑问');
    expect(pkg.name).toBe('节奏控场');
    const style = analyzeStyleDistillation('样稿', '风雪未歇。「人呢？」灯芯微颤。');
    expect(style.avgSentenceChars).toBeGreaterThan(0);
  });
});

describe('Pillar 6: Git Multi-Route Branching, Safety Restore Point & EPUB Export', () => {
  it('automatically creates a full-book safety restore point before switching branches and computes file diffs', () => {
    const project = createSeededWhiteTowerProject();
    // Simulate unsaved dirty edit on main
    project.workspace.chapters[1].markdownContent += '\n\n未提交的草稿段落。';

    const { project: switched, safetyPoint } = switchBranchWithSafetyGuard(project, 'route-reconciliation');
    expect(switched.currentBranch).toBe('route-reconciliation');
    expect(safetyPoint.reason).toBe('AUTO_BEFORE_BRANCH_SWITCH');
    expect(safetyPoint.snapshot.chapters[1].markdownContent).toContain('未提交的草稿段落');
    expect(switched.workspace.chapters[1].markdownContent).toContain('共查');

    const diffs = computeCommitFileByFileDiff(switched, switched.commits[0].hash);
    expect(diffs.length).toBeGreaterThan(0);
    expect(diffs.some((d) => d.path === 'chapters/ch_002.md')).toBe(true);

    const epub = buildEpubZipBytes(switched);
    // ZIP magic bytes PK\x03\x04
    expect(epub[0]).toBe(0x50);
    expect(epub[1]).toBe(0x4b);
  });
});
