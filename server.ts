import 'dotenv/config';
import express from 'express';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { GoogleGenAI, ThinkingLevel } from '@google/genai';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
app.use(express.json({ limit: '4mb' }));

function mapThinkingLevel(level?: string): ThinkingLevel | undefined {
  switch ((level || '').toUpperCase()) {
    case 'LOW':
      return ThinkingLevel.LOW;
    case 'MEDIUM':
      return ThinkingLevel.MEDIUM;
    case 'HIGH':
      return ThinkingLevel.HIGH;
    default:
      return undefined;
  }
}

app.post('/api/ai/complete', async (req, res) => {
  const { systemPrompt, userPrompt, thinkingLevel, taskLabel } = req.body as {
    systemPrompt?: string;
    userPrompt?: string;
    thinkingLevel?: string;
    taskLabel?: string;
  };

  if (!userPrompt || typeof userPrompt !== 'string') {
    res.status(400).json({ error: '缺少请求正文 userPrompt' });
    return;
  }

  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey || apiKey === 'MY_GEMINI_API_KEY') {
    res.status(503).json({
      error: '服务端尚未配置 GEMINI_API_KEY，请在 Secrets 面板配置密钥，或在界面切换至本地规则演练引擎。',
      code: 'API_KEY_UNCONFIGURED',
    });
    return;
  }

  const startMs = Date.now();
  try {
    const ai = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          'User-Agent': 'aistudio-build',
        },
      },
    });

    const mappedThinking = mapThinkingLevel(thinkingLevel);
    const response = await ai.models.generateContent({
      model: 'gemini-4-hydrogen',
      contents: userPrompt,
      config: {
        systemInstruction: systemPrompt || '你是青简长篇中文小说创作助手，语言克制精准，严禁使用 emoji。',
        ...(mappedThinking ? { thinkingConfig: { thinkingLevel: mappedThinking } } : {}),
      },
    });

    const text = (response.text ?? '').trim();
    const latencyMs = Date.now() - startMs;
    const promptTokens = response.usageMetadata?.promptTokenCount ?? Math.ceil((userPrompt.length + (systemPrompt?.length || 0)) / 2);
    const completionTokens = response.usageMetadata?.candidatesTokenCount ?? Math.ceil(text.length / 2);

    res.json({
      text,
      promptTokens,
      completionTokens,
      latencyMs,
      modelUsed: 'gemini-4-hydrogen',
      taskLabel: taskLabel || '文本生成',
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : '调用模型服务失败';
    res.status(502).json({ error: message });
  }
});

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, runtime: 'qingjian-workbench' });
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
