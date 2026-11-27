import { diffLines } from './markdownGuard';
import { crc32 } from './worldbookEngine';
import type {
  BookWorkspaceSnapshot,
  ExportFormatType,
  GitCommitNode,
  NovelProject,
  PipelineRunRecord,
  RestorePointRecord,
} from './types';

function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj)) as T;
}

function generateHash(seed: string): string {
  let h1 = 0xdeadbeef ^ seed.length;
  let h2 = 0x41c6ce57 ^ seed.length;
  for (let i = 0; i < seed.length; i++) {
    const ch = seed.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  const part1 = ((h1 >>> 0).toString(16).padStart(8, '0'));
  const part2 = ((h2 >>> 0).toString(16).padStart(8, '0'));
  const part3 = (Date.now() >>> 0).toString(16).padStart(8, '0');
  return `${part1}${part2}${part3}`.slice(0, 20);
}

/**
 * Serializes a BookWorkspaceSnapshot into canonical Git-First file paths
 * (`chapters/*.md`, `handoffs/*.json`, `lore/*.json`, `worldbook/entries.json`, `foreshadows.json`)
 * so file-by-file Git diffs mirror the exact Android JGit repo structure.
 */
export function snapshotToRepoFiles(snapshot: BookWorkspaceSnapshot): Record<string, string> {
  const files: Record<string, string> = {};
  for (const ch of snapshot.chapters) {
    files[`chapters/${ch.chapterId}.md`] = ch.markdownContent;
  }
  for (const h of snapshot.handoffs) {
    files[`handoffs/${h.chapterId}.json`] = JSON.stringify(h, null, 2);
  }
  files['lore/entities.json'] = JSON.stringify(snapshot.loreEntries, null, 2);
  files['lore/causal_graph.json'] = JSON.stringify(snapshot.causalGraph, null, 2);
  files['lore/plot_plans.json'] = JSON.stringify(snapshot.plotPlans, null, 2);
  files['worldbook/entries.json'] = JSON.stringify(snapshot.worldbookEntries, null, 2);
  files['foreshadows.json'] = JSON.stringify(snapshot.foreshadows, null, 2);
  return files;
}

export interface FileDiffSummary {
  path: string;
  changeType: 'ADD' | 'MODIFY' | 'DELETE';
  addedLines: number;
  deletedLines: number;
  unifiedPatch: string;
}

export function computeCommitFileByFileDiff(
  project: NovelProject,
  targetCommitHash: string,
): FileDiffSummary[] {
  const target = project.commits.find((c) => c.hash === targetCommitHash);
  if (!target) return [];
  const parent = target.parentHash ? project.commits.find((c) => c.hash === target.parentHash) : null;

  const oldFiles = parent ? snapshotToRepoFiles(parent.snapshot) : {};
  const newFiles = snapshotToRepoFiles(target.snapshot);
  const allPaths = Array.from(new Set([...Object.keys(oldFiles), ...Object.keys(newFiles)])).sort();

  const diffs: FileDiffSummary[] = [];
  for (const filePath of allPaths) {
    const oldContent = oldFiles[filePath];
    const newContent = newFiles[filePath];
    if (oldContent === newContent) continue;

    const lines = diffLines(oldContent ?? '', newContent ?? '');
    let addedLines = 0;
    let deletedLines = 0;
    const patchLines = [`--- a/${filePath}`, `+++ b/${filePath}`];
    for (const line of lines) {
      if (line.op === 'INSERT') {
        addedLines++;
        patchLines.push(`+ ${line.text}`);
      } else if (line.op === 'DELETE') {
        deletedLines++;
        patchLines.push(`- ${line.text}`);
      } else {
        patchLines.push(`  ${line.text}`);
      }
    }

    diffs.push({
      path: filePath,
      changeType: oldContent === undefined ? 'ADD' : newContent === undefined ? 'DELETE' : 'MODIFY',
      addedLines,
      deletedLines,
      unifiedPatch: patchLines.join('\n'),
    });
  }
  return diffs;
}

export function createRestorePointForProject(
  project: NovelProject,
  label: string,
  reason: string,
): RestorePointRecord {
  const currentHead =
    project.branches.find((b) => b.name === project.currentBranch)?.headCommitHash ??
    project.commits[0]?.hash ??
    '';
  return {
    id: `rp_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    label,
    reason,
    branchName: project.currentBranch,
    commitHash: currentHead,
    createdAt: Date.now(),
    snapshot: deepClone(project.workspace),
  };
}

export function commitWorkspace(project: NovelProject, message: string): NovelProject {
  const currentHead =
    project.branches.find((b) => b.name === project.currentBranch)?.headCommitHash ?? null;
  const hash = generateHash(`${project.currentBranch}:${message}:${Date.now()}`);
  const commit: GitCommitNode = {
    hash,
    shortHash: hash.slice(0, 7),
    message: message.trim() || '保存书稿变更',
    author: project.author,
    timestamp: Date.now(),
    parentHash: currentHead,
    snapshot: deepClone(project.workspace),
  };

  return {
    ...project,
    commits: [commit, ...project.commits],
    branches: project.branches.map((b) =>
      b.name === project.currentBranch ? { ...b, headCommitHash: hash } : b,
    ),
    updatedAt: Date.now(),
  };
}

/**
 * Pillar 6 Mandatory Anti-Loss Guard:
 * Always creates an automatic full-book restore point before switching branches or loading history.
 */
export function createBranchWithSafetyGuard(
  project: NovelProject,
  branchName: string,
  startCommitHash?: string | null,
): { project: NovelProject; safetyPoint: RestorePointRecord } {
  const cleanName = branchName.trim().replace(/\s+/g, '-') || `route-${Date.now() % 10000}`;
  const safetyPoint = createRestorePointForProject(
    project,
    `新建并切换分支 ${cleanName} 前自动备份`,
    'AUTO_BEFORE_BRANCH_CREATE',
  );
  const targetHash =
    startCommitHash ||
    project.branches.find((b) => b.name === project.currentBranch)?.headCommitHash ||
    project.commits[0]?.hash ||
    '';
  const targetCommit = project.commits.find((c) => c.hash === targetHash);
  const nextWorkspace = targetCommit ? deepClone(targetCommit.snapshot) : deepClone(project.workspace);

  const nextBranches = project.branches.some((b) => b.name === cleanName)
    ? project.branches
    : [...project.branches, { name: cleanName, headCommitHash: targetHash }];

  return {
    safetyPoint,
    project: {
      ...project,
      currentBranch: cleanName,
      branches: nextBranches,
      workspace: nextWorkspace,
      restorePoints: [safetyPoint, ...project.restorePoints],
      updatedAt: Date.now(),
    },
  };
}

export function switchBranchWithSafetyGuard(
  project: NovelProject,
  targetBranchName: string,
): { project: NovelProject; safetyPoint: RestorePointRecord } {
  const targetRef = project.branches.find((b) => b.name === targetBranchName);
  if (!targetRef) throw new Error(`分支不存在: ${targetBranchName}`);

  const safetyPoint = createRestorePointForProject(
    project,
    `切换至分支 ${targetBranchName} 前自动备份`,
    'AUTO_BEFORE_BRANCH_SWITCH',
  );
  const targetCommit = project.commits.find((c) => c.hash === targetRef.headCommitHash);
  const nextWorkspace = targetCommit ? deepClone(targetCommit.snapshot) : deepClone(project.workspace);

  return {
    safetyPoint,
    project: {
      ...project,
      currentBranch: targetBranchName,
      workspace: nextWorkspace,
      restorePoints: [safetyPoint, ...project.restorePoints],
      updatedAt: Date.now(),
    },
  };
}

export function loadCommitWithSafetyGuard(
  project: NovelProject,
  commitHash: string,
): { project: NovelProject; safetyPoint: RestorePointRecord } {
  const commit = project.commits.find((c) => c.hash === commitHash);
  if (!commit) throw new Error(`未找到历史提交: ${commitHash}`);

  const safetyPoint = createRestorePointForProject(
    project,
    `载入历史提交 ${commit.shortHash} 前自动备份`,
    'AUTO_BEFORE_HISTORICAL_LOAD',
  );

  const restoredProject: NovelProject = {
    ...project,
    workspace: deepClone(commit.snapshot),
    restorePoints: [safetyPoint, ...project.restorePoints],
  };

  return {
    safetyPoint,
    project: commitWorkspace(restoredProject, `载入历史提交 ${commit.shortHash}`),
  };
}

export function rollbackToRestorePoint(
  project: NovelProject,
  restorePointId: string,
): { project: NovelProject; preRollbackPoint: RestorePointRecord } {
  const target = project.restorePoints.find((rp) => rp.id === restorePointId);
  if (!target) throw new Error('还原点不存在');

  const preRollbackPoint = createRestorePointForProject(
    project,
    '回滚还原点前自动备份',
    'AUTO_BEFORE_RESTORE_POINT',
  );

  return {
    preRollbackPoint,
    project: {
      ...project,
      workspace: deepClone(target.snapshot),
      restorePoints: [preRollbackPoint, ...project.restorePoints],
      updatedAt: Date.now(),
    },
  };
}

/**
 * Pillar 1: Two-Stage Resumable Pipeline (`STAGE_DRAFT` -> `STAGE_REVIEW`).
 * When STAGE_REVIEW fails after STAGE_DRAFT persisted the draft, calling resume
 * strictly skips generateDraft() and reuses persistedDraft.
 */
export async function executeResumableTwoStagePipeline(params: {
  existingRun: PipelineRunRecord | null;
  chapterId: string;
  taskType: string;
  promptSummary: string;
  generateDraft: () => Promise<string>;
  runReview: (draftText: string) => Promise<string>;
}): Promise<{ run: PipelineRunRecord; reusedPersistedDraft: boolean }> {
  const { existingRun, chapterId, taskType, promptSummary, generateDraft, runReview } = params;
  const canReuse =
    existingRun !== null &&
    existingRun.persistedDraft !== null &&
    existingRun.persistedDraft.length > 0 &&
    (existingRun.status === 'DRAFT_SAVED' || existingRun.status === 'REVIEW_FAILED');

  let current: PipelineRunRecord = existingRun ?? {
    runId: `run_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    chapterId,
    taskType,
    stage: 'STAGE_DRAFT',
    status: 'RUNNING',
    promptSummary,
    persistedDraft: null,
    reviewReport: null,
    errorMessage: null,
    draftAttempts: 0,
    reviewAttempts: 0,
    updatedAt: Date.now(),
  };

  let draftText: string;
  let reusedPersistedDraft = false;

  if (canReuse && existingRun?.persistedDraft) {
    draftText = existingRun.persistedDraft;
    reusedPersistedDraft = true;
  } else {
    current = {
      ...current,
      stage: 'STAGE_DRAFT',
      status: 'RUNNING',
      errorMessage: null,
      draftAttempts: current.draftAttempts + 1,
      updatedAt: Date.now(),
    };
    try {
      draftText = await generateDraft();
    } catch (err) {
      return {
        reusedPersistedDraft: false,
        run: {
          ...current,
          status: 'FAILED',
          errorMessage: err instanceof Error ? err.message : '初稿生成失败',
          updatedAt: Date.now(),
        },
      };
    }
    current = {
      ...current,
      stage: 'STAGE_REVIEW',
      status: 'DRAFT_SAVED',
      persistedDraft: draftText,
      updatedAt: Date.now(),
    };
  }

  current = {
    ...current,
    stage: 'STAGE_REVIEW',
    status: 'RUNNING',
    reviewAttempts: current.reviewAttempts + 1,
    errorMessage: null,
    updatedAt: Date.now(),
  };

  try {
    const report = await runReview(draftText);
    return {
      reusedPersistedDraft,
      run: {
        ...current,
        stage: 'STAGE_COMPLETED',
        status: 'COMPLETED',
        reviewReport: report,
        updatedAt: Date.now(),
      },
    };
  } catch (err) {
    return {
      reusedPersistedDraft,
      run: {
        ...current,
        stage: 'STAGE_REVIEW',
        status: 'REVIEW_FAILED',
        errorMessage: err instanceof Error ? err.message : '自动复审失败',
        updatedAt: Date.now(),
      },
    };
  }
}

// --- Multi-Format Exporter (JSON / Markdown / TXT / Spec-Compliant EPUB ZIP) ---

export function buildExportBlob(project: NovelProject, format: ExportFormatType): { blob: Blob; filename: string } {
  const safeName = project.title.replace(/[^\p{L}\p{N}_-]/gu, '_') || 'qingjian_book';
  const sortedChapters = [...project.workspace.chapters].sort((a, b) => a.order - b.order);

  if (format === 'JSON') {
    const payload = JSON.stringify(
      {
        format: 'qingjian-book-v1',
        title: project.title,
        author: project.author,
        genre: project.genre,
        synopsis: project.synopsis,
        branch: project.currentBranch,
        workspace: project.workspace,
      },
      null,
      2,
    );
    return {
      blob: new Blob([payload], { type: 'application/json;charset=utf-8' }),
      filename: `${safeName}.json`,
    };
  }

  if (format === 'MARKDOWN') {
    const md = [
      `# ${project.title}`,
      '',
      `作者：${project.author}`,
      project.synopsis ? `\n> ${project.synopsis}\n` : '',
      ...sortedChapters.flatMap((ch) => [`## ${ch.volumeTitle} · ${ch.title}`, '', ch.markdownContent.trim(), '', '---', '']),
    ].join('\n');
    return {
      blob: new Blob([md], { type: 'text/markdown;charset=utf-8' }),
      filename: `${safeName}.md`,
    };
  }

  if (format === 'TXT') {
    const txt = [
      project.title,
      `作者：${project.author}`,
      '',
      ...sortedChapters.flatMap((ch) => [
        `${ch.volumeTitle} · ${ch.title}`,
        '',
        ch.markdownContent.replace(/^#{1,6}\s+/gm, '').replace(/\*\*(.+?)\*\*/g, '$1'),
        '',
        '',
      ]),
    ].join('\n');
    return {
      blob: new Blob([txt], { type: 'text/plain;charset=utf-8' }),
      filename: `${safeName}.txt`,
    };
  }

  // Real stored-ZIP EPUB archive
  const epubBytes = buildEpubZipBytes(project, sortedChapters);
  const copy = new Uint8Array(epubBytes.length);
  copy.set(epubBytes);
  return {
    blob: new Blob([copy.buffer], { type: 'application/epub+zip' }),
    filename: `${safeName}.epub`,
  };
}

function escapeXml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

export function buildEpubZipBytes(project: NovelProject, chapters = project.workspace.chapters): Uint8Array {
  const enc = new TextEncoder();
  const entries: Array<{ name: string; data: Uint8Array }> = [
    { name: 'mimetype', data: enc.encode('application/epub+zip') },
    {
      name: 'META-INF/container.xml',
      data: enc.encode(`<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`),
    },
  ];

  chapters.forEach((ch, idx) => {
    const body = ch.markdownContent
      .split(/\n\s*\n/)
      .map((b) => b.trim())
      .filter(Boolean)
      .map((b) =>
        /^-{3,}$/.test(b) ? '<hr/>' : `<p>${escapeXml(b.replace(/^#{1,6}\s+/, ''))}</p>`,
      )
      .join('\n');
    entries.push({
      name: `OEBPS/ch_${idx + 1}.xhtml`,
      data: enc.encode(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>${escapeXml(ch.title)}</title></head>
<body><h1>${escapeXml(ch.title)}</h1>${body}</body>
</html>`),
    });
  });

  const manifestItems = chapters
    .map((_, idx) => `<item id="ch_${idx + 1}" href="ch_${idx + 1}.xhtml" media-type="application/xhtml+xml"/>`)
    .join('\n    ');
  const spineItems = chapters.map((_, idx) => `<itemref idref="ch_${idx + 1}"/>`).join('\n    ');
  entries.push({
    name: 'OEBPS/content.opf',
    data: enc.encode(`<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeXml(project.title)}</dc:title>
    <dc:creator>${escapeXml(project.author)}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId">urn:qingjian:${escapeXml(project.id)}</dc:identifier>
  </metadata>
  <manifest>
    ${manifestItems}
  </manifest>
  <spine>
    ${spineItems}
  </spine>
</package>`),
  });

  return createStoredZip(entries);
}

function createStoredZip(entries: Array<{ name: string; data: Uint8Array }>): Uint8Array {
  const enc = new TextEncoder();
  const localParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = enc.encode(entry.name);
    const crc = crc32(entry.data);
    const localHeader = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(localHeader.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true);
    lv.setUint16(8, 0, true); // stored
    lv.setUint32(14, crc, true);
    lv.setUint32(18, entry.data.length, true);
    lv.setUint32(22, entry.data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    localHeader.set(nameBytes, 30);

    localParts.push(localHeader, entry.data);

    const central = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, entry.data.length, true);
    cv.setUint32(24, entry.data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, offset, true);
    central.set(nameBytes, 46);
    centralParts.push(central);

    offset += localHeader.length + entry.data.length;
  }

  const centralSize = centralParts.reduce((s, p) => s + p.length, 0);
  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);

  const all = [...localParts, ...centralParts, eocd];
  const totalLen = all.reduce((s, p) => s + p.length, 0);
  const out = new Uint8Array(totalLen);
  let pos = 0;
  for (const p of all) {
    out.set(p, pos);
    pos += p.length;
  }
  return out;
}
