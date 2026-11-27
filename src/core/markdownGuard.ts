export type StructureGuardResult =
  | { kind: 'VALID' }
  | { kind: 'NO_TEXT_CHANGE'; message: string }
  | { kind: 'STRUCTURE_BROKEN'; issues: string[] };

export function stripMarkdownToPlainText(markdown: string): string {
  return markdown
    .replace(/\r\n/g, '\n')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^>\s?/gm, '')
    .replace(/^[-*+]\s+/gm, '')
    .replace(/^\d+\.\s+/gm, '')
    .replace(/\*\*/g, '')
    .replace(/~~/g, '')
    .replace(/\*/g, '')
    .replace(/_/g, '')
    .replace(/`/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function isSceneDivider(block: string): boolean {
  return /^(-{3,}|\*{3,}|_{3,})$/.test(block.trim());
}

function countOccurrences(text: string, token: string): number {
  let count = 0;
  let idx = 0;
  while (true) {
    const found = text.indexOf(token, idx);
    if (found < 0) break;
    count++;
    idx = found + token.length;
  }
  return count;
}

/**
 * Pillar 1: Markdown Structure Guard.
 * - Flags NO_TEXT_CHANGE if plain text is identical after stripping Markdown syntax.
 * - Blocks acceptance (STRUCTURE_BROKEN) if heading level, scene break, blockquote,
 *   list markers, paired emphasis/code markers, or single-block split structure is broken.
 */
export function validateMarkdownStructure(
  originalMarkdown: string,
  candidateMarkdown: string,
): StructureGuardResult {
  const orig = originalMarkdown.replace(/\r\n/g, '\n').trim();
  const cand = candidateMarkdown.replace(/\r\n/g, '\n').trim();

  if (!cand) {
    return { kind: 'STRUCTURE_BROKEN', issues: ['候选内容为空，无法替换原段落。'] };
  }

  const issues: string[] = [];

  // 1. Heading level check
  const origHeading = orig.match(/^(#{1,6})\s+/)?.[1] ?? null;
  const candHeading = cand.match(/^(#{1,6})\s+/)?.[1] ?? null;
  if (origHeading !== candHeading) {
    if (origHeading && !candHeading) {
      issues.push(`原段落为 ${origHeading.length} 级标题，候选丢失了标题标记（${origHeading}）。`);
    } else if (!origHeading && candHeading) {
      issues.push(`原段落为正文，候选被意外改写为 ${candHeading.length} 级标题。`);
    } else if (origHeading && candHeading) {
      issues.push(`标题层级发生变化（原为 ${origHeading.length} 级，候选为 ${candHeading.length} 级）。`);
    }
  }

  // 2. Scene divider check
  const origIsDivider = isSceneDivider(orig);
  const candIsDivider = isSceneDivider(cand);
  if (origIsDivider !== candIsDivider) {
    issues.push('场景分隔线结构被破坏（原段落与候选的分隔线标记不一致）。');
  } else {
    const origDivCount = orig.split('\n').filter(isSceneDivider).length;
    const candDivCount = cand.split('\n').filter(isSceneDivider).length;
    if (origDivCount !== candDivCount) {
      issues.push(`场景分隔线数量发生变化（原 ${origDivCount} 处，候选 ${candDivCount} 处）。`);
    }
  }

  // 3. Blockquote structure check
  const origLines = orig.split('\n');
  const candLines = cand.split('\n');
  if (origLines.every((l) => l.trim().startsWith('>')) && !candLines.every((l) => l.trim().startsWith('>'))) {
    issues.push('原段落为引用块（>），候选破坏了引用前缀结构。');
  }

  // 4. List marker check
  if (origLines.every((l) => /^[-*+]\s+/.test(l.trim())) && !candLines.every((l) => /^[-*+]\s+/.test(l.trim()))) {
    issues.push('原段落为无序列表，候选破坏了列表项标记。');
  }
  if (origLines.every((l) => /^\d+\.\s+/.test(l.trim())) && !candLines.every((l) => /^\d+\.\s+/.test(l.trim()))) {
    issues.push('原段落为有序列表，候选破坏了编号列表结构。');
  }

  // 5. Paired emphasis & code markers
  const boldCount = countOccurrences(cand, '**');
  if (boldCount % 2 !== 0) {
    issues.push(`加粗标记 (**) 未成对闭合（共出现 ${boldCount} 次）。`);
  }
  const strikeCount = countOccurrences(cand, '~~');
  if (strikeCount % 2 !== 0) {
    issues.push(`删除线标记 (~~) 未成对闭合（共出现 ${strikeCount} 次）。`);
  }
  const codeCount = countOccurrences(cand, '`');
  if (codeCount % 2 !== 0) {
    issues.push(`行内代码反引号 (\`) 未成对闭合（共出现 ${codeCount} 次）。`);
  }
  const singleAsterisks = cand.replace(/\*\*/g, '').split('*').length - 1;
  if (singleAsterisks % 2 !== 0) {
    issues.push('斜体标记 (*) 未成对闭合，请重新生成或手动修复。');
  }

  // 6. Abnormal single-paragraph split
  const origBlocks = orig.split(/\n\s*\n/).filter((b) => b.trim().length > 0).length;
  const candBlocks = cand.split(/\n\s*\n/).filter((b) => b.trim().length > 0).length;
  if (origBlocks === 1 && candBlocks > 2) {
    issues.push(`单段改稿被异常拆分为 ${candBlocks} 个独立段落，破坏原有章节锚点结构。`);
  }

  if (issues.length > 0) {
    return { kind: 'STRUCTURE_BROKEN', issues };
  }

  // 7. No-text-change detection
  if (stripMarkdownToPlainText(orig) === stripMarkdownToPlainText(cand)) {
    return {
      kind: 'NO_TEXT_CHANGE',
      message: '正文无改动（仅 Markdown 标记或空白有细微变化）',
    };
  }

  return { kind: 'VALID' };
}

export interface DiffLine {
  op: 'EQUAL' | 'INSERT' | 'DELETE';
  text: string;
}

export function diffLines(original: string, revised: string): DiffLine[] {
  const a = original.replace(/\r\n/g, '\n').split('\n');
  const b = revised.replace(/\r\n/g, '\n').split('\n');
  const n = a.length;
  const m = b.length;

  const dp: number[][] = Array.from({ length: n + 1 }, () => Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ op: 'EQUAL', text: a[i] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ op: 'DELETE', text: a[i++] });
    } else {
      out.push({ op: 'INSERT', text: b[j++] });
    }
  }
  while (i < n) out.push({ op: 'DELETE', text: a[i++] });
  while (j < m) out.push({ op: 'INSERT', text: b[j++] });
  return out;
}

export interface AtomicUndoRecord {
  chapterId: string;
  label: string;
  previousMarkdown: string;
  nextMarkdown: string;
  createdAt: number;
}
