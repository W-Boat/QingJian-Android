import 'dotenv/config';
import express from 'express';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { GoogleGenAI, ThinkingLevel } from '@google/genai';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const WORKSPACE_ROOT = path.resolve(__dirname, 'data', 'qingjian_workspace');
const STATE_FILE = path.join(WORKSPACE_ROOT, 'state.json');

const app = express();
app.use(express.json({ limit: '16mb' }));

function ensureDir(dirPath: string) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

function sanitizeId(raw: string): string {
  return raw.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 80) || 'untitled';
}

function syncBooksToHumanReadableFiles(books: Array<Record<string, unknown>>) {
  const booksDir = path.join(WORKSPACE_ROOT, 'books');
  ensureDir(booksDir);

  for (const book of books) {
    const id = sanitizeId(String(book.id || 'book'));
    const repoDir = path.join(booksDir, id);
    const chaptersDir = path.join(repoDir, 'chapters');
    const handoffsDir = path.join(repoDir, 'handoffs');
    const loreDir = path.join(repoDir, 'lore');
    const worldbookDir = path.join(repoDir, 'worldbook');
    const commitsDir = path.join(repoDir, 'commits');
    [repoDir, chaptersDir, handoffsDir, loreDir, worldbookDir, commitsDir].forEach(ensureDir);

    const workspace = (book.workspace || {}) as Record<string, unknown>;
    const manifest = {
      id: book.id,
      title: book.title,
      author: book.author,
      genre: book.genre,
      synopsis: book.synopsis,
      currentBranch: book.currentBranch,
      writingMode: book.writingMode,
      wordCountGoalPerChapter: book.wordCountGoalPerChapter,
      updatedAt: book.updatedAt,
    };
    fs.writeFileSync(path.join(repoDir, 'book.json'), JSON.stringify(manifest, null, 2), 'utf-8');

    const chapters = Array.isArray(workspace.chapters) ? (workspace.chapters as Array<Record<string, unknown>>) : [];
    for (const ch of chapters) {
      const chId = sanitizeId(String(ch.chapterId || 'ch'));
      fs.writeFileSync(path.join(chaptersDir, `${chId}.md`), String(ch.markdownContent || ''), 'utf-8');
      fs.writeFileSync(
        path.join(chaptersDir, `${chId}.meta.json`),
        JSON.stringify(
          {
            chapterId: ch.chapterId,
            volumeTitle: ch.volumeTitle,
            title: ch.title,
            order: ch.order,
            status: ch.status,
            summary: ch.summary,
            blockFeedback: ch.blockFeedback,
            blockAnnotations: ch.blockAnnotations,
            updatedAt: ch.updatedAt,
          },
          null,
          2,
        ),
        'utf-8',
      );
    }

    const handoffs = Array.isArray(workspace.handoffs) ? (workspace.handoffs as Array<Record<string, unknown>>) : [];
    for (const h of handoffs) {
      const chId = sanitizeId(String(h.chapterId || 'ch'));
      fs.writeFileSync(path.join(handoffsDir, `${chId}.json`), JSON.stringify(h, null, 2), 'utf-8');
    }

    fs.writeFileSync(
      path.join(loreDir, 'entries.json'),
      JSON.stringify(workspace.loreEntries || [], null, 2),
      'utf-8',
    );
    fs.writeFileSync(
      path.join(worldbookDir, 'entries.json'),
      JSON.stringify(workspace.worldbookEntries || [], null, 2),
      'utf-8',
    );
    fs.writeFileSync(
      path.join(repoDir, 'foreshadows.json'),
      JSON.stringify(workspace.foreshadows || [], null, 2),
      'utf-8',
    );
    fs.writeFileSync(
      path.join(commitsDir, 'history.json'),
      JSON.stringify(
        {
          currentBranch: book.currentBranch,
          branches: book.branches || [],
          commits: Array.isArray(book.commits)
            ? (book.commits as Array<Record<string, unknown>>).map((c) => ({
                hash: c.hash,
                parentHash: c.parentHash,
                branch: c.branch,
                message: c.message,
                timestamp: c.timestamp,
              }))
            : [],
        },
        null,
        2,
      ),
      'utf-8',
    );
  }
}

app.get('/api/workspace', (_req, res) => {
  try {
    if (!fs.existsSync(STATE_FILE)) {
      res.json({ initialized: false, books: [], prefs: null, workspaceRoot: WORKSPACE_ROOT });
      return;
    }
    const raw = fs.readFileSync(STATE_FILE, 'utf-8');
    const parsed = JSON.parse(raw) as { books?: unknown[]; prefs?: unknown };
    res.json({
      initialized: true,
      books: Array.isArray(parsed.books) ? parsed.books : [],
      prefs: parsed.prefs ?? null,
      workspaceRoot: WORKSPACE_ROOT,
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : '读取磁盘工作区失败';
    res.status(500).json({ error: message });
  }
});

app.put('/api/workspace', (req, res) => {
  try {
    const { books, prefs } = req.body as {
      books?: Array<Record<string, unknown>>;
      prefs?: Record<string, unknown>;
    };
    if (!Array.isArray(books)) {
      res.status(400).json({ error: 'books 必须为数组' });
      return;
    }
    ensureDir(WORKSPACE_ROOT);
    const tmpFile = `${STATE_FILE}.tmp`;
    fs.writeFileSync(tmpFile, JSON.stringify({ books, prefs: prefs ?? null, savedAt: Date.now() }, null, 2), 'utf-8');
    fs.renameSync(tmpFile, STATE_FILE);
    syncBooksToHumanReadableFiles(books);
    res.json({ ok: true, bookCount: books.length, workspaceRoot: WORKSPACE_ROOT });
  } catch (err) {
    const message = err instanceof Error ? err.message : '持久化磁盘工作区失败';
    res.status(500).json({ error: message });
  }
});

function mapThinkingLevel(level?: string): ThinkingLevel | undefined {
  switch ((level || '').toUpperCase()) {
    case 'LOW':
      return ThinkingLevel.LOW;
    case 'MEDIUM':
      return ThinkingLevel.MEDIUM;
    case 'HIGH':
    case 'XHIGH':
      return ThinkingLevel.HIGH;
    default:
      return undefined;
  }
}

function mapReasoningEffort(level?: string): string {
  switch ((level || '').toUpperCase()) {
    case 'LOW':
      return 'low';
    case 'MEDIUM':
      return 'medium';
    case 'HIGH':
      return 'high';
    case 'XHIGH':
      return 'xhigh';
    default:
      return 'medium';
  }
}

async function callOpenAiCompatible(params: {
  endpoint: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  userPrompt: string;
  thinkingLevel?: string;
}) {
  const { endpoint, apiKey, model, systemPrompt, userPrompt, thinkingLevel } = params;
  const payload: Record<string, unknown> = {
    model,
    messages: [
      ...(systemPrompt ? [{ role: 'system', content: systemPrompt }] : []),
      { role: 'user', content: userPrompt },
    ],
  };
  const mLower = model.toLowerCase();
  if (mLower.startsWith('o1') || mLower.startsWith('o3') || mLower.startsWith('o4') || mLower.includes('gpt-5')) {
    payload.reasoning_effort = mapReasoningEffort(thinkingLevel);
  }

  const resp = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiKey}`,
      'User-Agent': 'Eta',
    },
    body: JSON.stringify(payload),
  });

  const raw = await resp.text();
  if (!resp.ok) {
    throw new Error(`上游接口返回 ${resp.status}: ${raw.slice(0, 260)}`);
  }
  const data = JSON.parse(raw) as {
    choices?: Array<{ message?: { content?: string } }>;
    usage?: { prompt_tokens?: number; completion_tokens?: number };
  };
  const text = (data.choices?.[0]?.message?.content || '').trim();
  if (!text) {
    throw new Error('上游接口未返回有效文本内容');
  }
  return {
    text,
    promptTokens: data.usage?.prompt_tokens ?? Math.ceil((systemPrompt.length + userPrompt.length) / 2),
    completionTokens: data.usage?.completion_tokens ?? Math.ceil(text.length / 2),
  };
}

async function callCodexResponses(params: {
  endpoint: string;
  accessToken: string;
  accountId: string;
  model: string;
  systemPrompt: string;
  userPrompt: string;
  thinkingLevel?: string;
}) {
  const { endpoint, accessToken, accountId, model, systemPrompt, userPrompt, thinkingLevel } = params;
  const payload = {
    model,
    instructions: systemPrompt || '你是青简长篇中文小说创作助手，语言克制精准，严禁使用 emoji。',
    input: [
      {
        role: 'user',
        content: [{ type: 'input_text', text: userPrompt }],
      },
    ],
    store: false,
    stream: true,
    include: ['reasoning.encrypted_content'],
    reasoning: { effort: mapReasoningEffort(thinkingLevel) },
  };

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    Authorization: `Bearer ${accessToken}`,
    'User-Agent': 'Eta',
    originator: 'eta',
    'OpenAI-Beta': 'responses=experimental',
  };
  if (accountId) {
    headers['ChatGPT-Account-Id'] = accountId;
  }

  const resp = await fetch(endpoint || 'https://chatgpt.com/backend-api/codex/responses', {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  });

  const rawSse = await resp.text();
  if (!resp.ok) {
    throw new Error(`Codex Responses 接口返回 ${resp.status}: ${rawSse.slice(0, 260)}`);
  }

  let text = '';
  let promptTokens = 0;
  let completionTokens = 0;

  for (const frame of rawSse.split(/\r?\n\r?\n/)) {
    const dataLines = frame
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim());
    const dataStr = dataLines.join('\n');
    if (!dataStr || dataStr === '[DONE]') continue;
    try {
      const evt = JSON.parse(dataStr) as Record<string, unknown>;
      if (evt.type === 'response.output_text.delta' && typeof evt.delta === 'string') {
        text += evt.delta;
      } else if (evt.type === 'response.failed') {
        const errObj = (evt.response as Record<string, unknown> | undefined)?.error as
          | Record<string, unknown>
          | undefined;
        throw new Error(String(errObj?.message || 'Codex 响应失败'));
      } else if (evt.type === 'response.completed' || evt.type === 'response.done') {
        const responseObj = (evt.response as Record<string, unknown> | undefined) || evt;
        const usage = responseObj.usage as { input_tokens?: number; output_tokens?: number } | undefined;
        if (usage?.input_tokens) promptTokens = usage.input_tokens;
        if (usage?.output_tokens) completionTokens = usage.output_tokens;
      }
    } catch (e) {
      if (e instanceof Error && e.message.includes('Codex')) throw e;
    }
  }

  const trimmed = text.trim();
  if (!trimmed) {
    throw new Error('Codex Responses SSE 流未返回有效文本');
  }
  return {
    text: trimmed,
    promptTokens: promptTokens || Math.ceil((systemPrompt.length + userPrompt.length) / 2),
    completionTokens: completionTokens || Math.ceil(trimmed.length / 2),
  };
}

app.post('/api/ai/complete', async (req, res) => {
  const {
    provider,
    model,
    endpoint,
    systemPrompt,
    userPrompt,
    thinkingLevel,
    taskLabel,
    oauthAccountId,
  } = req.body as {
    provider?: string;
    model?: string;
    endpoint?: string;
    systemPrompt?: string;
    userPrompt?: string;
    thinkingLevel?: string;
    taskLabel?: string;
    oauthAccountId?: string;
  };

  if (!userPrompt || typeof userPrompt !== 'string') {
    res.status(400).json({ error: '缺少请求正文 userPrompt' });
    return;
  }

  const startMs = Date.now();
  const normalizedProvider = (provider || 'GEMINI_SERVER').toUpperCase();

  try {
    if (normalizedProvider === 'DEEPSEEK' || normalizedProvider === 'OPENAI') {
      const envKey =
        normalizedProvider === 'DEEPSEEK' ? process.env.DEEPSEEK_API_KEY : process.env.OPENAI_API_KEY;
      const bearerFromHeader = req.header('Authorization')?.replace(/^Bearer\s+/i, '').trim();
      const token = bearerFromHeader || envKey;
      if (!token) {
        res.status(503).json({
          error: `服务端未配置 ${normalizedProvider}_API_KEY。请在环境变量配置对应密钥，或切换至服务端已配置的 Gemini Profile。`,
          code: 'PROVIDER_KEY_UNCONFIGURED',
        });
        return;
      }
      const targetEndpoint =
        endpoint ||
        (normalizedProvider === 'DEEPSEEK'
          ? 'https://api.deepseek.com/v1/chat/completions'
          : 'https://api.openai.com/v1/chat/completions');
      const targetModel = model || (normalizedProvider === 'DEEPSEEK' ? 'deepseek-chat' : 'gpt-4.1');
      const result = await callOpenAiCompatible({
        endpoint: targetEndpoint,
        apiKey: token,
        model: targetModel,
        systemPrompt: systemPrompt || '你是青简长篇中文小说创作助手，语言克制精准，严禁使用 emoji。',
        userPrompt,
        thinkingLevel,
      });
      res.json({
        ...result,
        latencyMs: Date.now() - startMs,
        modelUsed: targetModel,
        taskLabel: taskLabel || '文本生成',
      });
      return;
    }

    if (normalizedProvider === 'CHATGPT_CODEX_OAUTH') {
      const bearerFromHeader = req.header('Authorization')?.replace(/^Bearer\s+/i, '').trim();
      const token = bearerFromHeader || process.env.CHATGPT_CODEX_ACCESS_TOKEN;
      if (!token) {
        res.status(503).json({
          error:
            '当前 Web 预览未持有 ChatGPT Codex OAuth 令牌。原生 Android 客户端支持 127.0.0.1:1455 浏览器 PKCE 授权；Web 端请切换至服务端 Gemini 引擎或在环境变量注入 CHATGPT_CODEX_ACCESS_TOKEN。',
          code: 'CODEX_TOKEN_UNCONFIGURED',
        });
        return;
      }
      const targetModel = model || 'gpt-5-codex';
      const result = await callCodexResponses({
        endpoint: endpoint || 'https://chatgpt.com/backend-api/codex/responses',
        accessToken: token,
        accountId: oauthAccountId || process.env.CHATGPT_ACCOUNT_ID || '',
        model: targetModel,
        systemPrompt: systemPrompt || '你是青简长篇中文小说创作助手，语言克制精准，严禁使用 emoji。',
        userPrompt,
        thinkingLevel,
      });
      res.json({
        ...result,
        latencyMs: Date.now() - startMs,
        modelUsed: targetModel,
        taskLabel: taskLabel || '文本生成',
      });
      return;
    }

    // Default: Server-side Gemini API
    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey || apiKey === 'MY_GEMINI_API_KEY') {
      res.status(503).json({
        error: '服务端尚未配置 GEMINI_API_KEY，请在 Secrets 面板配置真实密钥后再发起 AI 生成。',
        code: 'API_KEY_UNCONFIGURED',
      });
      return;
    }

    const ai = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          'User-Agent': 'aistudio-build',
        },
      },
    });

    const mappedThinking = mapThinkingLevel(thinkingLevel);
    const targetModel = model && model.startsWith('gemini') ? model : 'gemini-4-hydrogen';
    const response = await ai.models.generateContent({
      model: targetModel,
      contents: userPrompt,
      config: {
        systemInstruction: systemPrompt || '你是青简长篇中文小说创作助手，语言克制精准，严禁使用 emoji。',
        ...(mappedThinking ? { thinkingConfig: { thinkingLevel: mappedThinking } } : {}),
      },
    });

    const text = (response.text ?? '').trim();
    if (!text) {
      res.status(502).json({ error: '模型返回为空，请检查提示词或稍后重试。' });
      return;
    }
    const latencyMs = Date.now() - startMs;
    const promptTokens =
      response.usageMetadata?.promptTokenCount ??
      Math.ceil((userPrompt.length + (systemPrompt?.length || 0)) / 2);
    const completionTokens = response.usageMetadata?.candidatesTokenCount ?? Math.ceil(text.length / 2);

    res.json({
      text,
      promptTokens,
      completionTokens,
      latencyMs,
      modelUsed: targetModel,
      taskLabel: taskLabel || '文本生成',
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : '调用模型服务失败';
    res.status(502).json({ error: message });
  }
});

app.get('/api/health', (_req, res) => {
  res.json({
    ok: true,
    runtime: 'qingjian-eta-workbench',
    geminiConfigured: Boolean(process.env.GEMINI_API_KEY && process.env.GEMINI_API_KEY !== 'MY_GEMINI_API_KEY'),
    workspaceRoot: WORKSPACE_ROOT,
  });
});

async function startServer() {
  const distDir = path.resolve(__dirname, 'dist');
  const isProd = process.env.NODE_ENV === 'production' && fs.existsSync(distDir);

  if (isProd) {
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  } else {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  }

  const port = Number(process.env.PORT || 3000);
  app.listen(port, '0.0.0.0', () => {
    console.log(`QingJian Workbench listening on http://0.0.0.0:${port}`);
  });
}

startServer();
