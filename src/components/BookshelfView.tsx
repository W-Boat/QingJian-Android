import React, { useState } from 'react';
import {
  BookOpen,
  Download,
  GitBranch,
  LayoutGrid,
  List,
  Plus,
  Settings2,
  SlidersHorizontal,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { buildExportBlob } from '../core/gitAndPipeline';
import { countChars, type ExportFormatType, type NovelProject } from '../core/types';

interface BookshelfViewProps {
  books: NovelProject[];
  activeBookId: string;
  onSelectBook: (bookId: string, openEditor?: boolean) => void;
  onCreateBook: (params: {
    title: string;
    author: string;
    genre: string;
    synopsis: string;
    coverBg: string;
    coverAccent: string;
    wordCountGoalPerChapter: number;
  }) => void;
  onUpdateBookSettings: (
    bookId: string,
    patch: Partial<
      Pick<
        NovelProject,
        'title' | 'author' | 'genre' | 'synopsis' | 'coverBg' | 'coverAccent' | 'wordCountGoalPerChapter' | 'editorWidthPx'
      >
    >,
  ) => void;
  onDeleteBook: (bookId: string) => void;
  onToast: (msg: string) => void;
}

const COVER_PALETTES = [
  { name: '青简黛墨', bg: '#1A3A3A', accent: '#2B6E63' },
  { name: '玄铁深蓝', bg: '#1B2838', accent: '#3B6EA5' },
  { name: '松烟暮褐', bg: '#2E2620', accent: '#8C6239' },
  { name: '朱砂绛褐', bg: '#3B1E22', accent: '#A13D44' },
  { name: '砚台岩灰', bg: '#222629', accent: '#5A6772' },
];

export const BookshelfView: React.FC<BookshelfViewProps> = ({
  books,
  activeBookId,
  onSelectBook,
  onCreateBook,
  onUpdateBookSettings,
  onDeleteBook,
  onToast,
}) => {
  const [layoutMode, setLayoutMode] = useState<'GRID' | 'LIST'>('GRID');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [editingBook, setEditingBook] = useState<NovelProject | null>(null);

  const [newTitle, setNewTitle] = useState('');
  const [newAuthor, setNewAuthor] = useState('林墨');
  const [newGenre, setNewGenre] = useState('悬疑长篇');
  const [newSynopsis, setNewSynopsis] = useState('');
  const [newPaletteIdx, setNewPaletteIdx] = useState(0);
  const [newWordGoal, setNewWordGoal] = useState(3000);

  const handleExport = (book: NovelProject, format: ExportFormatType) => {
    const { blob, filename } = buildExportBlob(book, format);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    onToast(`已导出《${book.title}》为 ${format} (${filename})`);
  };

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    const pal = COVER_PALETTES[newPaletteIdx] ?? COVER_PALETTES[0];
    onCreateBook({
      title: newTitle.trim(),
      author: newAuthor.trim() || '佚名',
      genre: newGenre.trim() || '长篇小说',
      synopsis: newSynopsis.trim() || '新建本地 Git 书稿仓库。',
      coverBg: pal.bg,
      coverAccent: pal.accent,
      wordCountGoalPerChapter: Math.max(500, newWordGoal),
    });
    setNewTitle('');
    setNewSynopsis('');
    setShowCreateModal(false);
  };

  return (
    <div className="h-full overflow-y-auto px-5 py-6 md:px-10 md:py-8">
      <div className="mx-auto max-w-6xl space-y-6">
        {/* Top bar */}
        <div className="flex flex-wrap items-end justify-between gap-4 border-b border-zinc-200 pb-5 dark:border-zinc-800">
          <div>
            <p className="text-xs font-medium tracking-wider text-zinc-500 uppercase dark:text-zinc-400">
              本地 Git-First 书库 · 单一事实来源
            </p>
            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-100">
              青简书架
            </h1>
            <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
              共 {books.length} 部作品 · 每部作品独立维护 Git 分支、交接稿、世界书与还原点
            </p>
          </div>

          <div className="flex items-center gap-2">
            <div className="flex items-center rounded-lg border border-zinc-200 bg-white p-0.5 dark:border-zinc-800 dark:bg-zinc-900">
              <button
                type="button"
                onClick={() => setLayoutMode('GRID')}
                className={`flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium whitespace-nowrap shrink-0 transition ${
                  layoutMode === 'GRID'
                    ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                    : 'text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100'
                }`}
              >
                <LayoutGrid className="h-3.5 w-3.5" />
                2:3 封面卡片
              </button>
              <button
                type="button"
                onClick={() => setLayoutMode('LIST')}
                className={`flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium whitespace-nowrap shrink-0 transition ${
                  layoutMode === 'LIST'
                    ? 'bg-zinc-900 text-white dark:bg-zinc-100 dark:text-zinc-900'
                    : 'text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100'
                }`}
              >
                <List className="h-3.5 w-3.5" />
                横向列表
              </button>
            </div>

            <button
              type="button"
              onClick={() => setShowCreateModal(true)}
              style={{ backgroundColor: 'var(--qj-accent)' }}
              className="flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-medium text-white whitespace-nowrap shrink-0 transition hover:opacity-90"
            >
              <Plus className="h-4 w-4" />
              新建书稿
            </button>
          </div>
        </div>

        {/* Book list */}
        {layoutMode === 'GRID' ? (
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {books.map((book) => {
              const totalWords = book.workspace.chapters.reduce((s, c) => s + countChars(c.markdownContent), 0);
              const finalizedCount = book.workspace.chapters.filter((c) => c.status === 'FINALIZED').length;
              const isActive = book.id === activeBookId;
              return (
                <div
                  key={book.id}
                  className={`group flex flex-col overflow-hidden rounded-xl border bg-white transition dark:bg-[#16191D] ${
                    isActive
                      ? 'border-zinc-900 ring-1 ring-zinc-900/15 dark:border-zinc-200 dark:ring-zinc-200/15'
                      : 'border-zinc-200 hover:border-zinc-300 dark:border-zinc-800 dark:hover:border-zinc-700'
                  }`}
                >
                  {/* 2:3 editorial cover */}
                  <button
                    type="button"
                    onClick={() => onSelectBook(book.id, true)}
                    className="relative flex aspect-[3/2] w-full flex-col justify-between p-5 text-left transition"
                    style={{
                      background: `linear-gradient(145deg, ${book.coverBg} 0%, ${book.coverAccent} 100%)`,
                    }}
                  >
                    <div className="flex items-center justify-between text-[11px] font-medium text-white/75">
                      <span>{book.genre}</span>
                      <span className="font-mono-tabular flex items-center gap-1">
                        <GitBranch className="h-3 w-3" />
                        {book.currentBranch}
                      </span>
                    </div>

                    <div className="my-auto py-3">
                      <h2 className="font-prose text-2xl font-semibold tracking-wide text-white">
                        {book.title}
                      </h2>
                      <p className="mt-1.5 text-xs text-white/80">作者 · {book.author}</p>
                    </div>

                    <div className="flex items-center justify-between border-t border-white/15 pt-2.5 text-[11px] text-white/75 tabular-nums">
                      <span>
                        {book.workspace.chapters.length} 章 · 已定稿 {finalizedCount}
                      </span>
                      <span>{totalWords.toLocaleString()} 字</span>
                    </div>
                  </button>

                  {/* Metadata & actions */}
                  <div className="flex flex-1 flex-col justify-between p-4">
                    <p className="line-clamp-2 text-xs leading-relaxed text-zinc-600 dark:text-zinc-400">
                      {book.synopsis}
                    </p>

                    <div className="mt-4 space-y-3 border-t border-zinc-100 pt-3 dark:border-zinc-800/80">
                      <div className="flex items-center justify-between text-[11px] text-zinc-500 tabular-nums dark:text-zinc-400">
                        <span>单章目标 {book.wordCountGoalPerChapter} 字</span>
                        <span>
                          {book.branches.length} 分支 / {book.commits.length} 提交
                        </span>
                      </div>

                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <button
                          type="button"
                          onClick={() => onSelectBook(book.id, true)}
                          style={{ backgroundColor: 'var(--qj-accent)' }}
                          className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium text-white whitespace-nowrap shrink-0 hover:opacity-90"
                        >
                          <BookOpen className="h-3.5 w-3.5" />
                          进入执笔
                        </button>

                        <div className="flex items-center gap-1">
                          <button
                            type="button"
                            onClick={() => setEditingBook(book)}
                            title="单书设置与封面管理"
                            className="rounded-md border border-zinc-200 p-1.5 text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                          >
                            <SlidersHorizontal className="h-3.5 w-3.5" />
                          </button>
                          <button
                            type="button"
                            onClick={() => handleExport(book, 'EPUB')}
                            title="导出 EPUB"
                            className="rounded-md border border-zinc-200 px-2 py-1 text-[11px] font-medium text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                          >
                            EPUB
                          </button>
                          <button
                            type="button"
                            onClick={() => handleExport(book, 'MARKDOWN')}
                            title="导出 Markdown"
                            className="rounded-md border border-zinc-200 px-2 py-1 text-[11px] font-medium text-zinc-600 hover:bg-zinc-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                          >
                            MD
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="divide-y divide-zinc-200 rounded-xl border border-zinc-200 bg-white dark:divide-zinc-800 dark:border-zinc-800 dark:bg-[#16191D]">
            {books.map((book) => {
              const totalWords = book.workspace.chapters.reduce((s, c) => s + countChars(c.markdownContent), 0);
              const finalizedCount = book.workspace.chapters.filter((c) => c.status === 'FINALIZED').length;
              return (
                <div
                  key={book.id}
                  className="flex flex-col gap-4 p-4 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="flex items-start gap-4">
                    <button
                      type="button"
                      onClick={() => onSelectBook(book.id, true)}
                      className="flex h-20 w-14 shrink-0 flex-col justify-between rounded-md p-2 text-left text-white"
                      style={{
                        background: `linear-gradient(145deg, ${book.coverBg} 0%, ${book.coverAccent} 100%)`,
                      }}
                    >
                      <span className="text-[9px] opacity-75">{book.genre.slice(0, 4)}</span>
                      <span className="font-prose text-xs leading-tight font-semibold line-clamp-2">
                        {book.title}
                      </span>
                    </button>
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          onClick={() => onSelectBook(book.id, true)}
                          className="text-base font-semibold text-zinc-900 hover:underline dark:text-zinc-100"
                        >
                          {book.title}
                        </button>
                        <span className="text-xs text-zinc-500 dark:text-zinc-400">
                          {book.author} · {book.genre} · 分支 {book.currentBranch}
                        </span>
                      </div>
                      <p className="mt-1 line-clamp-1 text-xs text-zinc-600 dark:text-zinc-400">
                        {book.synopsis}
                      </p>
                      <p className="mt-1.5 text-[11px] text-zinc-500 tabular-nums dark:text-zinc-400">
                        共 {totalWords.toLocaleString()} 字 · {book.workspace.chapters.length} 章（定稿 {finalizedCount}）·
                        目标 {book.wordCountGoalPerChapter} 字/章 · 编辑器宽度 {book.editorWidthPx}px
                      </p>
                    </div>
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5">
                    <button
                      type="button"
                      onClick={() => onSelectBook(book.id, true)}
                      style={{ backgroundColor: 'var(--qj-accent)' }}
                      className="rounded-md px-3 py-1.5 text-xs font-medium text-white whitespace-nowrap shrink-0"
                    >
                      执笔
                    </button>
                    <button
                      type="button"
                      onClick={() => setEditingBook(book)}
                      className="rounded-md border border-zinc-200 px-2.5 py-1.5 text-xs text-zinc-700 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                    >
                      <Settings2 className="mr-1 inline h-3.5 w-3.5" />
                      书籍设置
                    </button>
                    {(['JSON', 'MARKDOWN', 'TXT', 'EPUB'] as ExportFormatType[]).map((fmt) => (
                      <button
                        key={fmt}
                        type="button"
                        onClick={() => handleExport(book, fmt)}
                        className="rounded-md border border-zinc-200 px-2 py-1.5 text-[11px] text-zinc-600 hover:bg-zinc-50 whitespace-nowrap shrink-0 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800"
                      >
                        {fmt}
                      </button>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Create book modal */}
      {showCreateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <form
            onSubmit={handleCreateSubmit}
            className="w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-6 shadow-xl dark:border-zinc-800 dark:bg-[#16191D]"
          >
            <div className="flex items-center justify-between border-b border-zinc-200 pb-3 dark:border-zinc-800">
              <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                新建长篇书稿（独立本地 Git 仓库）
              </h2>
              <button
                type="button"
                onClick={() => setShowCreateModal(false)}
                className="text-xs text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100"
              >
                关闭
              </button>
            </div>

            <div className="mt-4 space-y-3.5 text-sm">
              <div>
                <label className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">书名</label>
                <input
                  required
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="例如：霜雪长安录"
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-900 focus:border-zinc-500 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">笔名</label>
                  <input
                    value={newAuthor}
                    onChange={(e) => setNewAuthor(e.target.value)}
                    className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-900 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">题材</label>
                  <input
                    value={newGenre}
                    onChange={(e) => setNewGenre(e.target.value)}
                    className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-900 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                </div>
              </div>

              <div>
                <label className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">简介</label>
                <textarea
                  rows={2}
                  value={newSynopsis}
                  onChange={(e) => setNewSynopsis(e.target.value)}
                  placeholder="一句话写明核心冲突与世界观基底"
                  className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-900 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">
                    单章字数目标
                  </label>
                  <input
                    type="number"
                    min={500}
                    max={20000}
                    step={500}
                    value={newWordGoal}
                    onChange={(e) => setNewWordGoal(Number(e.target.value) || 3000)}
                    className="font-mono-tabular w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-900 focus:outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-zinc-600 dark:text-zinc-400">封面色系</label>
                  <div className="flex items-center gap-2 pt-1.5">
                    {COVER_PALETTES.map((pal, idx) => (
                      <button
                        key={pal.name}
                        type="button"
                        title={pal.name}
                        onClick={() => setNewPaletteIdx(idx)}
                        className={`h-7 w-7 rounded-full border-2 transition ${
                          newPaletteIdx === idx ? 'border-zinc-900 scale-110 dark:border-white' : 'border-transparent'
                        }`}
                        style={{ backgroundColor: pal.bg }}
                      />
                    ))}
                  </div>
                </div>
              </div>
            </div>

            <div className="mt-5 flex items-center justify-between border-t border-zinc-200 pt-4 dark:border-zinc-800">
              <span className="flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
                <Sparkles className="h-3.5 w-3.5" />
                建书后可在技能中心运行 AI 向导配置文风
              </span>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs font-medium text-zinc-600 dark:border-zinc-700 dark:text-zinc-300"
                >
                  取消
                </button>
                <button
                  type="submit"
                  style={{ backgroundColor: 'var(--qj-accent)' }}
                  className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
                >
                  创建并初始化仓库
                </button>
              </div>
            </div>
          </form>
        </div>
      )}

      {/* Book settings & multi-format export modal */}
      {editingBook && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-6 shadow-xl dark:border-zinc-800 dark:bg-[#16191D]">
            <div className="flex items-center justify-between border-b border-zinc-200 pb-3 dark:border-zinc-800">
              <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-100">
                单书设置与多格式导出 · {editingBook.title}
              </h2>
              <button
                type="button"
                onClick={() => setEditingBook(null)}
                className="text-xs text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100"
              >
                关闭
              </button>
            </div>

            <div className="mt-4 space-y-4 text-sm">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs text-zinc-500 dark:text-zinc-400">书名</label>
                  <input
                    value={editingBook.title}
                    onChange={(e) => setEditingBook({ ...editingBook, title: e.target.value })}
                    className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs text-zinc-500 dark:text-zinc-400">作者</label>
                  <input
                    value={editingBook.author}
                    onChange={(e) => setEditingBook({ ...editingBook, author: e.target.value })}
                    className="w-full rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs text-zinc-500 dark:text-zinc-400">
                    单章字数目标 ({editingBook.wordCountGoalPerChapter} 字)
                  </label>
                  <input
                    type="range"
                    min={1000}
                    max={10000}
                    step={500}
                    value={editingBook.wordCountGoalPerChapter}
                    onChange={(e) =>
                      setEditingBook({ ...editingBook, wordCountGoalPerChapter: Number(e.target.value) })
                    }
                    className="w-full accent-emerald-700"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs text-zinc-500 dark:text-zinc-400">
                    编辑器正文宽度 ({editingBook.editorWidthPx}px)
                  </label>
                  <input
                    type="range"
                    min={560}
                    max={960}
                    step={20}
                    value={editingBook.editorWidthPx}
                    onChange={(e) =>
                      setEditingBook({ ...editingBook, editorWidthPx: Number(e.target.value) })
                    }
                    className="w-full accent-emerald-700"
                  />
                </div>
              </div>

              <div>
                <label className="mb-1.5 block text-xs text-zinc-500 dark:text-zinc-400">封面色板</label>
                <div className="flex items-center gap-2">
                  {COVER_PALETTES.map((pal) => (
                    <button
                      key={pal.name}
                      type="button"
                      onClick={() =>
                        setEditingBook({ ...editingBook, coverBg: pal.bg, coverAccent: pal.accent })
                      }
                      className={`rounded-md px-2.5 py-1.5 text-xs text-white transition ${
                        editingBook.coverBg === pal.bg ? 'ring-2 ring-zinc-900 dark:ring-white' : 'opacity-80'
                      }`}
                      style={{ backgroundColor: pal.bg }}
                    >
                      {pal.name}
                    </button>
                  ))}
                </div>
              </div>

              <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3.5 dark:border-zinc-800 dark:bg-zinc-900/60">
                <p className="mb-2 text-xs font-medium text-zinc-700 dark:text-zinc-300">多格式整书导出</p>
                <div className="flex flex-wrap gap-2">
                  {(['JSON', 'MARKDOWN', 'TXT', 'EPUB'] as ExportFormatType[]).map((fmt) => (
                    <button
                      key={fmt}
                      type="button"
                      onClick={() => handleExport(editingBook, fmt)}
                      className="flex items-center gap-1 rounded-md border border-zinc-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-100 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-200"
                    >
                      <Download className="h-3.5 w-3.5" />
                      导出 {fmt}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="mt-5 flex items-center justify-between border-t border-zinc-200 pt-4 dark:border-zinc-800">
              {books.length > 1 ? (
                <button
                  type="button"
                  onClick={() => {
                    onDeleteBook(editingBook.id);
                    setEditingBook(null);
                  }}
                  className="flex items-center gap-1 text-xs font-medium text-rose-600 hover:underline dark:text-rose-400"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  删除本书
                </button>
              ) : (
                <span className="text-xs text-zinc-400">至少保留一部作品</span>
              )}

              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setEditingBook(null)}
                  className="rounded-lg border border-zinc-200 px-3 py-1.5 text-xs text-zinc-600 dark:border-zinc-700 dark:text-zinc-300"
                >
                  取消
                </button>
                <button
                  type="button"
                  onClick={() => {
                    onUpdateBookSettings(editingBook.id, {
                      title: editingBook.title,
                      author: editingBook.author,
                      coverBg: editingBook.coverBg,
                      coverAccent: editingBook.coverAccent,
                      wordCountGoalPerChapter: editingBook.wordCountGoalPerChapter,
                      editorWidthPx: editingBook.editorWidthPx,
                    });
                    setEditingBook(null);
                    onToast('已更新单书配置');
                  }}
                  style={{ backgroundColor: 'var(--qj-accent)' }}
                  className="rounded-lg px-4 py-1.5 text-xs font-medium text-white"
                >
                  保存配置
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
