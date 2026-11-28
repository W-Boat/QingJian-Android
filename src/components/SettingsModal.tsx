import React, { useState } from 'react';
import { Check, KeyRound, Moon, Plus, ShieldCheck, Sun, Trash2, X } from 'lucide-react';
import type { AiAccountProfile, AiUsageLog, ThinkingLevelType } from '../core/types';

export const MIUIX_ACCENTS = [
  { id: 'qingjian', name: '青简黛绿', hex: '#1F7A6E' },
  { id: 'indigo', name: '砚台靛蓝', hex: '#2F5D8A' },
  { id: 'amber', name: '古铜琥珀', hex: '#9A6324' },
  { id: 'crimson', name: '印泥朱砂', hex: '#9E3A3F' },
  { id: 'slate', name: '墨玉石青', hex: '#4A5568' },
];

interface SettingsModalProps {
  isDark: boolean;
  onToggleDark: (dark: boolean) => void;
  accentHex: string;
  onChangeAccent: (hex: string) => void;
  profiles: AiAccountProfile[];
  activeProfileId: string;
  onSelectProfile: (id: string) => void;
  onUpsertProfile: (profile: AiAccountProfile) => void;
  onDeleteProfile: (id: string) => void;
  usageLogs: AiUsageLog[];
  onClose: () => void;
  onToast: (msg: string) => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  isDark,
  onToggleDark,
  accentHex,
  onChangeAccent,
  profiles,
  activeProfileId,
  onSelectProfile,
  onUpsertProfile,
  onDeleteProfile,
  usageLogs,
  onClose,
  onToast,
}) => {
  const [tab, setTab] = useState<'APPEARANCE' | 'PROFILES' | 'USAGE'>('PROFILES');
  const [newLabel, setNewLabel] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newModel, setNewModel] = useState('gpt-5-codex');
  const [newThinking, setNewThinking] = useState<ThinkingLevelType>('HIGH');

  const activeProfile = profiles.find((p) => p.id === activeProfileId) ?? profiles[0];

  const handleAddCodexProfile = () => {
    const label = newLabel.trim() || `ChatGPT Codex 账号 ${profiles.length + 1}`;
    const created: AiAccountProfile = {
      id: `prof_codex_${Date.now()}`,
      label,
      provider: 'CHATGPT_CODEX_OAUTH',
      model: newModel.trim() || 'gpt-5-codex',
      thinkingLevel: newThinking,
      endpoint: 'https://chatgpt.com/backend-api/codex/responses',
      oauthEmail: newEmail.trim() || undefined,
    };
    onUpsertProfile(created);
    onSelectProfile(created.id);
    setNewLabel('');
    setNewEmail('');
    onToast(`已新增 OAuth Profile「${label}」并设为当前账号`);
  };

  const totalTokens = usageLogs.reduce((s, u) => s + u.promptTokens + u.completionTokens, 0);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4">
      <div className="flex max-h-[88vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-2xl dark:border-zinc-800 dark:bg-[#16191D]">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-zinc-200 px-5 py-4 dark:border-zinc-800">
          <div>
            <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
              宿主引擎与 Miuix 外观设置
            </h2>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              裁剪版 Eta 免 Root 基座 · 多账号 Profile 切换 · 思考等级调节 · 用量审计
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Sub-nav */}
        <div className="flex gap-1 border-b border-zinc-200 px-5 pt-2 dark:border-zinc-800">
          {[
            { id: 'PROFILES', label: 'AI 账号与思考等级' },
            { id: 'APPEARANCE', label: 'Miuix 深色与主题色' },
            { id: 'USAGE', label: `AI 用量统计 (${usageLogs.length})` },
          ].map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setTab(item.id as typeof tab)}
              className={`border-b-2 px-3 py-2 text-xs font-medium whitespace-nowrap shrink-0 transition ${
                tab === item.id
                  ? 'border-zinc-900 text-zinc-900 dark:border-zinc-100 dark:text-zinc-100'
                  : 'border-transparent text-zinc-500 hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-200'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-5">
          {tab === 'APPEARANCE' && (
            <div className="space-y-6">
              <div>
                <h3 className="text-xs font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">
                  界面明暗主题
                </h3>
                <div className="mt-2.5 grid grid-cols-2 gap-3">
                  <button
                    type="button"
                    onClick={() => onToggleDark(false)}
                    className={`flex items-center justify-between rounded-lg border p-3.5 text-left text-sm transition ${
                      !isDark
                        ? 'border-zinc-900 bg-zinc-50 dark:border-zinc-100'
                        : 'border-zinc-200 dark:border-zinc-800'
                    }`}
                  >
                    <span className="flex items-center gap-2 font-medium text-zinc-900 dark:text-zinc-100">
                      <Sun className="h-4 w-4" />
                      纸墨浅色模式
                    </span>
                    {!isDark && <Check className="h-4 w-4 text-zinc-900" />}
                  </button>
                  <button
                    type="button"
                    onClick={() => onToggleDark(true)}
                    className={`flex items-center justify-between rounded-lg border p-3.5 text-left text-sm transition ${
                      isDark
                        ? 'border-zinc-200 bg-zinc-800/80'
                        : 'border-zinc-200 dark:border-zinc-800'
                    }`}
                  >
                    <span className="flex items-center gap-2 font-medium text-zinc-900 dark:text-zinc-100">
                      <Moon className="h-4 w-4" />
                      深夜执笔深色模式
                    </span>
                    {isDark && <Check className="h-4 w-4 text-zinc-100" />}
                  </button>
                </div>
              </div>

              <div>
                <h3 className="text-xs font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">
                  Miuix 自定义主题色
                </h3>
                <div className="mt-2.5 grid grid-cols-2 gap-2.5 sm:grid-cols-5">
                  {MIUIX_ACCENTS.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => onChangeAccent(item.hex)}
                      className={`flex items-center gap-2 rounded-lg border px-3 py-2.5 text-xs font-medium transition ${
                        accentHex === item.hex
                          ? 'border-zinc-900 bg-zinc-50 dark:border-zinc-100 dark:bg-zinc-800'
                          : 'border-zinc-200 dark:border-zinc-800'
                      }`}
                    >
                      <span
                        className="h-3.5 w-3.5 shrink-0 rounded-full"
                        style={{ backgroundColor: item.hex }}
                      />
                      <span className="text-zinc-800 dark:text-zinc-200">{item.name}</span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {tab === 'PROFILES' && (
            <div className="space-y-5">
              <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3.5 text-xs text-zinc-600 dark:border-zinc-800 dark:bg-zinc-900/60 dark:text-zinc-400">
                <div className="flex items-center gap-1.5 font-medium text-zinc-800 dark:text-zinc-200">
                  <ShieldCheck className="h-4 w-4" style={{ color: 'var(--qj-accent)' }} />
                  安全说明：服务端默认代理 Gemini API，不要求在前端输入或明文保存任何密钥；原生 Android 端支持官方 ChatGPT Codex 套餐浏览器 OAuth PKCE 授权。
                </div>
              </div>

              {/* Active profile list */}
              <div className="space-y-2.5">
                {profiles.map((p) => {
                  const selected = p.id === activeProfile?.id;
                  return (
                    <div
                      key={p.id}
                      className={`rounded-lg border p-3.5 transition ${
                        selected
                          ? 'border-zinc-900 bg-zinc-50/70 dark:border-zinc-200 dark:bg-zinc-800/40'
                          : 'border-zinc-200 dark:border-zinc-800'
                      }`}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <button
                          type="button"
                          onClick={() => onSelectProfile(p.id)}
                          className="flex items-center gap-2 text-left"
                        >
                          <span
                            className="flex h-4 w-4 items-center justify-center rounded-full border"
                            style={
                              selected
                                ? { backgroundColor: 'var(--qj-accent)', borderColor: 'var(--qj-accent)' }
                                : undefined
                            }
                          >
                            {selected && <Check className="h-3 w-3 text-white" />}
                          </span>
                          <div>
                            <div className="text-sm font-medium text-zinc-900 dark:text-zinc-100">{p.label}</div>
                            <div className="text-[11px] text-zinc-500 dark:text-zinc-400">
                              {p.provider} · 模型 {p.model}
                              {p.oauthEmail ? ` · 账号 ${p.oauthEmail}` : ''}
                            </div>
                          </div>
                        </button>

                        <div className="flex items-center gap-2">
                          <select
                            aria-label="思考等级"
                            value={p.thinkingLevel}
                            onChange={(e) =>
                              onUpsertProfile({
                                ...p,
                                thinkingLevel: e.target.value as ThinkingLevelType,
                              })
                            }
                            className="rounded-md border border-zinc-200 bg-white px-2 py-1 text-xs text-zinc-700 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200"
                          >
                            <option value="LOW">思考等级：低 (LOW)</option>
                            <option value="MEDIUM">思考等级：中 (MEDIUM)</option>
                            <option value="HIGH">思考等级：高 (HIGH)</option>
                            <option value="XHIGH">思考等级：极深 (XHIGH)</option>
                          </select>

                          {profiles.length > 1 && p.id !== 'prof_gemini' && (
                            <button
                              type="button"
                              onClick={() => onDeleteProfile(p.id)}
                              className="p-1 text-zinc-400 hover:text-rose-500"
                              title="移除此 Profile"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </button>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>

              {/* Add Codex OAuth Profile */}
              <div className="rounded-lg border border-zinc-200 p-4 dark:border-zinc-800">
                <div className="mb-3 flex items-center gap-1.5 text-xs font-semibold text-zinc-800 dark:text-zinc-200">
                  <KeyRound className="h-3.5 w-3.5" />
                  新增 ChatGPT (Codex) OAuth 多账号 Profile
                </div>
                <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
                  <input
                    value={newLabel}
                    onChange={(e) => setNewLabel(e.target.value)}
                    placeholder="Profile 备注（如：备用写作号）"
                    className="rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 text-xs dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                  <input
                    value={newEmail}
                    onChange={(e) => setNewEmail(e.target.value)}
                    placeholder="OAuth 绑定邮箱"
                    className="rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 text-xs dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                  <div className="flex gap-1.5">
                    <input
                      value={newModel}
                      onChange={(e) => setNewModel(e.target.value)}
                      placeholder="模型 ID"
                      className="w-full rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-1.5 text-xs dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                    />
                    <button
                      type="button"
                      onClick={handleAddCodexProfile}
                      style={{ backgroundColor: 'var(--qj-accent)' }}
                      className="flex items-center gap-1 rounded-md px-3 py-1.5 text-xs font-medium text-white whitespace-nowrap shrink-0"
                    >
                      <Plus className="h-3.5 w-3.5" />
                      添加
                    </button>
                  </div>
                </div>
                <div className="mt-2 flex items-center gap-3 text-[11px] text-zinc-500">
                  <span>默认思考等级：</span>
                  {(['LOW', 'MEDIUM', 'HIGH', 'XHIGH'] as ThinkingLevelType[]).map((lv) => (
                    <button
                      key={lv}
                      type="button"
                      onClick={() => setNewThinking(lv)}
                      className={`rounded px-1.5 py-0.5 ${
                        newThinking === lv
                          ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                          : 'hover:bg-zinc-100 dark:hover:bg-zinc-800'
                      }`}
                    >
                      {lv}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {tab === 'USAGE' && (
            <div className="space-y-4">
              <div className="grid grid-cols-3 gap-3 rounded-lg border border-zinc-200 bg-zinc-50 p-3.5 dark:border-zinc-800 dark:bg-zinc-900/60">
                <div>
                  <div className="text-[11px] text-zinc-500">累计请求次数</div>
                  <div className="font-mono-tabular mt-0.5 text-lg font-semibold text-zinc-900 dark:text-zinc-100">
                    {usageLogs.length}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] text-zinc-500">累计消耗 Token</div>
                  <div className="font-mono-tabular mt-0.5 text-lg font-semibold text-zinc-900 dark:text-zinc-100">
                    {totalTokens.toLocaleString()}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] text-zinc-500">当前激活 Profile</div>
                  <div className="mt-1 truncate text-xs font-medium text-zinc-800 dark:text-zinc-200">
                    {activeProfile?.label}
                  </div>
                </div>
              </div>

              {usageLogs.length === 0 ? (
                <p className="py-8 text-center text-xs text-zinc-500">
                  暂无调用记录。在编辑器触发局部改稿、定稿交接或整章流水线后将在此统计。
                </p>
              ) : (
                <div className="overflow-hidden rounded-lg border border-zinc-200 dark:border-zinc-800">
                  <table className="w-full text-left text-xs">
                    <thead className="border-b border-zinc-200 bg-zinc-50 text-[11px] text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900">
                      <tr>
                        <th className="px-3 py-2 font-medium">任务</th>
                        <th className="px-3 py-2 font-medium">模型 / 思考等级</th>
                        <th className="px-3 py-2 text-right font-medium">输入 / 输出 Token</th>
                        <th className="px-3 py-2 text-right font-medium">耗时</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
                      {usageLogs.slice(0, 30).map((log) => (
                        <tr key={log.id} className="text-zinc-700 dark:text-zinc-300">
                          <td className="px-3 py-2">{log.taskLabel}</td>
                          <td className="px-3 py-2 text-[11px] text-zinc-500">
                            {log.modelName} · {log.thinkingLevel}
                          </td>
                          <td className="font-mono-tabular px-3 py-2 text-right">
                            {log.promptTokens} / {log.completionTokens}
                          </td>
                          <td className="font-mono-tabular px-3 py-2 text-right text-zinc-500">
                            {log.latencyMs}ms
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
