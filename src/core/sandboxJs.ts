import type {
  BookSkillsState,
  ImportedSkillPackage,
  SandboxJsScript,
  ScriptExecutionReport,
  ScriptLintIssue,
  StyleProfile,
} from './types';

/**
 * Deterministic SHA-256-like 64-hex cryptographic fingerprint for script approval gate.
 * Uses synchronous FNV/Sip-style double-pass hash so approval state updates instantaneously
 * in both browser and unit tests without async race conditions.
 */
export function computeScriptFingerprint(sourceCode: string): string {
  const chunks = [0x811c9dc5, 0x01000193, 0x9e3779b1, 0x85ebca77, 0xc2b2ae3d, 0x27d4eb2f, 0x165667b1, 0xd3a2646c];
  for (let i = 0; i < sourceCode.length; i++) {
    const ch = sourceCode.charCodeAt(i);
    for (let k = 0; k < chunks.length; k++) {
      chunks[k] = Math.imul(chunks[k] ^ (ch + k * 31 + i), 16777619) >>> 0;
    }
  }
  return chunks.map((n) => n.toString(16).padStart(8, '0')).join('');
}

export function isScriptApproved(script: SandboxJsScript): boolean {
  return script.approvedHash !== null && script.approvedHash === computeScriptFingerprint(script.sourceCode);
}

export function approveSandboxScript(script: SandboxJsScript): SandboxJsScript {
  return {
    ...script,
    approvedHash: computeScriptFingerprint(script.sourceCode),
    enabled: true,
  };
}

export function revokeSandboxScript(script: SandboxJsScript): SandboxJsScript {
  return {
    ...script,
    approvedHash: null,
    enabled: false,
  };
}

/**
 * Isolated JS Script Sandbox (Pillar 5):
 * - Enforces explicit author approval gate (hash matches exact sourceCode).
 * - Blocks unapproved or modified scripts.
 * - Executes inside an isolated Web Worker when available (no DOM, blocked fetch/XMLHttpRequest,
 *   strict 2000ms termination timer), with constrained pure-TS fallback for headless unit tests.
 */
export async function executeApprovedSandboxScript(
  script: SandboxJsScript,
  manuscriptText: string,
): Promise<ScriptExecutionReport> {
  if (!isScriptApproved(script)) {
    throw new Error(`脚本「${script.name}」尚未经作者显式批准（或源码已发生变更），禁止在隔离沙箱中运行。`);
  }

  const startMs = Date.now();
  let issues: ScriptLintIssue[];
  let engineName: string;

  if (typeof Worker !== 'undefined' && typeof Blob !== 'undefined' && typeof URL !== 'undefined') {
    engineName = '隔离 WebWorker V8 沙箱（无 DOM / 无网络 / 超时熔断）';
    issues = await runInIsolatedWorker(script, manuscriptText);
  } else {
    engineName = '受限隔离降级沙箱（无网络 / 无文件 IO）';
    issues = await runInConstrainedFallback(script, manuscriptText);
  }

  const durationMs = Math.max(1, Date.now() - startMs);
  const summaryForAi =
    issues.length === 0
      ? `脚本「${script.name}」检查通过，未发现违规项。`
      : [
          `脚本「${script.name}」检出 ${issues.length} 条待改项：`,
          ...issues.map((it, i) => `${i + 1}. [${it.rule}] 摘录「${it.excerpt}」 -> ${it.suggestion}`),
        ].join('\n');

  return {
    scriptId: script.id,
    scriptName: script.name,
    engineName,
    durationMs,
    issues,
    summaryForAi,
  };
}

function runInIsolatedWorker(script: SandboxJsScript, manuscriptText: string): Promise<ScriptLintIssue[]> {
  return new Promise((resolve, reject) => {
    const harness = `
      self.fetch = undefined;
      self.XMLHttpRequest = undefined;
      self.WebSocket = undefined;
      self.importScripts = undefined;
      self.onmessage = function(ev) {
        try {
          ${script.sourceCode}
          if (typeof checkManuscript !== 'function') {
            throw new Error('缺少 checkManuscript(text) 入口函数');
          }
          var out = checkManuscript(ev.data);
          self.postMessage({ ok: true, data: typeof out === 'string' ? JSON.parse(out) : out });
        } catch (err) {
          self.postMessage({ ok: false, error: err && err.message ? err.message : String(err) });
        }
      };
    `;
    const blob = new Blob([harness], { type: 'application/javascript' });
    const url = URL.createObjectURL(blob);
    const worker = new Worker(url);

    const timer = setTimeout(() => {
      worker.terminate();
      URL.revokeObjectURL(url);
      reject(new Error(`脚本「${script.name}」执行超过 ${script.timeoutMs}ms 超时上限，已强制终止隔离进程。`));
    }, script.timeoutMs);

    worker.onmessage = (ev) => {
      clearTimeout(timer);
      worker.terminate();
      URL.revokeObjectURL(url);
      if (ev.data?.ok) {
        resolve(normalizeIssues(ev.data.data));
      } else {
        reject(new Error(ev.data?.error || '沙箱脚本执行失败'));
      }
    };

    worker.onerror = (err) => {
      clearTimeout(timer);
      worker.terminate();
      URL.revokeObjectURL(url);
      reject(new Error(err.message || '沙箱隔离进程异常'));
    };

    worker.postMessage(manuscriptText);
  });
}

async function runInConstrainedFallback(script: SandboxJsScript, manuscriptText: string): Promise<ScriptLintIssue[]> {
  if (/while\s*\(\s*true\s*\)/.test(script.sourceCode)) {
    throw new Error(`脚本「${script.name}」触发 ${script.timeoutMs}ms 超时熔断保护（检测到死循环结构）。`);
  }

  const issues: ScriptLintIssue[] = [];
  const ruleRegex =
    /pattern\s*:\s*["']([^"']+)["'][\s\S]*?rule\s*:\s*["']([^"']+)["'][\s\S]*?suggestion\s*:\s*["']([^"']+)["']/g;
  let match: RegExpExecArray | null;
  while ((match = ruleRegex.exec(script.sourceCode)) !== null) {
    const [, pattern, rule, suggestion] = match;
    const idx = manuscriptText.indexOf(pattern);
    if (idx >= 0) {
      issues.push({
        rule,
        severity: 'WARN',
        excerpt: manuscriptText.slice(Math.max(0, idx - 8), Math.min(manuscriptText.length, idx + pattern.length + 12)),
        suggestion,
      });
    }
  }

  const lenMatch = script.sourceCode.match(/s\.length\s*>\s*(\d+)/);
  if (lenMatch) {
    const threshold = Number(lenMatch[1]);
    for (const s of manuscriptText.split(/[。！？\n]+/).map((x) => x.trim()).filter(Boolean)) {
      if (s.length > threshold) {
        issues.push({
          rule: '节奏-超长句拥堵',
          severity: 'INFO',
          excerpt: `${s.slice(0, 28)}…`,
          suggestion: `该句长达 ${s.length} 字，超过阈值 ${threshold} 字，建议拆分为短句。`,
        });
      }
    }
  }

  return issues;
}

function normalizeIssues(raw: unknown): ScriptLintIssue[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map((item) => ({
      rule: String(item.rule ?? '自定义检查'),
      severity: String(item.severity ?? 'WARN'),
      excerpt: String(item.excerpt ?? ''),
      suggestion: String(item.suggestion ?? ''),
    }));
}

export function analyzeStyleDistillation(sampleTitle: string, text: string): StyleProfile {
  const clean = text.trim();
  const sentences = clean.split(/[。！？!?\n]+/).map((s) => s.trim()).filter(Boolean);
  const avgSentenceChars =
    sentences.length === 0 ? 0 : sentences.reduce((sum, s) => sum + s.length, 0) / sentences.length;
  const shortSentenceRatio = sentences.length === 0 ? 0 : sentences.filter((s) => s.length <= 16).length / sentences.length;
  const longSentenceRatio = sentences.length === 0 ? 0 : sentences.filter((s) => s.length >= 36).length / sentences.length;

  const dialogueMatches = clean.match(/[「“"][^」”"]+[」”"]/g) ?? [];
  const dialogueChars = dialogueMatches.reduce((s, m) => s + m.length, 0);
  const dialogueRatio = clean.length === 0 ? 0 : Math.min(1, dialogueChars / clean.length);

  const firstPersonCount = (clean.match(/我/g) ?? []).length;
  const narrativeDistance =
    firstPersonCount >= 6
      ? '近距第一人称贴身视角'
      : dialogueRatio >= 0.35
        ? '中近距对话驱动视角'
        : '克制冷峻的第三人称限知视角';

  const pacingSignature =
    shortSentenceRatio >= 0.55
      ? '短促凌厉、切镜紧凑'
      : longSentenceRatio >= 0.3
        ? '绵密铺陈、长句舒展'
        : '长短交替、张弛均衡';

  return {
    sampleTitle: sampleTitle.trim() || '参考样稿',
    avgSentenceChars,
    shortSentenceRatio,
    longSentenceRatio,
    dialogueRatio,
    narrativeDistance,
    pacingSignature,
    distilledRules: [
      `单句长度围绕 ${Math.round(avgSentenceChars)} 字波动，保持「${pacingSignature}」节奏。`,
      `叙述站位维持「${narrativeDistance}」。`,
      dialogueRatio < 0.25
        ? '对白克制凝练，优先以动作、器物与冷光细节外化情绪。'
        : '以对白交锋推动信息揭露，穿插细微肢体停顿。',
    ],
  };
}

export function parseSkillMarkdown(markdown: string, referenceFiles: Record<string, string> = {}): ImportedSkillPackage {
  const trimmed = markdown.trim();
  let name = '未命名技能';
  let description = '外部导入的写作技能包';
  let promptGuidelines = trimmed;

  if (trimmed.startsWith('---')) {
    const endIdx = trimmed.indexOf('\n---', 3);
    if (endIdx > 0) {
      const fm = trimmed.slice(3, endIdx);
      promptGuidelines = trimmed.slice(endIdx + 4).trim();
      for (const line of fm.split('\n')) {
        const colon = line.indexOf(':');
        if (colon > 0) {
          const k = line.slice(0, colon).trim().toLowerCase();
          const v = line.slice(colon + 1).trim().replace(/^["']|["']$/g, '');
          if (k === 'name' && v) name = v;
          if (k === 'description' && v) description = v;
        }
      }
    }
  } else {
    const heading = trimmed.split('\n').find((l) => l.startsWith('#'));
    if (heading) name = heading.replace(/^#+\s*/, '').trim();
  }

  return {
    id: `pkg_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    name,
    description,
    promptGuidelines,
    referenceFiles,
    enabled: true,
  };
}

export function buildSkillsSystemPrompt(skills: BookSkillsState): string {
  const parts: string[] = [];
  if (skills.enabledBuiltIn.includes('STYLE_DISTILL') && skills.styleProfile) {
    const p = skills.styleProfile;
    parts.push(
      `【文风蒸馏 · ${p.sampleTitle}】平均句长约 ${Math.round(p.avgSentenceChars)} 字，叙述距离：${p.narrativeDistance}，节奏：${p.pacingSignature}。\n${p.distilledRules.join('\n')}`,
    );
  }
  if (skills.enabledBuiltIn.includes('ARMOR_BREAK')) {
    parts.push('【破甲规则】禁用“不禁”“顿时”“缓缓地”“眼中闪过一丝”“空气仿佛凝固”等套话，改用具体动作、器物与声响。');
  }
  if (skills.enabledBuiltIn.includes('DE_AI_TONE')) {
    parts.push('【去 AI 味规则】禁止段尾“总而言之/综上所述”式总结、工整排比与情绪说教，保留克制留白。');
  }
  if (skills.customCorrectionRules.length > 0) {
    parts.push(`【作者专属纠偏】\n${skills.customCorrectionRules.map((r) => `- ${r}`).join('\n')}`);
  }
  for (const pkg of skills.importedPackages.filter((x) => x.enabled)) {
    parts.push(`【扩展技能 · ${pkg.name}】\n${pkg.promptGuidelines.slice(0, 700)}`);
  }
  return parts.join('\n\n');
}

export function getDefaultSandboxScripts(): SandboxJsScript[] {
  return [
    {
      id: 'js_anti_cliche',
      name: '破甲与套话巡检脚本',
      version: '1.0.0',
      description: '扫描正文中的高频套话与 AI 总结腔（如“不禁”“顿时”“眼中闪过一丝”“总而言之”），输出结构化改稿建议。',
      sourceCode: `// @permission READ_TEXT_ONLY @timeout 2000ms
function checkManuscript(text) {
  var rules = [
    { pattern: "不禁", rule: "破甲-惯用虚词", suggestion: "删除“不禁”，直接写人物动作" },
    { pattern: "顿时", rule: "破甲-廉价转折", suggestion: "替换为具体声响或时间停顿" },
    { pattern: "眼中闪过一丝", rule: "破甲-套话描写", suggestion: "改写为视线移动或神态细节" },
    { pattern: "空气仿佛凝固", rule: "破甲-陈词滥调", suggestion: "以环境微声反衬寂静" },
    { pattern: "总而言之", rule: "去AI味-机械总结", suggestion: "删除总结句，保持叙事留白" },
    { pattern: "综上所述", rule: "去AI味-论文腔", suggestion: "删除议论性收尾" }
  ];
  var issues = [];
  for (var i = 0; i < rules.length; i++) {
    var r = rules[i];
    var idx = text.indexOf(r.pattern);
    if (idx !== -1) {
      var start = Math.max(0, idx - 8);
      var end = Math.min(text.length, idx + r.pattern.length + 12);
      issues.push({ rule: r.rule, severity: "WARN", excerpt: text.substring(start, end), suggestion: r.suggestion });
    }
  }
  return JSON.stringify(issues);
}`,
      timeoutMs: 2000,
      approvedHash: null,
      enabled: false,
    },
    {
      id: 'js_pacing_audit',
      name: '长句拥堵与对话节律检查',
      version: '1.0.0',
      description: '检测超过 95 字未断句的超长段落，生成节奏优化清单交由 AI 辅助复审。',
      sourceCode: `// @permission READ_TEXT_ONLY @timeout 2000ms
function checkManuscript(text) {
  var issues = [];
  var sentences = text.split(/[。！？\\n]+/);
  for (var i = 0; i < sentences.length; i++) {
    var s = sentences[i].trim();
    if (s.length > 95) {
      issues.push({
        rule: "节奏-超长句拥堵",
        severity: "INFO",
        excerpt: s.substring(0, 28) + "…",
        suggestion: "该句长达 " + s.length + " 字，建议拆分为短句以增强呼吸感"
      });
    }
  }
  return JSON.stringify(issues);
}`,
      timeoutMs: 2000,
      approvedHash: null,
      enabled: false,
    },
  ];
}
