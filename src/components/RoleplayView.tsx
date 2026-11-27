import React, { useMemo, useRef, useState } from 'react';
import {
  ArrowRightCircle,
  Download,
  ImagePlus,
  Layers,
  Send,
  Sparkles,
  Upload,
  UserPlus,
} from 'lucide-react';
import { buildLocalRpReplyFallback, requestAiCompletion } from '../core/aiClient';
import {
  encodeTavernCardToPngBytes,
  evaluateWorldbook,
  parseTavernCardFromPngBytes,
  parseTavernCardJson,
} from '../core/worldbookEngine';
import type {
  AiAccountProfile,
  AiUsageLog,
  NovelProject,
  RpTurnMessage,
  TavernCharacterCard,
} from '../core/types';

interface RoleplayViewProps {
  project: NovelProject;
  activeProfile: AiAccountProfile;
  onUpdateProject: (updater: (prev: NovelProject) => NovelProject) => void;
  onAppendDraftToChapter: (text: string) => void;
  onLogUsage: (usage: AiUsageLog) => void;
  onToast: (msg: string) => void;
}

export const RoleplayView: React.FC<RoleplayViewProps> = ({
  project,
  activeProfile,
  onUpdateProject,
  onAppendDraftToChapter,
  onLogUsage,
  onToast,
}) => {
  const [selectedCardId, setSelectedCardId] = useState(project.rpCards[0]?.id ?? '');
  const selectedCard: TavernCharacterCard | undefined =
    project.rpCards.find((c) => c.id === selectedCardId) ?? project.rpCards[0];

  const [messagesByCard, setMessagesByCard] = useState<Record<string, RpTurnMessage[]>>(() => {
    const initial: Record<string, RpTurnMessage[]> = {};
    for (const card of project.rpCards) {
      initial[card.id] = [
        {
          id: `rp_init_${card.id}`,
          isAuthor: false,
          speaker: card.name,
          content: card.firstMessage || `（${card.name}静候开场）`,
          triggeredWorldbookTitles: [],
        },
      ];
    }
    return initial;
  });

  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [showJsonImport, setShowJsonImport] = useState(false);
  const [jsonInput, setJsonInput] = useState('');
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const activeMessages = useMemo(() => {
    if (!selectedCard) return [];
    return (
      messagesByCard[selectedCard.id] ?? [
        {
          id: `rp_init_${selectedCard.id}`,
          isAuthor: false,
          speaker: selectedCard.name,
          content: selectedCard.firstMessage || `（${selectedCard.name}进入排练场景）`,
          triggeredWorldbookTitles: [],
        },
      ]
    );
  }, [selectedCard, messagesByCard]);

  // Live shared Worldbook hit preview for current conversation + draft input
  const sharedWorldbookEval = useMemo(() => {
    const recentCorpus = [
      selectedCard?.scenario ?? '',
      ...activeMessages.slice(-4).map((m) => m.content),
      input,
    ].join('\n');
    return evaluateWorldbook(
      project.workspace.worldbookEntries,
      recentCorpus,
      project.contextConfig.worldbookCharBudget,
      3,
    );
  }, [project.workspace.worldbookEntries, project.contextConfig.worldbookCharBudget, selectedCard, activeMessages, input]);

  const handleSendTurn = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedCard || !input.trim() || sending) return;
    const userText = input.trim();
    setInput('');
    setSending(true);

    const hitTitles = sharedWorldbookEval.matchedTraces
      .filter((t) => t.includedInBudget)
      .map((t) => t.entry.title);

    const userMsg: RpTurnMessage = {
      id: `rp_u_${Date.now()}`,
      isAuthor: true,
      speaker: '作者试戏',
      content: userText,
      triggeredWorldbookTitles: hitTitles,
    };

    const nextHistory = [...activeMessages, userMsg];
    setMessagesByCard((prev) => ({ ...prev, [selectedCard.id]: nextHistory }));

    try {
      const res = await requestAiCompletion({
        profile: activeProfile,
        systemPrompt: [
          `你正在扮演长篇小说《${project.title}》角色「${selectedCard.name}」进行剧情对戏排练。`,
          `角色设定：${selectedCard.description}`,
          `性格：${selectedCard.personality}`,
          `场景：${selectedCard.scenario}`,
          sharedWorldbookEval.injectedText,
          '要求：保持角色知情边界，动作与对白克制有质感，严禁使用 emoji。',
        ]
          .filter(Boolean)
          .join('\n\n'),
        userPrompt: nextHistory.map((m) => `${m.speaker}：${m.content}`).join('\n\n'),
        taskLabel: `角色对戏排练 (${selectedCard.name})`,
        localFallbackGenerator: () => buildLocalRpReplyFallback(selectedCard, userText, hitTitles),
      });
      onLogUsage(res.usage);

      const aiMsg: RpTurnMessage = {
        id: `rp_a_${Date.now()}`,
        isAuthor: false,
        speaker: selectedCard.name,
        content: res.text,
        triggeredWorldbookTitles: hitTitles,
      };
      setMessagesByCard((prev) => ({
        ...prev,
        [selectedCard.id]: [...nextHistory, aiMsg],
      }));
    } catch (err) {
      onToast(err instanceof Error ? err.message : '对戏回复失败');
    } finally {
      setSending(false);
    }
  };

  const handleProjectLoreCharacters = () => {
    const charLore = project.workspace.loreEntries.filter((l) => l.category === 'CHARACTER');
    if (charLore.length === 0) {
      onToast('事实档案中暂无人物条目可投影');
      return;
    }
    let added = 0;
    onUpdateProject((proj) => {
      const existingNames = new Set(proj.rpCards.map((c) => c.name));
      const newCards: TavernCharacterCard[] = [];
      for (const lore of charLore) {
        if (existingNames.has(lore.title)) continue;
        added++;
        newCards.push({
          id: `card_proj_${lore.id}`,
          specVersion: 'chara_card_v2',
          name: lore.title,
          description: lore.content,
          personality: '严格对齐正文章节已定稿人设与语气',
          scenario: `《${proj.title}》主线排练场`,
          firstMessage: `（${lore.title}按《${proj.title}》已定稿档案入场，等待对戏台词）`,
          tags: [proj.title, '小说角色投影'],
          linkedLoreId: lore.id,
        });
      }
      return { ...proj, rpCards: [...newCards, ...proj.rpCards] };
    });
    onToast(added > 0 ? `已将 ${added} 位小说已定稿人物一键投影为角色卡` : '所有小说人物均已投影为角色卡');
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      let card: TavernCharacterCard;
      if (file.name.toLowerCase().endsWith('.png')) {
        const buf = new Uint8Array(await file.arrayBuffer());
        card = parseTavernCardFromPngBytes(buf);
      } else {
        const text = await file.text();
        card = parseTavernCardJson(text);
      }
      onUpdateProject((p) => ({ ...p, rpCards: [card, ...p.rpCards] }));
      setSelectedCardId(card.id);
      onToast(`已导入酒馆角色卡「${card.name}」(${card.specVersion})`);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '角色卡解析失败');
    } finally {
      e.target.value = '';
    }
  };

  const handleExportCurrentCardPng = () => {
    if (!selectedCard) return;
    const pngBytes = encodeTavernCardToPngBytes(selectedCard);
    const copy = new Uint8Array(pngBytes.length);
    copy.set(pngBytes);
    const blob = new Blob([copy.buffer], { type: 'image/png' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${selectedCard.name}_tavern_v2.png`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    onToast(`已导出「${selectedCard.name}」为带 chara 元数据的酒馆 PNG 卡`);
  };

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden bg-[#F7F7F5] lg:flex-row dark:bg-[#0E1012]">
      {/* Left Character Cards & Projection Rail */}
      <aside className="flex w-full shrink-0 flex-col border-b border-zinc-200 bg-white lg:w-72 lg:border-r lg:border-b-0 dark:border-zinc-800 dark:bg-[#13161A]">
        <div className="border-b border-zinc-200 p-4 dark:border-zinc-800">
          <h1 className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
            角色对戏排练 · 共享世界书
          </h1>
          <p className="mt-0.5 text-[11px] text-zinc-500">
            支持酒馆 V1/V2/V3 JSON &amp; PNG 角色卡与小说人物一键投影
          </p>

          <div className="mt-3 flex flex-wrap gap-1.5">
            <button
              type="button"
              onClick={handleProjectLoreCharacters}
              style={{ backgroundColor: 'var(--qj-accent)' }}
              className="flex items-center gap-1 rounded-md px-2.5 py-1.5 text-[11px] font-medium text-white whitespace-nowrap shrink-0"
            >
              <UserPlus className="h-3.5 w-3.5" />
              投影小说人物
            </button>
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="flex items-center gap-1 rounded-md border border-zinc-200 px-2.5 py-1.5 text-[11px] font-medium text-zinc-700 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300"
            >
              <ImagePlus className="h-3.5 w-3.5" />
              导入 PNG/JSON
            </button>
            <button
              type="button"
              onClick={() => setShowJsonImport((v) => !v)}
              className="flex items-center gap-1 rounded-md border border-zinc-200 px-2 py-1.5 text-[11px] text-zinc-600 dark:border-zinc-700 dark:text-zinc-300"
            >
              <Upload className="h-3.5 w-3.5" />
              粘贴 JSON
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".png,.json"
              onChange={handleFileUpload}
              className="hidden"
            />
          </div>

          {showJsonImport && (
            <div className="mt-3 space-y-2 text-xs">
              <textarea
                rows={3}
                value={jsonInput}
                onChange={(e) => setJsonInput(e.target.value)}
                placeholder='{"spec":"chara_card_v2","data":{"name":"谢晚晴","description":"城西药铺掌柜","first_mes":"..."}}'
                className="font-mono-tabular w-full rounded border border-zinc-200 bg-zinc-50 p-2 text-[11px] dark:border-zinc-700 dark:bg-zinc-900"
              />
              <button
                type="button"
                onClick={() => {
                  try {
                    const card = parseTavernCardJson(jsonInput);
                    onUpdateProject((p) => ({ ...p, rpCards: [card, ...p.rpCards] }));
                    setSelectedCardId(card.id);
                    setJsonInput('');
                    setShowJsonImport(false);
                    onToast(`已导入角色卡「${card.name}」`);
                  } catch (err) {
                    onToast(err instanceof Error ? err.message : 'JSON 格式有误');
                  }
                }}
                style={{ backgroundColor: 'var(--qj-accent)' }}
                className="w-full rounded py-1 text-xs font-medium text-white"
              >
                导入角色卡
              </button>
            </div>
          )}
        </div>

        <div className="flex gap-2 overflow-x-auto p-3 lg:flex-1 lg:flex-col lg:overflow-y-auto">
          {project.rpCards.map((card) => {
            const active = card.id === selectedCard?.id;
            return (
              <button
                key={card.id}
                type="button"
                onClick={() => setSelectedCardId(card.id)}
                className={`min-w-[200px] rounded-lg border p-3 text-left transition lg:min-w-0 ${
                  active
                    ? 'border-zinc-900 bg-zinc-100/80 dark:border-zinc-200 dark:bg-zinc-800/70'
                    : 'border-zinc-200 hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-800/40'
                }`}
              >
                <div className="flex items-center justify-between text-xs font-semibold text-zinc-900 dark:text-zinc-100">
                  <span>{card.name}</span>
                  <span className="font-mono-tabular text-[10px] font-normal text-zinc-400">
                    {card.specVersion}
                  </span>
                </div>
                <p className="mt-1 line-clamp-2 text-[11px] text-zinc-500">{card.description}</p>
              </button>
            );
          })}
        </div>
      </aside>

      {/* Right Rehearsal Chat Stage */}
      <div className="flex min-w-0 flex-1 flex-col">
        {selectedCard ? (
          <>
            <header className="flex flex-wrap items-center justify-between gap-2 border-b border-zinc-200 bg-white px-5 py-3 dark:border-zinc-800 dark:bg-[#13161A]">
              <div>
                <div className="text-sm font-semibold text-zinc-900 dark:text-zinc-100">
                  与「{selectedCard.name}」对戏试写
                </div>
                <div className="text-[11px] text-zinc-500">
                  场景：{selectedCard.scenario} · 实时共享《{project.title}》世界书（当前命中{' '}
                  {sharedWorldbookEval.matchedTraces.filter((t) => t.includedInBudget).length} 条）
                </div>
              </div>
              <button
                type="button"
                onClick={handleExportCurrentCardPng}
                className="flex items-center gap-1 rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs text-zinc-700 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300"
              >
                <Download className="h-3.5 w-3.5" />
                导出酒馆 PNG 卡
              </button>
            </header>

            {/* Shared Worldbook Live Hit Strip */}
            <div className="flex flex-wrap items-center gap-2 border-b border-zinc-200 bg-zinc-50 px-5 py-2 text-[11px] text-zinc-600 dark:border-zinc-800 dark:bg-zinc-900/60 dark:text-zinc-400">
              <span className="flex items-center gap-1 font-medium text-zinc-700 dark:text-zinc-300">
                <Layers className="h-3 w-3" />
                本轮共享世界书命中：
              </span>
              {sharedWorldbookEval.matchedTraces
                .filter((t) => t.includedInBudget)
                .map((t) => (
                  <span key={t.entry.id}>
                    {t.entry.title} ({t.triggerReason})
                  </span>
                ))}
            </div>

            {/* Messages */}
            <div className="flex-1 space-y-4 overflow-y-auto px-5 py-5">
              <div className="mx-auto max-w-3xl space-y-4">
                {activeMessages.map((msg) => (
                  <div
                    key={msg.id}
                    className={`rounded-xl border p-4 ${
                      msg.isAuthor
                        ? 'ml-8 border-zinc-300 bg-white dark:border-zinc-700 dark:bg-[#1B2026]'
                        : 'mr-8 border-zinc-200 bg-white dark:border-zinc-800 dark:bg-[#16191D]'
                    }`}
                  >
                    <div className="mb-1.5 flex items-center justify-between text-[11px] text-zinc-500">
                      <span className="font-semibold text-zinc-800 dark:text-zinc-200">
                        {msg.speaker}
                      </span>
                      {!msg.isAuthor && (
                        <button
                          type="button"
                          onClick={() => onAppendDraftToChapter(msg.content)}
                          style={{ color: 'var(--qj-accent)' }}
                          className="flex items-center gap-1 font-medium hover:underline"
                        >
                          <ArrowRightCircle className="h-3.5 w-3.5" />
                          写入当前章草稿
                        </button>
                      )}
                    </div>
                    <p className="font-prose whitespace-pre-wrap text-[15px] leading-7 text-zinc-800 dark:text-zinc-200">
                      {msg.content}
                    </p>
                  </div>
                ))}
              </div>
            </div>

            {/* Composer */}
            <form
              onSubmit={handleSendTurn}
              className="border-t border-zinc-200 bg-white px-5 py-3.5 dark:border-zinc-800 dark:bg-[#13161A]"
            >
              <div className="mx-auto flex max-w-3xl gap-2">
                <input
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  placeholder={`输入试戏动作或台词，触发《${project.title}》世界书与「${selectedCard.name}」对戏…`}
                  className="flex-1 rounded-lg border border-zinc-200 bg-zinc-50 px-3.5 py-2 text-sm text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
                <button
                  type="submit"
                  disabled={sending || !input.trim()}
                  style={{ backgroundColor: 'var(--qj-accent)' }}
                  className="flex items-center gap-1.5 rounded-lg px-4 py-2 text-xs font-medium text-white whitespace-nowrap shrink-0 disabled:opacity-50"
                >
                  {sending ? <Sparkles className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
                  {sending ? '演绎中…' : '发送试戏'}
                </button>
              </div>
            </form>
          </>
        ) : (
          <div className="flex flex-1 items-center justify-center text-xs text-zinc-500">
            请先投影小说人物或导入酒馆角色卡。
          </div>
        )}
      </div>
    </div>
  );
};
