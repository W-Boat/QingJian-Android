import { analyzeStyleDistillation, getDefaultSandboxScripts } from './sandboxJs';
import type { AiAccountProfile, BookWorkspaceSnapshot, NovelProject } from './types';

export function createInitialAiProfiles(): AiAccountProfile[] {
  return [
    {
      id: 'prof_gemini',
      label: '青简默认引擎 (Gemini Server)',
      provider: 'GEMINI_SERVER',
      model: 'gemini-4-hydrogen',
      thinkingLevel: 'MEDIUM',
      endpoint: '/api/ai/complete',
      useLocalFallbackOnError: true,
    },
    {
      id: 'prof_codex_1',
      label: 'ChatGPT Codex 主账号 (OAuth)',
      provider: 'CHATGPT_CODEX_OAUTH',
      model: 'gpt-5-codex',
      thinkingLevel: 'HIGH',
      endpoint: 'https://chatgpt.com/backend-api/codex/responses',
      oauthEmail: 'author.studio@example.com',
      useLocalFallbackOnError: true,
    },
    {
      id: 'prof_deepseek',
      label: 'DeepSeek 长篇主力',
      provider: 'DEEPSEEK',
      model: 'deepseek-reasoner',
      thinkingLevel: 'HIGH',
      endpoint: 'https://api.deepseek.com/v1/chat/completions',
      useLocalFallbackOnError: true,
    },
  ];
}

export function createSeededWhiteTowerProject(): NovelProject {
  const ch1Markdown = `## 第一章 寒灯启塔

永熙十四年腊月初七，雪粒打在白塔青灰色的檐瓦上，发出细密如盐的声响。沈砚提着一盏防风铜灯拾级而上，靴底踩碎石阶薄冰，回声在空荡的塔廊里荡开三层。

> 白塔禁令：子时之后，凡未持司礼监勘合者不得踏足三层暗阁，违者由守塔司就地拿问。

他在二层转角停步，指尖掠过墙龛里半截被雪风吹冷的青铜药杵。药杵底座缺了一角，断口平滑发亮，显然不是经年磨损，而是被人用细锉刻意抹去了编号。

---

「沈大人来得比约定早了一刻。」阴影里传来低缓的脚步声，守塔司副使顾衡未佩长刀，只捧着一只缠了黑绸的木匣，「你若此刻回头，还能赶上城门落锁前的最后一班更鼓。」`;

  const ch2Markdown = `## 第二章 暗阁旧档

三层暗阁没有窗，只有穹顶垂下一束冷白的雪光，正正落在案台中央那枚缺了半角的青铜印上。沈砚解开大氅时，肩头冰碴滚落地砖缝隙，化开几道极浅的水痕。

顾衡坐在灯影边缘，将一卷泛黄的守塔司旧档推过案心。他不禁微微抬眸，眼中闪过一丝难以察觉的试探，空气仿佛凝固了一般。

「白塔禁令白纸黑字写着，子时后严禁启封诏匣。」顾衡指节轻叩匣盖，「三年前北境军报失窃那夜，值守白塔的正是你兄长。今夜你若执意开匣，便等于替旧案重新掀棺。」

---

沈砚没有先看旧档，而是俯身按住青铜印钮侧面的浅痕。那道锉痕的弧度，与二层药杵底座的缺口如出一辙——总而言之，当年动手的绝非外贼，而是手握塔钥的掌印人。`;

  const baseWorkspace: BookWorkspaceSnapshot = {
    chapters: [
      {
        chapterId: 'ch_001',
        volumeTitle: '第一卷 雪夜白塔',
        title: '第一章 寒灯启塔',
        order: 1,
        status: 'FINALIZED',
        summary: '沈砚冒雪赴约登白塔，发现青铜药杵缺口与守塔司夜禁异常。',
        markdownContent: ch1Markdown,
        blockFeedback: { blk_ch_001_2: 'UP' },
        blockAnnotations: { blk_ch_001_4: '关键物证：青铜药杵缺口需与第二章旧印暗记呼应' },
        updatedAt: Date.now() - 86400_000,
      },
      {
        chapterId: 'ch_002',
        volumeTitle: '第一卷 雪夜白塔',
        title: '第二章 暗阁旧档',
        order: 2,
        status: 'DRAFT',
        summary: '沈砚与顾衡在三层暗阁核对守塔司旧档，围绕青铜印锉痕与白塔禁令展开试探。',
        markdownContent: ch2Markdown,
        blockFeedback: { blk_ch_002_2: 'UP', blk_ch_002_3: 'DOWN' },
        blockAnnotations: {
          blk_ch_002_3: '含“不禁”“眼中闪过一丝”“空气仿佛凝固”，可双击局部改稿或用 JS 沙箱检查',
        },
        updatedAt: Date.now() - 3600_000,
      },
    ],
    handoffs: [
      {
        chapterId: 'ch_001',
        chapterTitle: '第一章 寒灯启塔',
        happenedFacts: [
          '永熙十四年腊月初七雪夜，沈砚持防风铜灯独自登白塔赴约。',
          '沈砚在二层发现被细锉磨去编号的青铜药杵（仅沈砚本人知情）。',
          '守塔司副使顾衡手捧黑绸木匣现身，暗示城门将闭，试探沈砚是否退走。',
        ],
        writingLessons: [
          '沿用：雪粒、薄冰、防风铜灯等低饱和冷感官细节，克制且有压迫感。',
          '避免：禁止使用“不禁”“顿时”“眼中闪过一丝”以及段尾“总而言之”式总结腔。',
        ],
        nextChapterHooks: [
          '黑绸木匣内究竟藏着何物？顾衡为何甘冒白塔夜禁之险夜约沈砚？',
          '二层青铜药杵锉痕将在三层与掌印人青铜旧印形成直接物证呼应。',
        ],
        nextDirections: [
          {
            id: 'dir_reconcile',
            title: '和解路线：暗阁对档结盟',
            premise: '沈砚亮出药杵缺口与青铜印比对，迫使顾衡交出三年前值守名录，两人暂结暗盟。',
            tension: '门外监察使将至，脆弱同盟随时瓦解',
          },
          {
            id: 'dir_rupture',
            title: '决裂路线：熄灯夺匣夜遁',
            premise: '沈砚判定木匣系诱饵，反手熄灯制住顾衡，破开三层通风暗道潜逃。',
            tension: '守塔司全面锁塔追捕，沈砚孤立无援',
          },
        ],
        updatedAt: Date.now() - 80000_000,
      },
    ],
    loreEntries: [
      {
        id: 'lore_shen_yan',
        category: 'CHARACTER',
        title: '沈砚',
        content: '前刑部录事，沈氏旧案幸存者。擅长通过器物细微磨损推理真相，言语克制冷峻。',
        sourceChapterId: 'ch_001',
        sourceBlockId: 'blk_ch_001_2',
        sourceExcerpt: '沈砚提着一盏防风铜灯拾级而上',
      },
      {
        id: 'lore_gu_heng',
        category: 'CHARACTER',
        title: '顾衡',
        content: '守塔司副使，执掌白塔夜巡与旧档封存。看似恪守塔规，实则暗查三年前北境军报失窃真相。',
        sourceChapterId: 'ch_001',
        sourceBlockId: 'blk_ch_001_6',
        sourceExcerpt: '守塔司副使顾衡未佩长刀，只捧着一只缠了黑绸的木匣',
      },
      {
        id: 'lore_white_tower',
        category: 'SCENE',
        title: '白塔三层暗阁',
        content: '无窗穹顶密室，专储历年绝密诏匣与司礼监旧档，仅子时前凭勘合可入。',
        sourceChapterId: 'ch_002',
        sourceBlockId: 'blk_ch_002_2',
        sourceExcerpt: '三层暗阁没有窗，只有穹顶垂下一束冷白的雪光',
      },
    ],
    loreCandidates: [
      {
        id: 'cand_north_case',
        category: 'EVENT',
        title: '北境军报失窃旧案',
        summary: '三年前失窃当夜由沈砚兄长值守白塔，如今旧档与青铜印锉痕重现疑云。',
        chapterId: 'ch_002',
        chapterTitle: '第二章 暗阁旧档',
        blockId: 'blk_ch_002_4',
        verbatimExcerpt: '三年前北境军报失窃那夜，值守白塔的正是你兄长',
      },
      {
        id: 'cand_night_ban',
        category: 'WORLD_RULE',
        title: '白塔子时启匣禁令',
        summary: '子时后严禁无勘合启封诏匣，违者由守塔司当场拿问。',
        chapterId: 'ch_001',
        chapterTitle: '第一章 寒灯启塔',
        blockId: 'blk_ch_001_3',
        verbatimExcerpt: '白塔禁令：子时之后，凡未持司礼监勘合者不得踏足三层暗阁',
      },
    ],
    foreshadows: [
      {
        id: 'fs_bronze_notch',
        title: '青铜器上的半寸锉痕',
        description: '二层药杵与三层掌印人青铜印拥有完全一致的细锉磨痕，指向三年前北境军报失窃内鬼。',
        anchors: [
          {
            id: 'fa_1',
            stage: 'PLANT',
            chapterId: 'ch_001',
            chapterTitle: '第一章 寒灯启塔',
            blockId: 'blk_ch_001_4',
            excerpt: '药杵底座缺了一角，断口平滑发亮，显然不是经年磨损',
            note: '初次埋设物证特征',
            createdAt: Date.now() - 84000_000,
          },
          {
            id: 'fa_2',
            stage: 'ECHO',
            chapterId: 'ch_002',
            chapterTitle: '第二章 暗阁旧档',
            blockId: 'blk_ch_002_6',
            excerpt: '那道锉痕的弧度，与二层药杵底座的缺口如出一辙',
            note: '二章呼应，尚未回收幕后真凶',
            createdAt: Date.now() - 3600_000,
          },
        ],
        isResolved: false,
      },
    ],
    causalGraph: {
      nodes: [
        { id: 'n1', chapterId: 'ch_001', title: '雪夜持灯登白塔', detail: '沈砚冒雪赴约进入白塔', x: 60, y: 60 },
        { id: 'n2', chapterId: 'ch_001', title: '发现药杵细锉痕', detail: '二层墙龛现人为磨去编号痕迹', x: 310, y: 60 },
        { id: 'n3', chapterId: 'ch_002', title: '三层暗阁核验旧档', detail: '顾衡出示黑绸木匣与青铜旧印', x: 185, y: 195 },
      ],
      edges: [
        {
          id: 'e1',
          fromNodeId: 'n1',
          toNodeId: 'n2',
          relationLabel: '沿石阶上行途经二层墙龛',
          confirmed: true,
          aiSuggested: false,
        },
        {
          id: 'e2',
          fromNodeId: 'n2',
          toNodeId: 'n3',
          relationLabel: '药杵锉痕驱动沈砚核验青铜旧印暗记',
          confirmed: false,
          aiSuggested: true,
        },
      ],
    },
    plotPlans: [
      {
        id: 'plan_reconcile',
        title: '卷末分支 A：和解查案路线',
        sourceChapterId: 'ch_002',
        premise: '沈砚与顾衡在监察使敲门前达成秘密协定，伪造封条拖延时间。',
        keyBeats: ['核对司礼监朱批墨印真伪', '联手揪出磨去暗记的第三人'],
        branchTag: '和解构思',
      },
      {
        id: 'plan_rupture',
        title: '卷末分支 B：决裂夜遁路线',
        sourceChapterId: 'ch_002',
        premise: '沈砚怀疑顾衡设局栽赃，熄灯夺印从暗阁风井撤离白塔。',
        keyBeats: ['触发白塔全境夜禁追捕', '潜入城西药铺寻谢晚晴核验副录'],
        branchTag: '决裂构思',
      },
    ],
    worldbookEntries: [
      {
        id: 'wb_era',
        title: '大昭永熙纪年',
        content: '故事发生于大昭永熙十四年隆冬，朝局由司礼监与守塔司分掌内廷机密。',
        enabled: true,
        alwaysActive: true,
        primaryKeys: [],
        secondaryKeys: [],
        secondaryLogic: 'NONE',
        priority: 100,
        recursiveScan: false,
        category: '时代背景',
      },
      {
        id: 'wb_tower_ban',
        title: '白塔夜禁律令',
        content: '白塔三层暗阁子时后封锁，凡无勘合擅启诏匣者，由守塔司按谋逆论处，立时落闸锁塔。',
        enabled: true,
        alwaysActive: false,
        primaryKeys: ['白塔'],
        secondaryKeys: ['禁令', '夜禁', '子时'],
        secondaryLogic: 'AND_ANY',
        priority: 90,
        recursiveScan: true,
        category: '律令规则',
      },
      {
        id: 'wb_guard_bureau',
        title: '守塔司编制与青铜旧印',
        content: '守塔司直属御前，仅正副二使持有青铜旧印；每枚印钮内侧刻有唯一暗记，严禁私磨。',
        enabled: true,
        alwaysActive: false,
        primaryKeys: ['守塔司', '青铜印'],
        secondaryKeys: [],
        secondaryLogic: 'NONE',
        priority: 80,
        recursiveScan: true,
        category: '组织器物',
      },
    ],
  };

  // Second branch ("route-reconciliation") starts from base commit with reconciled ch_002 ending
  const reconcileWorkspace: BookWorkspaceSnapshot = JSON.parse(JSON.stringify(baseWorkspace));
  reconcileWorkspace.chapters[1].markdownContent +=
    '\n\n顾衡沉默片刻，将守塔司副印反扣在案上：「既然你认出了锉痕，那今夜这份名录，便由我们两人共查。」';

  const initHash = 'a84f29c10e7b31a94201';
  const branchHash = 'd31e77b92c4a65e01894';

  return {
    id: 'book_white_tower',
    title: '白塔遗诏',
    author: '林墨',
    genre: '悬疑古风',
    synopsis: '永熙十四年冬夜，一纸缺角青铜印与半卷白塔禁令，将沉寂三年的北境军报失窃旧案重新推上风口。',
    coverBg: '#1A3A3A',
    coverAccent: '#2B6E63',
    wordCountGoalPerChapter: 3000,
    editorWidthPx: 720,
    writingMode: 'HUMAN_LED',
    aiWizardCompleted: true,
    workspace: baseWorkspace,
    skills: {
      enabledBuiltIn: ['STYLE_DISTILL', 'ARMOR_BREAK', 'DE_AI_TONE'],
      styleProfile: analyzeStyleDistillation('第一章 寒灯启塔', ch1Markdown),
      customCorrectionRules: [
        '严禁在段尾使用“总而言之”“由此可见”等总结句',
        '情绪须通过器物触感、风声或细微动作外化，不直接宣称心理标签',
      ],
      importedPackages: [],
      scripts: getDefaultSandboxScripts(),
    },
    contextConfig: {
      neighborParagraphsBefore: 3,
      neighborParagraphsAfter: 2,
      includeOutline: true,
      includeFinalizedHandoff: true,
      includeAuthorFeedback: true,
      worldbookCharBudget: 2400,
      totalContextCharBudget: 12000,
    },
    rpCards: [
      {
        id: 'card_gu_heng',
        specVersion: 'chara_card_v2',
        name: '顾衡',
        description: '守塔司副使，冷峻克制，擅长在对话中以退为进、借塔规施压。',
        personality: '滴水不漏、言简意赅、惜字如金',
        scenario: '永熙十四年雪夜，白塔三层暗阁灯下对峙',
        firstMessage:
          '（顾衡指尖轻压在黑绸木匣的铜扣上，未抬眼看灯）\n\n「你既把二层药杵的事咽回肚里，今夜便不必再绕弯子。白塔禁令响前，你只有一刻钟问话。」',
        tags: ['白塔遗诏', '守塔司'],
      },
    ],
    currentBranch: 'main',
    branches: [
      { name: 'main', headCommitHash: initHash },
      { name: 'route-reconciliation', headCommitHash: branchHash },
    ],
    commits: [
      {
        hash: branchHash,
        shortHash: branchHash.slice(0, 7),
        message: '和解路线：第二章暗阁达成共查协定',
        author: '林墨',
        timestamp: Date.now() - 1800_000,
        parentHash: initHash,
        snapshot: reconcileWorkspace,
      },
      {
        hash: initHash,
        shortHash: initHash.slice(0, 7),
        message: '初始化《白塔遗诏》主线书稿（含一二章与交接稿）',
        author: '林墨',
        timestamp: Date.now() - 7200_000,
        parentHash: null,
        snapshot: baseWorkspace,
      },
    ],
    restorePoints: [],
    activePipelineRun: null,
    updatedAt: Date.now(),
  };
}
