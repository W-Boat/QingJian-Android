import React, { useState } from 'react';
import {
  CheckCircle2,
  FileCode2,
  Play,
  Send,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Upload,
  Wand2,
} from 'lucide-react';
import { requestAiCompletion } from '../core/aiClient';
import {
  analyzeStyleDistillation,
  approveSandboxScript,
  computeScriptFingerprint,
  executeApprovedSandboxScript,
  isScriptApproved,
  parseSkillMarkdown,
  revokeSandboxScript,
} from '../core/sandboxJs';
import type {
  AiAccountProfile,
  AiUsageLog,
  BuiltInSkillType,
  NovelProject,
  ScriptExecutionReport,
} from '../core/types';

interface SkillsSandboxViewProps {
  project: NovelProject;
  activeChapterId: string;
  activeProfile: AiAccountProfile;
  onUpdateProject: (updater: (prev: NovelProject) => NovelProject) => void;
  onSendReportToEditor: (reportSummary: string) => void;
  onLogUsage: (usage: AiUsageLog) => void;
  onToast: (msg: string) => void;
}

const BUILTIN_SKILL_META: Array<{
  id: BuiltInSkillType;
  title: string;
  desc: string;
}> = [
  {
    id: 'STYLE_DISTILL',
    title: '文风蒸馏',
    desc: '定量抽取样稿句长分布、对白占比、叙述距离与切镜节奏，写入系统级约束并支持持续纠偏。',
  },
  {
    id: 'ARMOR_BREAK',
    title: '破甲',
    desc: '强力清除“不禁”“顿时”“眼中闪过一丝”“空气仿佛凝固”等高频套话，强制改用具体器物与动作。',
  },
  {
    id: 'DE_AI_TONE',
    title: '去 AI 味',
    desc: '禁止段尾“总而言之/综上所述”式总结腔、工整排比与廉价心理说教，保留长篇小说留白。',
  },
];

export const SkillsSandboxView: React.FC<SkillsSandboxViewProps> = ({
  project,
  activeChapterId,
  activeProfile,
  onUpdateProject,
  onSendReportToEditor,
  onLogUsage,
  onToast,
}) => {
  const [styleSampleTitle, setStyleSampleTitle] = useState(
    project.skills.styleProfile?.sampleTitle ?? '参考样稿',
  );
  const [styleSampleText, setStyleSampleText] = useState(
    '永熙十四年腊月初七，雪粒打在白塔青灰色的檐瓦上，发出细密如盐的声响。沈砚提着一盏防风铜灯拾级而上，靴底踩碎石阶薄冰。「你来早了一刻。」阴影里传来低缓脚步声。',
  );
  const [newRule, setNewRule] = useState('');

  // First-time AI Configuration Wizard
  const [wizardOpen, setWizardOpen] = useState(!project.aiWizardCompleted);
  const [wizardGoal, setWizardGoal] = useState('冷峻克制的古风悬疑，注重物证推理与留白，严禁说教腔');
  const [wizardBusy, setWizardBusy] = useState(false);

  // SKILL.md import
  const [skillMdInput, setSkillMdInput] = useState(`---
name: 悬疑物证细节强化
description: 要求关键场景必须通过至少一件器物磨损或光影变化推动线索揭示
---
1. 每场密室对话须包含至少一处器物触感或微声描写。
2. 禁止直接由旁白点破人物内心盘算，须借停顿与动作反衬。`);

  // JS Sandbox execution state
  const [runningScriptId, setRunningScriptId] = useState<string | null>(null);
  const [latestReport, setLatestReport] = useState<ScriptExecutionReport | null>(null);
  const [sandboxError, setSandboxError] = useState<string | null>(null);

  const activeChapter =
    project.workspace.chapters.find((c) => c.chapterId === activeChapterId) ??
    project.workspace.chapters[0];

  const toggleBuiltIn = (skillId: BuiltInSkillType) => {
    onUpdateProject((p) => {
      const has = p.skills.enabledBuiltIn.includes(skillId);
      return {
        ...p,
        skills: {
          ...p.skills,
          enabledBuiltIn: has
            ? p.skills.enabledBuiltIn.filter((x) => x !== skillId)
            : [...p.skills.enabledBuiltIn, skillId],
        },
      };
    });
  };

  const handleRunWizard = async () => {
    setWizardBusy(true);
    try {
      const res = await requestAiCompletion({
        profile: activeProfile,
        systemPrompt: '你是青简建书文风配置向导，根据作者目标生成 3 条精炼的中文写作纠偏准则，每条一行。严禁 emoji。',
        userPrompt: `作品：《${project.title}》（${project.genre}），作者期望风格：${wizardGoal}`,
        taskLabel: '首次建书 AI 技能配置向导',
        localFallbackGenerator: () =>
          [
            `围绕「${wizardGoal.slice(0, 18)}」保持冷峻克制叙述，优先通过器物与声响承载信息`,
            '每段严禁出现“不禁/顿时/眼中闪过一丝/总而言之”等高频套话',
            '对白保持简短锋利，人物知情边界严格遵循上章定稿交接稿',
          ].join('\n'),
      });
      onLogUsage(res.usage);
      const rules = res.text
        .split('\n')
        .map((s) => s.replace(/^[\d.\-*、\s]+/, '').trim())
        .filter(Boolean)
        .slice(0, 4);

      onUpdateProject((p) => ({
        ...p,
        aiWizardCompleted: true,
        skills: {
          ...p.skills,
          enabledBuiltIn: ['STYLE_DISTILL', 'ARMOR_BREAK', 'DE_AI_TONE'],
          customCorrectionRules: Array.from(new Set([...rules, ...p.skills.customCorrectionRules])),
        },
      }));
      setWizardOpen(false);
      onToast('AI 配置向导已完成，已为本书启用三项核心技能与专属纠偏准则');
    } finally {
      setWizardBusy(false);
    }
  };

  const handleRunSandboxScript = async (scriptId: string) => {
    const script = project.skills.scripts.find((s) => s.id === scriptId);
    if (!script) return;
    setRunningScriptId(scriptId);
    setSandboxError(null);
    setLatestReport(null);
    try {
      const report = await executeApprovedSandboxScript(script, activeChapter.markdownContent);
      setLatestReport(report);
      onToast(`隔离沙箱执行完成：检出 ${report.issues.length} 条建议 (${report.durationMs}ms)`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : '隔离沙箱执行失败';
      setSandboxError(msg);
      onToast(msg);
    } finally {
      setRunningScriptId(null);
    }
  };

  return (
    <div className="h-full overflow-y-auto bg-[#F7F7F5] px-5 py-6 md:px-10 dark:bg-[#0E1012]">
      <div className="mx-auto max-w-6xl space-y-6">
        {/* Header */}
        <div className="flex flex-wrap items-end justify-between gap-3 border-b border-zinc-200 pb-4 dark:border-zinc-800">
          <div>
            <h1 className="text-xl font-semibold text-zinc-900 dark:text-zinc-100">
              写作技能与隔离 JS 沙箱 · {project.title}
            </h1>
            <p className="mt-1 text-xs text-zinc-500">
              按书独立配置三大内置技能、外部 SKILL.md 技能包与无网络/无文件 IO 的隔离 JS 检查脚本
            </p>
          </div>
          <button
            type="button"
            onClick={() => setWizardOpen((v) => !v)}
            className="flex items-center gap-1.5 rounded-lg border border-zinc-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-800 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
          >
            <Sparkles className="h-3.5 w-3.5" />
            AI 配置向导
          </button>
        </div>

        {/* AI First-Time Configuration Wizard */}
        {wizardOpen && (
          <div className="rounded-xl border border-zinc-300 bg-white p-5 dark:border-zinc-700 dark:bg-[#16191D]">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                首次建书 AI 技能配置向导
              </h2>
              <button
                type="button"
                onClick={() => setWizardOpen(false)}
                className="text-xs text-zinc-500 hover:underline"
              >
                收起
              </button>
            </div>
            <p className="mt-1 text-xs text-zinc-500">
              描述你期望的文风与禁忌，向导将自动为本书开启适配技能并生成作者专属纠偏规则。
            </p>
            <div className="mt-3 flex flex-col gap-2.5 sm:flex-row">
              <input
                value={wizardGoal}
                onChange={(e) => setWizardGoal(e.target.value)}
                className="flex-1 rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-xs dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
              />
              <button
                type="button"
                disabled={wizardBusy}
                onClick={handleRunWizard}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="flex items-center justify-center gap-1.5 rounded-lg px-4 py-2 text-xs font-medium text-white whitespace-nowrap shrink-0 disabled:opacity-50"
              >
                <Wand2 className="h-3.5 w-3.5" />
                {wizardBusy ? '向导配置中…' : '一键生成本书技能配置'}
              </button>
            </div>
          </div>
        )}

        {/* Section 1: 3 Built-In Skills (Per-Book Toggles) */}
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          {BUILTIN_SKILL_META.map((item) => {
            const enabled = project.skills.enabledBuiltIn.includes(item.id);
            return (
              <div
                key={item.id}
                className={`rounded-xl border bg-white p-4 transition dark:bg-[#16191D] ${
                  enabled
                    ? 'border-zinc-900 dark:border-zinc-200'
                    : 'border-zinc-200 opacity-75 dark:border-zinc-800'
                }`}
              >
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                    {item.title}
                  </h3>
                  <button
                    type="button"
                    onClick={() => toggleBuiltIn(item.id)}
                    style={enabled ? { backgroundColor: 'var(--qj-accent)' } : undefined}
                    className={`rounded-md px-2.5 py-1 text-[11px] font-medium transition ${
                      enabled
                        ? 'text-white'
                        : 'border border-zinc-200 text-zinc-500 dark:border-zinc-700'
                    }`}
                  >
                    {enabled ? '已开启' : '未开启'}
                  </button>
                </div>
                <p className="mt-2 text-xs leading-relaxed text-zinc-600 dark:text-zinc-400">
                  {item.desc}
                </p>
              </div>
            );
          })}
        </div>

        {/* Section 2: Style Distillation & Custom Correction Rules */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
          <div className="rounded-xl border border-zinc-200 bg-white p-5 lg:col-span-7 dark:border-zinc-800 dark:bg-[#16191D]">
            <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              文风蒸馏定量分析与持续纠偏
            </h2>
            <p className="mt-1 text-xs text-zinc-500">
              粘贴参考样稿，提取句长分布、短长句比、对白比例与叙述距离
            </p>

            <div className="mt-3 space-y-2.5 text-xs">
              <input
                value={styleSampleTitle}
                onChange={(e) => setStyleSampleTitle(e.target.value)}
                placeholder="样稿名称"
                className="w-full rounded-md border border-zinc-200 bg-zinc-50 px-3 py-1.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
              />
              <textarea
                rows={3}
                value={styleSampleText}
                onChange={(e) => setStyleSampleText(e.target.value)}
                className="w-full rounded-md border border-zinc-200 bg-zinc-50 p-2.5 leading-6 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
              />
              <button
                type="button"
                onClick={() => {
                  const profile = analyzeStyleDistillation(styleSampleTitle, styleSampleText);
                  onUpdateProject((p) => ({
                    ...p,
                    skills: { ...p.skills, styleProfile: profile },
                  }));
                  onToast('文风特征已重新蒸馏并写入系统上下文');
                }}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="rounded-md px-3.5 py-1.5 font-medium text-white"
              >
                重新蒸馏样稿特征
              </button>
            </div>

            {project.skills.styleProfile && (
              <div className="mt-4 rounded-lg border border-zinc-200 bg-zinc-50 p-3.5 text-xs dark:border-zinc-800 dark:bg-zinc-900/60">
                <div className="grid grid-cols-3 gap-3 border-b border-zinc-200 pb-3 dark:border-zinc-800">
                  <div>
                    <div className="text-[11px] text-zinc-500">平均句长</div>
                    <div className="font-mono-tabular mt-0.5 text-base font-semibold text-zinc-900 dark:text-zinc-100">
                      {Math.round(project.skills.styleProfile.avgSentenceChars)} 字
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-zinc-500">短句占比</div>
                    <div className="font-mono-tabular mt-0.5 text-base font-semibold text-zinc-900 dark:text-zinc-100">
                      {Math.round(project.skills.styleProfile.shortSentenceRatio * 100)}%
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-zinc-500">对白占比</div>
                    <div className="font-mono-tabular mt-0.5 text-base font-semibold text-zinc-900 dark:text-zinc-100">
                      {Math.round(project.skills.styleProfile.dialogueRatio * 100)}%
                    </div>
                  </div>
                </div>
                <div className="mt-2.5 space-y-1 text-zinc-600 dark:text-zinc-400">
                  <div>叙述距离：{project.skills.styleProfile.narrativeDistance}</div>
                  <div>节奏特征：{project.skills.styleProfile.pacingSignature}</div>
                </div>
              </div>
            )}

            {/* Custom correction rules */}
            <div className="mt-4 space-y-2 border-t border-zinc-100 pt-4 text-xs dark:border-zinc-800">
              <div className="font-semibold text-zinc-800 dark:text-zinc-200">
                作者持续纠偏规则（{project.skills.customCorrectionRules.length} 条）
              </div>
              <div className="flex gap-2">
                <input
                  value={newRule}
                  onChange={(e) => setNewRule(e.target.value)}
                  placeholder="新增纠偏要求（如：严禁出现现代职场词汇）"
                  className="flex-1 rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
                <button
                  type="button"
                  onClick={() => {
                    if (!newRule.trim()) return;
                    onUpdateProject((p) => ({
                      ...p,
                      skills: {
                        ...p.skills,
                        customCorrectionRules: [newRule.trim(), ...p.skills.customCorrectionRules],
                      },
                    }));
                    setNewRule('');
                  }}
                  className="rounded-md border border-zinc-300 px-3 py-1.5 font-medium dark:border-zinc-700"
                >
                  添加
                </button>
              </div>
              <ul className="space-y-1">
                {project.skills.customCorrectionRules.map((rule, idx) => (
                  <li
                    key={idx}
                    className="flex items-center justify-between rounded bg-zinc-50 px-2.5 py-1.5 text-zinc-700 dark:bg-zinc-900 dark:text-zinc-300"
                  >
                    <span>{rule}</span>
                    <button
                      type="button"
                      onClick={() =>
                        onUpdateProject((p) => ({
                          ...p,
                          skills: {
                            ...p.skills,
                            customCorrectionRules: p.skills.customCorrectionRules.filter(
                              (_, i) => i !== idx,
                            ),
                          },
                        }))
                      }
                      className="text-[11px] text-zinc-400 hover:text-rose-500"
                    >
                      移除
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>

          {/* Section 3: SKILL.md Importer */}
          <div className="rounded-xl border border-zinc-200 bg-white p-5 lg:col-span-5 dark:border-zinc-800 dark:bg-[#16191D]">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
              <Upload className="h-4 w-4" />
              导入外部 SKILL.md 技能包
            </h2>
            <p className="mt-1 text-xs text-zinc-500">
              支持标准 YAML Frontmatter（name / description）与参考规范，启用后自动注入写作上下文
            </p>
            <textarea
              rows={7}
              value={skillMdInput}
              onChange={(e) => setSkillMdInput(e.target.value)}
              className="font-mono-tabular mt-3 w-full rounded-lg border border-zinc-200 bg-zinc-50 p-3 text-xs leading-5 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
            />
            <button
              type="button"
              onClick={() => {
                const pkg = parseSkillMarkdown(skillMdInput);
                onUpdateProject((p) => ({
                  ...p,
                  skills: {
                    ...p.skills,
                    importedPackages: [pkg, ...p.skills.importedPackages],
                  },
                }));
                onToast(`已导入技能包「${pkg.name}」`);
              }}
              style={{ backgroundColor: 'var(--qj-accent)' }}
              className="mt-2.5 w-full rounded-md py-2 text-xs font-medium text-white"
            >
              解析并挂载 SKILL.md
            </button>

            <div className="mt-4 space-y-2 text-xs">
              {project.skills.importedPackages.map((pkg) => (
                <div
                  key={pkg.id}
                  className="rounded-lg border border-zinc-200 p-3 dark:border-zinc-800"
                >
                  <div className="flex items-center justify-between font-semibold text-zinc-900 dark:text-zinc-100">
                    <span>{pkg.name}</span>
                    <label className="flex items-center gap-1 font-normal text-zinc-500">
                      <input
                        type="checkbox"
                        checked={pkg.enabled}
                        onChange={(e) =>
                          onUpdateProject((p) => ({
                            ...p,
                            skills: {
                              ...p.skills,
                              importedPackages: p.skills.importedPackages.map((x) =>
                                x.id === pkg.id ? { ...x, enabled: e.target.checked } : x,
                              ),
                            },
                          }))
                        }
                      />
                      启用
                    </label>
                  </div>
                  <p className="mt-1 text-[11px] text-zinc-500">{pkg.description}</p>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Section 4: Isolated JS Script Sandbox with Author Approval Gate */}
        <div className="rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-[#16191D]">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-200 pb-3 dark:border-zinc-800">
            <div>
              <h2 className="flex items-center gap-1.5 text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                <FileCode2 className="h-4 w-4" />
                隔离 JavaScript 技能脚本沙箱（无网络 · 无文件写入 · 2000ms 超时熔断 · 须作者显式批准）
              </h2>
              <p className="mt-0.5 text-xs text-zinc-500">
                当前待检章节：《{activeChapter.title}》。脚本源码指纹发生任何变动都将自动撤销批准。
              </p>
            </div>
          </div>

          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            {project.skills.scripts.map((script) => {
              const approved = isScriptApproved(script);
              const fp = computeScriptFingerprint(script.sourceCode).slice(0, 12);
              return (
                <div
                  key={script.id}
                  className="flex flex-col justify-between rounded-xl border border-zinc-200 p-4 text-xs dark:border-zinc-800"
                >
                  <div>
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                        {script.name}
                      </span>
                      <span
                        className={`flex items-center gap-1 font-medium ${
                          approved
                            ? 'text-emerald-700 dark:text-emerald-400'
                            : 'text-amber-700 dark:text-amber-400'
                        }`}
                      >
                        {approved ? (
                          <>
                            <ShieldCheck className="h-3.5 w-3.5" />
                            已批准执行
                          </>
                        ) : (
                          <>
                            <ShieldAlert className="h-3.5 w-3.5" />
                            未批准（禁止运行）
                          </>
                        )}
                      </span>
                    </div>
                    <p className="mt-1 text-zinc-600 dark:text-zinc-400">{script.description}</p>
                    <div className="font-mono-tabular mt-1.5 text-[11px] text-zinc-400">
                      指纹 {fp} · 超时上限 {script.timeoutMs}ms · 权限 READ_TEXT_ONLY
                    </div>
                    <pre className="font-mono-tabular mt-2.5 max-h-36 overflow-auto rounded-lg border border-zinc-200 bg-zinc-50 p-2.5 text-[11px] leading-5 text-zinc-700 dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-300">
                      {script.sourceCode}
                    </pre>
                  </div>

                  <div className="mt-3 flex flex-wrap items-center justify-between gap-2 pt-2">
                    <button
                      type="button"
                      onClick={() => {
                        onUpdateProject((p) => ({
                          ...p,
                          skills: {
                            ...p.skills,
                            scripts: p.skills.scripts.map((s) =>
                              s.id === script.id
                                ? approved
                                  ? revokeSandboxScript(s)
                                  : approveSandboxScript(s)
                                : s,
                            ),
                          },
                        }));
                        onToast(
                          approved
                            ? `已撤销「${script.name}」执行授权`
                            : `作者已批准「${script.name}」进入隔离沙箱`,
                        );
                      }}
                      className="rounded-md border border-zinc-300 px-3 py-1.5 font-medium text-zinc-800 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
                    >
                      {approved ? '撤销批准' : '显式批准此脚本'}
                    </button>

                    <button
                      type="button"
                      disabled={runningScriptId === script.id}
                      onClick={() => handleRunSandboxScript(script.id)}
                      style={approved ? { backgroundColor: 'var(--qj-accent)' } : undefined}
                      className={`flex items-center gap-1 rounded-md px-3.5 py-1.5 font-medium ${
                        approved
                          ? 'text-white'
                          : 'border border-zinc-200 text-zinc-500 hover:bg-zinc-100 dark:border-zinc-700'
                      }`}
                    >
                      <Play className="h-3.5 w-3.5" />
                      {runningScriptId === script.id ? '沙箱执行中…' : '对当前章运行审查'}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          {sandboxError && (
            <div className="mt-4 flex items-center gap-2 rounded-lg border border-rose-300 bg-rose-50 px-3.5 py-2.5 text-xs text-rose-900 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-200">
              <ShieldAlert className="h-4 w-4 shrink-0" />
              <span>{sandboxError}</span>
            </div>
          )}

          {latestReport && (
            <div className="mt-4 rounded-xl border border-zinc-200 bg-zinc-50 p-4 text-xs dark:border-zinc-800 dark:bg-zinc-900/60">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-200 pb-2.5 dark:border-zinc-800">
                <div>
                  <div className="font-semibold text-zinc-900 dark:text-zinc-100">
                    审查报告 · {latestReport.scriptName}
                  </div>
                  <div className="font-mono-tabular text-[11px] text-zinc-500">
                    引擎：{latestReport.engineName} · 耗时 {latestReport.durationMs}ms · 检出{' '}
                    {latestReport.issues.length} 项
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => onSendReportToEditor(latestReport.summaryForAi)}
                  style={{ backgroundColor: 'var(--qj-accent)' }}
                  className="flex items-center gap-1 rounded-md px-3 py-1.5 font-medium text-white whitespace-nowrap shrink-0"
                >
                  <Send className="h-3.5 w-3.5" />
                  交由 AI 辅助修改正文
                </button>
              </div>

              {latestReport.issues.length === 0 ? (
                <div className="mt-3 flex items-center gap-1.5 text-emerald-700 dark:text-emerald-400">
                  <CheckCircle2 className="h-4 w-4" />
                  本章未检出规则违规项。
                </div>
              ) : (
                <div className="mt-3 space-y-2">
                  {latestReport.issues.map((it, idx) => (
                    <div
                      key={idx}
                      className="rounded-lg border border-zinc-200 bg-white px-3 py-2 dark:border-zinc-800 dark:bg-[#16191D]"
                    >
                      <div className="font-medium text-zinc-900 dark:text-zinc-100">
                        [{it.rule}] 「{it.excerpt}」
                      </div>
                      <div className="mt-0.5 text-zinc-500">建议：{it.suggestion}</div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
