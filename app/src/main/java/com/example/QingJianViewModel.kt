package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.QingJianPipelineService
import com.example.ui.theme.QingJianAccent
import com.qingjian.core.eta.AiAccountProfile
import com.qingjian.core.eta.AiCompletionRequest
import com.qingjian.core.eta.AiProviderType
import com.qingjian.core.eta.ChatMessage
import com.qingjian.core.eta.ContextCompressionEngine
import com.qingjian.core.eta.ContextWindowConfig
import com.qingjian.core.eta.EtaAgentRuntime
import com.qingjian.core.eta.SecondaryKeyLogic
import com.qingjian.core.eta.TavernCharacterCard
import com.qingjian.core.eta.ThinkingLevel
import com.qingjian.core.eta.WorldbookEngine
import com.qingjian.core.eta.WorldbookEntry
import com.qingjian.core.git.BookExportEngine
import com.qingjian.core.git.BookGitRepository
import com.qingjian.core.git.BookRepoLayout
import com.qingjian.core.git.ExportFormat
import com.qingjian.core.git.ExportableBook
import com.qingjian.core.git.ExportableChapter
import com.qingjian.core.sandbox.BookSkillsState
import com.qingjian.core.sandbox.BuiltInSkillType
import com.qingjian.core.sandbox.DefaultSandboxScripts
import com.qingjian.core.sandbox.ImportedSkillPackage
import com.qingjian.core.sandbox.IsolatedJsSandbox
import com.qingjian.core.sandbox.SandboxJsScript
import com.qingjian.core.sandbox.ScriptExecutionReport
import com.qingjian.core.sandbox.StyleDistillationAnalyzer
import com.qingjian.core.storage.AiUsageEntity
import com.qingjian.core.storage.BookIndexEntity
import com.qingjian.core.storage.BookManifest
import com.qingjian.core.storage.CausalEdge
import com.qingjian.core.storage.CausalEdgeEntity
import com.qingjian.core.storage.CausalGraphData
import com.qingjian.core.storage.CausalNode
import com.qingjian.core.storage.ChapterDocument
import com.qingjian.core.storage.ChapterIndexEntity
import com.qingjian.core.storage.ChapterStatus
import com.qingjian.core.storage.FeedbackVote
import com.qingjian.core.storage.ForeshadowAnchor
import com.qingjian.core.storage.ForeshadowAnchorEntity
import com.qingjian.core.storage.ForeshadowStage
import com.qingjian.core.storage.ForeshadowThread
import com.qingjian.core.storage.HandoffBrief
import com.qingjian.core.storage.HandoffDirection
import com.qingjian.core.storage.LoreCategory
import com.qingjian.core.storage.LoreEntry
import com.qingjian.core.storage.LoreExtractionCandidate
import com.qingjian.core.storage.PipelineRunEntity
import com.qingjian.core.storage.PipelineStage
import com.qingjian.core.storage.PlotPlanIdea
import com.qingjian.core.storage.QingJianDatabase
import com.qingjian.core.storage.RestorePointEntity
import com.qingjian.core.storage.ResumablePipelineExecutor
import com.qingjian.core.storage.RoomPipelineStateStore
import com.qingjian.core.storage.WritingMode
import com.qingjian.editor.muya.AtomicUndoRecord
import com.qingjian.editor.muya.MarkdownStructureGuard
import com.qingjian.editor.muya.RevisionUndoStack
import com.qingjian.editor.muya.StructureGuardResult
import com.qingjian.feature.editor.FullScreenDiffKind
import com.qingjian.feature.editor.FullScreenDiffProposal
import com.qingjian.feature.editor.GitWorkspaceSnapshot
import com.qingjian.feature.editor.InlineRevisionCandidateState
import com.qingjian.feature.rp.RpTurnMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainDestination(val label: String) {
  BOOKSHELF("书架"),
  EDITOR("写作"),
  LORE("剧情"),
  SKILLS("技能"),
  RP("试戏"),
}

data class QingJianUiState(
  val destination: MainDestination = MainDestination.EDITOR,
  val isDarkTheme: Boolean = false,
  val accent: QingJianAccent = QingJianAccent.JADE,
  val isGridView: Boolean = true,
  val books: List<BookIndexEntity> = emptyList(),
  val activeBookId: String? = null,
  val activeManifest: BookManifest? = null,
  val chapters: List<ChapterIndexEntity> = emptyList(),
  val activeChapter: ChapterDocument? = null,
  val handoffs: List<HandoffBrief> = emptyList(),
  val loreEntries: List<LoreEntry> = emptyList(),
  val loreCandidates: List<LoreExtractionCandidate> = emptyList(),
  val foreshadows: List<ForeshadowThread> = emptyList(),
  val causalGraph: CausalGraphData = CausalGraphData(),
  val plotPlans: List<PlotPlanIdea> = emptyList(),
  val worldbookEntries: List<WorldbookEntry> = emptyList(),
  val contextConfig: ContextWindowConfig = ContextWindowConfig(),
  val skillsState: BookSkillsState = BookSkillsState(),
  val lastScriptReport: ScriptExecutionReport? = null,
  val sandboxError: String? = null,
  val rpCards: List<TavernCharacterCard> = emptyList(),
  val selectedRpCardId: String? = null,
  val rpMessages: List<RpTurnMessage> = emptyList(),
  val isRpGenerating: Boolean = false,
  val inlineCandidate: InlineRevisionCandidateState? = null,
  val fullScreenDiff: FullScreenDiffProposal? = null,
  val activePipelineRun: PipelineRunEntity? = null,
  val gitSnapshot: GitWorkspaceSnapshot = GitWorkspaceSnapshot(),
  val canUndo: Boolean = false,
  val pendingJumpBlockId: String? = null,
  val aiProfiles: List<AiAccountProfile> = defaultAiProfiles(),
  val activeProfileId: String = "prof_deepseek",
  val usageLogs: List<AiUsageEntity> = emptyList(),
  val statusBanner: String? = null,
)

private fun defaultAiProfiles(): List<AiAccountProfile> = listOf(
  AiAccountProfile(
    id = "prof_deepseek",
    label = "DeepSeek 主力写作",
    provider = AiProviderType.DEEPSEEK,
    model = "deepseek-chat",
    thinkingLevel = ThinkingLevel.MEDIUM,
  ),
  AiAccountProfile(
    id = "prof_codex_primary",
    label = "ChatGPT Codex 主账号",
    provider = AiProviderType.CHATGPT_CODEX_OAUTH,
    model = "gpt-5-codex",
    thinkingLevel = ThinkingLevel.HIGH,
  ),
  AiAccountProfile(
    id = "prof_openai",
    label = "OpenAI 兼容接口",
    provider = AiProviderType.OPENAI,
    model = "gpt-4.1",
    thinkingLevel = ThinkingLevel.MEDIUM,
  ),
)

class QingJianViewModel(application: Application) : AndroidViewModel(application) {
  private val appContext = application.applicationContext
  private val db = QingJianDatabase.getInstance(appContext)
  private val etaRuntime = EtaAgentRuntime()
  private val jsSandbox = IsolatedJsSandbox(appContext)
  private val undoStack = RevisionUndoStack()
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val booksRootDir = File(appContext.filesDir, "qingjian_books")
  private val exportRootDir = File(appContext.filesDir, "qingjian_exports")

  private val _uiState = MutableStateFlow(QingJianUiState())
  val uiState: StateFlow<QingJianUiState> = _uiState.asStateFlow()

  private var bookCollectorsJob: Job? = null

  init {
    observeGlobalStreams()
    viewModelScope.launch {
      seedSampleBookIfEmpty()
    }
  }

  private fun observeGlobalStreams() {
    viewModelScope.launch {
      db.bookIndexDao().observeBooks().collect { books ->
        _uiState.update { it.copy(books = books) }
        if (_uiState.value.activeBookId == null && books.isNotEmpty()) {
          openBook(books.first().bookId, switchToEditor = false)
        }
      }
    }
    viewModelScope.launch {
      db.aiUsageDao().observeUsage().collect { logs ->
        _uiState.update { it.copy(usageLogs = logs) }
      }
    }
  }

  fun navigate(dest: MainDestination) {
    _uiState.update { it.copy(destination = dest) }
  }

  fun toggleViewMode() {
    _uiState.update { it.copy(isGridView = !it.isGridView) }
  }

  fun setDarkTheme(dark: Boolean) {
    _uiState.update { it.copy(isDarkTheme = dark) }
  }

  fun setAccent(accent: QingJianAccent) {
    _uiState.update { it.copy(accent = accent) }
  }

  fun clearBanner() {
    _uiState.update { it.copy(statusBanner = null) }
  }

  fun selectAiProfile(profileId: String) {
    _uiState.update { it.copy(activeProfileId = profileId) }
  }

  fun updateAiProfile(profile: AiAccountProfile) {
    _uiState.update { state ->
      state.copy(
        aiProfiles = state.aiProfiles.map { if (it.id == profile.id) profile else it },
        statusBanner = "已保存 AI 配置：${profile.label}",
      )
    }
  }

  fun addAiProfile(profile: AiAccountProfile) {
    _uiState.update { state ->
      state.copy(
        aiProfiles = state.aiProfiles + profile,
        activeProfileId = profile.id,
        statusBanner = "已新增账号配置：${profile.label}",
      )
    }
  }

  private fun activeProfile(): AiAccountProfile {
    val st = _uiState.value
    return st.aiProfiles.firstOrNull { it.id == st.activeProfileId } ?: st.aiProfiles.first()
  }

  private fun layoutForBook(bookId: String): BookRepoLayout =
    BookRepoLayout(File(booksRootDir, bookId))

  private fun gitRepoForBook(bookId: String): BookGitRepository =
    BookGitRepository(layoutForBook(bookId))

  // --- Bookshelf Operations ---

  fun createNewBook(
    title: String,
    author: String,
    genre: String,
    synopsis: String,
    coverBg: Long,
    coverAccent: Long,
    wordGoal: Int,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      val bookId = "book_${UUID.randomUUID().toString().take(8)}"
      val layout = layoutForBook(bookId)
      val git = BookGitRepository(layout)
      layout.ensureDirectories()

      val manifest = BookManifest(
        id = bookId,
        title = title,
        author = author,
        genre = genre,
        synopsis = synopsis,
        coverColorHex = coverBg,
        coverAccentHex = coverAccent,
        wordCountGoalPerChapter = wordGoal,
      )
      writeJson(layout.bookManifestFile, manifest, BookManifest::class.java)

      val ch1 = ChapterDocument(
        chapterId = "ch_001",
        bookId = bookId,
        volumeTitle = "第一卷",
        title = "第一章 开篇",
        order = 1,
        markdownContent = "## 第一章 开篇\n\n夜色沉沉，笔锋初落。单击任意段落可展开工具条，双击唤起局部改稿，长按 650ms 打开完整编辑弹窗。",
      )
      saveChapterToRepo(layout, ch1)
      writeListJson(layout.foreshadowsFile, emptyList<ForeshadowThread>(), ForeshadowThread::class.java)
      writeListJson(layout.loreEntitiesFile, emptyList<LoreEntry>(), LoreEntry::class.java)
      writeListJson(layout.loreCandidatesFile, emptyList<LoreExtractionCandidate>(), LoreExtractionCandidate::class.java)
      writeJson(layout.causalGraphFile, CausalGraphData(), CausalGraphData::class.java)
      writeListJson(layout.plotPlansFile, emptyList<PlotPlanIdea>(), PlotPlanIdea::class.java)
      writeListJson(layout.worldbookEntriesFile, emptyList<WorldbookEntry>(), WorldbookEntry::class.java)
      writeJson(layout.skillsConfigFile, BookSkillsState(), BookSkillsState::class.java)
      writeListJson(layout.rpCardsFile, emptyList<TavernCharacterCard>(), TavernCharacterCard::class.java)

      git.initIfNeeded("初始化《$title》仓库")
      reindexBook(bookId)
      openBook(bookId, switchToEditor = true)
    }
  }

  fun updateBookSettings(bookId: String, wordGoal: Int, editorWidthDp: Int, coverBg: Long, coverAccent: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      val layout = layoutForBook(bookId)
      val manifest = readJson(layout.bookManifestFile, BookManifest::class.java) ?: return@launch
      val updated = manifest.copy(
        wordCountGoalPerChapter = wordGoal,
        editorWidthDp = editorWidthDp,
        coverColorHex = coverBg,
        coverAccentHex = coverAccent,
        updatedAt = System.currentTimeMillis(),
      )
      writeJson(layout.bookManifestFile, updated, BookManifest::class.java)
      reindexBook(bookId)
      if (_uiState.value.activeBookId == bookId) {
        _uiState.update { it.copy(activeManifest = updated, statusBanner = "已更新书籍排版与封面设置") }
      }
    }
  }

  fun deleteBook(bookId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      db.bookIndexDao().deleteBook(bookId)
      db.chapterIndexDao().clearForBook(bookId)
      db.foreshadowIndexDao().clearForBook(bookId)
      db.causalEdgeDao().clearForBook(bookId)
      layoutForBook(bookId).repoRoot.deleteRecursively()
      if (_uiState.value.activeBookId == bookId) {
        val next = _uiState.value.books.firstOrNull { it.bookId != bookId }
        if (next != null) {
          openBook(next.bookId, switchToEditor = false)
        } else {
          _uiState.update { it.copy(activeBookId = null, activeChapter = null, activeManifest = null) }
        }
      }
    }
  }

  fun exportBookSync(bookId: String, format: ExportFormat): String {
    val layout = layoutForBook(bookId)
    val manifest = readJson(layout.bookManifestFile, BookManifest::class.java)
      ?: return "未找到书稿元数据"
    val chapters = loadAllChaptersFromDisk(layout, bookId).map { ch ->
      ExportableChapter(
        id = ch.chapterId,
        volumeTitle = ch.volumeTitle,
        title = ch.title,
        order = ch.order,
        markdownContent = ch.markdownContent,
      )
    }
    val outFile = BookExportEngine.exportBook(
      ExportableBook(
        id = manifest.id,
        title = manifest.title,
        author = manifest.author,
        synopsis = manifest.synopsis,
        chapters = chapters,
      ),
      format,
      exportRootDir,
    )
    return "已导出 ${format.label}：${outFile.name}（${outFile.length()} 字节）"
  }

  fun openBook(bookId: String, switchToEditor: Boolean = true) {
    bookCollectorsJob?.cancel()
    bookCollectorsJob = viewModelScope.launch {
      launch {
        db.chapterIndexDao().observeChapters(bookId).collect { list ->
          _uiState.update { it.copy(chapters = list) }
        }
      }
      launch {
        db.pipelineRunDao().observeRuns(bookId).collect { runs ->
          _uiState.update { it.copy(activePipelineRun = runs.firstOrNull()) }
        }
      }
      launch {
        db.restorePointDao().observeRestorePoints(bookId).collect { rps ->
          _uiState.update { st -> st.copy(gitSnapshot = st.gitSnapshot.copy(restorePoints = rps)) }
        }
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      val layout = layoutForBook(bookId)
      val manifest = readJson(layout.bookManifestFile, BookManifest::class.java) ?: return@launch
      val chapters = loadAllChaptersFromDisk(layout, bookId)
      val firstChapter = chapters.firstOrNull()
      val handoffs = loadAllHandoffsFromDisk(layout)
      val lore = readListJson(layout.loreEntitiesFile, LoreEntry::class.java)
      val candidates = readListJson(layout.loreCandidatesFile, LoreExtractionCandidate::class.java)
      val foreshadows = readListJson(layout.foreshadowsFile, ForeshadowThread::class.java)
      val causal = readJson(layout.causalGraphFile, CausalGraphData::class.java) ?: CausalGraphData()
      val plans = readListJson(layout.plotPlansFile, PlotPlanIdea::class.java)
      val wb = readListJson(layout.worldbookEntriesFile, WorldbookEntry::class.java)
      val skills = readJson(layout.skillsConfigFile, BookSkillsState::class.java) ?: BookSkillsState()
      val cards = readListJson(layout.rpCardsFile, TavernCharacterCard::class.java)

      val git = BookGitRepository(layout)
      val branch = runCatching { git.currentBranch() }.getOrDefault("main")
      val branches = runCatching { git.listBranches() }.getOrDefault(emptyList())
      val commits = runCatching { git.listCommits() }.getOrDefault(emptyList())

      val initialRpMessages = cards.firstOrNull()?.takeIf { it.firstMessage.isNotBlank() }?.let { card ->
        listOf(
          RpTurnMessage(
            id = "rp_init_${card.id}",
            isAuthor = false,
            speaker = card.name,
            content = card.firstMessage,
          )
        )
      }.orEmpty()

      _uiState.update { st ->
        st.copy(
          destination = if (switchToEditor) MainDestination.EDITOR else st.destination,
          activeBookId = bookId,
          activeManifest = manifest,
          activeChapter = firstChapter,
          handoffs = handoffs,
          loreEntries = lore,
          loreCandidates = candidates,
          foreshadows = foreshadows,
          causalGraph = causal,
          plotPlans = plans,
          worldbookEntries = wb,
          skillsState = skills,
          rpCards = cards,
          selectedRpCardId = cards.firstOrNull()?.id,
          rpMessages = initialRpMessages,
          inlineCandidate = null,
          fullScreenDiff = null,
          gitSnapshot = st.gitSnapshot.copy(
            currentBranch = branch,
            branches = branches,
            commits = commits,
          ),
        )
      }
    }
  }

  // --- Editor & Revision Operations (Pillar 1) ---

  fun setWritingMode(mode: WritingMode) {
    val bookId = _uiState.value.activeBookId ?: return
    val manifest = _uiState.value.activeManifest ?: return
    val updated = manifest.copy(writingMode = mode, updatedAt = System.currentTimeMillis())
    _uiState.update { it.copy(activeManifest = updated) }
    viewModelScope.launch(Dispatchers.IO) {
      writeJson(layoutForBook(bookId).bookManifestFile, updated, BookManifest::class.java)
    }
  }

  fun selectChapter(chapterId: String, jumpToBlockId: String? = null) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val ch = loadChapterFromDisk(layoutForBook(bookId), bookId, chapterId) ?: return@launch
      _uiState.update {
        it.copy(
          activeChapter = ch,
          inlineCandidate = null,
          pendingJumpBlockId = jumpToBlockId,
        )
      }
    }
  }

  fun consumeJumpBlockId() {
    _uiState.update { it.copy(pendingJumpBlockId = null) }
  }

  fun jumpToForeshadowOrLoreAnchor(chapterId: String, blockId: String) {
    _uiState.update { it.copy(destination = MainDestination.EDITOR) }
    if (_uiState.value.activeChapter?.chapterId == chapterId) {
      _uiState.update { it.copy(pendingJumpBlockId = blockId) }
    } else {
      selectChapter(chapterId, jumpToBlockId = blockId)
    }
  }

  fun createBlankChapter(volumeTitle: String, title: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val nextOrder = (_uiState.value.chapters.maxOfOrNull { it.chapterOrder } ?: 0) + 1
      val chId = "ch_%03d".format(nextOrder)
      val doc = ChapterDocument(
        chapterId = chId,
        bookId = bookId,
        volumeTitle = volumeTitle,
        title = title,
        order = nextOrder,
        markdownContent = "## $title\n\n此处开始撰写新章正文。",
      )
      saveChapterToRepo(layoutForBook(bookId), doc)
      reindexBook(bookId)
      _uiState.update { it.copy(activeChapter = doc, statusBanner = "已新建章节：$title") }
    }
  }

  fun onDocumentChanged(newMarkdown: String) {
    val current = _uiState.value.activeChapter ?: return
    if (current.markdownContent == newMarkdown) return
    val updated = current.copy(markdownContent = newMarkdown, updatedAt = System.currentTimeMillis())
    _uiState.update { it.copy(activeChapter = updated) }
    persistChapterAsync(updated)
  }

  fun updateSingleBlockDirect(blockId: String, newBlockMarkdown: String) {
    val current = _uiState.value.activeChapter ?: return
    val blocks = current.toBlocks()
    val replaced = blocks.map { if (it.id == blockId) newBlockMarkdown else it.markdown }.joinToString("\n\n")
    pushUndoAndApply(current, replaced, "手动编辑段落")
  }

  fun setBlockVote(blockId: String, vote: FeedbackVote) {
    val current = _uiState.value.activeChapter ?: return
    val map = current.blockFeedback.toMutableMap()
    if (vote == FeedbackVote.NONE) map.remove(blockId) else map[blockId] = vote
    val updated = current.copy(blockFeedback = map, updatedAt = System.currentTimeMillis())
    _uiState.update { it.copy(activeChapter = updated) }
    persistChapterAsync(updated)
  }

  fun setBlockAnnotation(blockId: String, note: String) {
    val current = _uiState.value.activeChapter ?: return
    val map = current.blockAnnotations.toMutableMap()
    if (note.isBlank()) map.remove(blockId) else map[blockId] = note
    val updated = current.copy(blockAnnotations = map, updatedAt = System.currentTimeMillis())
    _uiState.update { it.copy(activeChapter = updated) }
    persistChapterAsync(updated)
  }

  fun requestSingleBlockRewrite(blockId: String, instruction: String) {
    val st = _uiState.value
    val chapter = st.activeChapter ?: return
    val blocks = chapter.toBlocks()
    val targetIdx = blocks.indexOfFirst { it.id == blockId }
    if (targetIdx < 0) return
    val targetBlock = blocks[targetIdx]

    val existing = st.inlineCandidate?.takeIf { it.blockId == blockId }
    _uiState.update {
      it.copy(
        inlineCandidate = existing?.copy(isGenerating = true, instruction = instruction)
          ?: InlineRevisionCandidateState(
            blockId = blockId,
            originalMarkdown = targetBlock.markdown,
            instruction = instruction,
            candidates = listOf("正在生成改稿候选…"),
            isGenerating = true,
          )
      )
    }

    viewModelScope.launch {
      val contextPrompt = assembleContextPrompt(chapter, targetIdx)
      val systemPrompt = buildString {
        appendLine("你是长篇小说精修编辑，仅输出改写后的该段落 Markdown 正文，严格保留原有标题、引用、列表与成对强调结构，不输出解释。")
        appendLine(st.skillsState.buildSystemPromptDirectives())
      }
      val userPrompt = """
        |$contextPrompt
        |
        |【原文】
        |${targetBlock.markdown}
        |
        |【改稿要求】
        |$instruction
      """.trimMargin()

      val response = runAiCall(
        taskLabel = "单段改稿",
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
      )

      _uiState.update { cur ->
        val prevList = cur.inlineCandidate?.takeIf { it.blockId == blockId && !it.candidates.firstOrNull().orEmpty().startsWith("正在生成") }?.candidates.orEmpty()
        val nextList = prevList + response.text
        cur.copy(
          inlineCandidate = InlineRevisionCandidateState(
            blockId = blockId,
            originalMarkdown = targetBlock.markdown,
            instruction = instruction,
            candidates = nextList,
            activeIndex = nextList.lastIndex,
            isGenerating = false,
          )
        )
      }
    }
  }

  fun cycleInlineCandidate(nextIndex: Int) {
    _uiState.update { st ->
      val cand = st.inlineCandidate ?: return@update st
      st.copy(inlineCandidate = cand.copy(activeIndex = nextIndex.coerceIn(0, cand.candidates.lastIndex)))
    }
  }

  fun updateInlineCandidateText(newText: String) {
    _uiState.update { st ->
      val cand = st.inlineCandidate ?: return@update st
      val updatedList = cand.candidates.toMutableList()
      updatedList[cand.activeIndex] = newText
      st.copy(inlineCandidate = cand.copy(candidates = updatedList))
    }
  }

  fun acceptInlineCandidate() {
    val st = _uiState.value
    val cand = st.inlineCandidate ?: return
    val chapter = st.activeChapter ?: return
    val guard = MarkdownStructureGuard.validate(cand.originalMarkdown, cand.activeCandidate)
    if (guard is StructureGuardResult.StructureBroken) {
      _uiState.update { it.copy(statusBanner = "结构守卫已拦截落稿，请重新生成或手动修复。") }
      return
    }
    val blocks = chapter.toBlocks()
    val merged = blocks.map { if (it.id == cand.blockId) cand.activeCandidate else it.markdown }.joinToString("\n\n")
    pushUndoAndApply(chapter, merged, "接受行内单段改稿")
    _uiState.update { it.copy(inlineCandidate = null) }
  }

  fun rejectInlineCandidate() {
    _uiState.update { it.copy(inlineCandidate = null) }
  }

  fun requestBatchRewrite(blockIds: List<String>, instruction: String) {
    val st = _uiState.value
    val chapter = st.activeChapter ?: return
    val blocks = chapter.toBlocks()
    val targets = blocks.filter { it.id in blockIds }
    if (targets.isEmpty()) return

    val joinedOriginal = targets.joinToString("\n\n") { it.markdown }
    viewModelScope.launch {
      val systemPrompt = "你是长篇小说审校编辑，请联合修订以下选定段落（可能不相邻），按原段落顺序输出，段落之间空一行。\n${st.skillsState.buildSystemPromptDirectives()}"
      val revisedParts = targets.map { blk ->
        val resp = runAiCall(
          taskLabel = "多段联合改稿",
          systemPrompt = systemPrompt,
          userPrompt = "【原文】\n${blk.markdown}\n\n【改稿要求】\n$instruction",
        )
        resp.text
      }
      _uiState.update {
        it.copy(
          fullScreenDiff = FullScreenDiffProposal(
            kind = FullScreenDiffKind.BATCH_REWRITE,
            title = "已选 ${targets.size} 个段落联合改稿",
            originalText = joinedOriginal,
            proposedText = revisedParts.joinToString("\n\n"),
            targetBlockIds = targets.map { t -> t.id },
          )
        )
      }
    }
  }

  fun acceptFullScreenDiff(editedText: String) {
    val st = _uiState.value
    val proposal = st.fullScreenDiff ?: return
    val chapter = st.activeChapter ?: return

    val nextMarkdown = when (proposal.kind) {
      FullScreenDiffKind.BATCH_REWRITE -> {
        val revisedBlocks = editedText.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val replacementMap = proposal.targetBlockIds.zip(revisedBlocks).toMap()
        chapter.toBlocks().map { blk -> replacementMap[blk.id] ?: blk.markdown }.joinToString("\n\n")
      }
      FullScreenDiffKind.FULL_CHAPTER_DRAFT -> editedText
      FullScreenDiffKind.CHAPTER_SUMMARY -> {
        val updated = chapter.copy(summary = editedText, updatedAt = System.currentTimeMillis())
        _uiState.update { it.copy(activeChapter = updated, fullScreenDiff = null, statusBanner = "已保存章节摘要") }
        persistChapterAsync(updated)
        return
      }
    }

    pushUndoAndApply(chapter, nextMarkdown, "接受${proposal.kind.label}")
    _uiState.update { it.copy(fullScreenDiff = null) }
  }

  fun rejectFullScreenDiff() {
    _uiState.update { it.copy(fullScreenDiff = null) }
  }

  fun undoLastRevision() {
    val rec = undoStack.popUndo() ?: return
    val chapter = _uiState.value.activeChapter ?: return
    if (chapter.chapterId != rec.chapterId) return
    val reverted = chapter.copy(markdownContent = rec.previousMarkdown, updatedAt = System.currentTimeMillis())
    _uiState.update {
      it.copy(
        activeChapter = reverted,
        canUndo = undoStack.canUndo(),
        statusBanner = "已撤销：${rec.label}",
      )
    }
    persistChapterAsync(reverted)
  }

  private fun pushUndoAndApply(chapter: ChapterDocument, nextMarkdown: String, label: String) {
    undoStack.push(
      AtomicUndoRecord(
        chapterId = chapter.chapterId,
        label = label,
        previousMarkdown = chapter.markdownContent,
        nextMarkdown = nextMarkdown,
      )
    )
    val updated = chapter.copy(markdownContent = nextMarkdown, updatedAt = System.currentTimeMillis())
    _uiState.update {
      it.copy(
        activeChapter = updated,
        canUndo = undoStack.canUndo(),
        statusBanner = "已落稿：$label（支持随时撤销）",
      )
    }
    persistChapterAsync(updated)
  }

  // --- Resumable Two-Stage Pipeline + ForegroundService (Pillar 1) ---

  fun runChapterDraftPipeline(summaryPrompt: String, simulateReviewFailure: Boolean) {
    val st = _uiState.value
    val bookId = st.activeBookId ?: return
    val chapter = st.activeChapter ?: return

    viewModelScope.launch {
      val executor = ResumablePipelineExecutor(
        stateStore = RoomPipelineStateStore(db.pipelineRunDao()),
        layout = layoutForBook(bookId),
      )
      QingJianPipelineService.updateProgress(appContext, "青简整章生成流水线", "阶段一：正在生成初稿…", 25)
      val result = executor.executeOrResume(
        bookId = bookId,
        chapterId = chapter.chapterId,
        taskType = "CHAPTER_DRAFT",
        promptSummary = summaryPrompt,
        onProgress = { stage, msg ->
          val pct = when (stage) {
            PipelineStage.STAGE_DRAFT -> 35
            PipelineStage.STAGE_REVIEW -> 75
            PipelineStage.STAGE_COMPLETED -> 100
          }
          QingJianPipelineService.updateProgress(appContext, "青简整章生成流水线", msg, pct)
        },
        generateDraft = {
          val ctx = assembleContextPrompt(chapter, centerBlockIdx = 0)
          val resp = runAiCall(
            taskLabel = "整章初稿生成",
            systemPrompt = "你是长篇中文小说主笔，请根据章节摘要与前文交接稿撰写完整章节初稿。\n${st.skillsState.buildSystemPromptDirectives()}",
            userPrompt = "$ctx\n\n章节标题：${chapter.title}\n本章摘要：\n$summaryPrompt",
          )
          resp.text
        },
        runReview = { draftText ->
          if (simulateReviewFailure) {
            throw IllegalStateException("模拟复审阶段网络超时（初稿已落盘，点击「从失败处继续」将复用已存初稿）")
          }
          runAutomaticReviewStage(draftText)
        },
      )
      QingJianPipelineService.stop(appContext)

      if (result.run.status == "COMPLETED" && result.draftText != null) {
        _uiState.update {
          it.copy(
            fullScreenDiff = FullScreenDiffProposal(
              kind = FullScreenDiffKind.FULL_CHAPTER_DRAFT,
              title = "${chapter.title} · 整章初稿与复审结果",
              originalText = chapter.markdownContent,
              proposedText = result.draftText!!,
              reviewReport = result.reviewReport,
            ),
            statusBanner = "两阶段流水线已完成，请在大屏 Diff 中审批落稿。",
          )
        }
      } else {
        _uiState.update {
          it.copy(statusBanner = result.run.errorMessage ?: "流水线中断，可从失败处继续。")
        }
      }
    }
  }

  fun resumeFailedPipeline(runId: String) {
    val st = _uiState.value
    val bookId = st.activeBookId ?: return
    val chapter = st.activeChapter ?: return

    viewModelScope.launch {
      val executor = ResumablePipelineExecutor(
        stateStore = RoomPipelineStateStore(db.pipelineRunDao()),
        layout = layoutForBook(bookId),
      )
      QingJianPipelineService.updateProgress(appContext, "青简断点续跑", "复用已落盘初稿，仅重跑复审阶段…", 70)
      val result = executor.executeOrResume(
        runId = runId,
        bookId = bookId,
        chapterId = chapter.chapterId,
        taskType = "CHAPTER_DRAFT",
        promptSummary = st.activePipelineRun?.promptSummary.orEmpty(),
        generateDraft = { error("断点续跑不应重新执行 STAGE_DRAFT") },
        runReview = { persistedDraft -> runAutomaticReviewStage(persistedDraft) },
      )
      QingJianPipelineService.stop(appContext)

      if (result.draftText != null && result.run.status == "COMPLETED") {
        _uiState.update {
          it.copy(
            fullScreenDiff = FullScreenDiffProposal(
              kind = FullScreenDiffKind.FULL_CHAPTER_DRAFT,
              title = "${chapter.title} · 断点续跑复审完成（已复用初稿）",
              originalText = chapter.markdownContent,
              proposedText = result.draftText!!,
              reviewReport = "已跳过重复生成并复用已存初稿 · ${result.reviewReport.orEmpty()}",
            ),
            statusBanner = "断点续跑成功：直接复用已存初稿完成复审。",
          )
        }
      }
    }
  }

  private suspend fun runAutomaticReviewStage(draftText: String): String {
    val approvedScripts = _uiState.value.skillsState.scripts.filter { it.isApprovedForCurrentSource() }
    val scriptNotes = approvedScripts.mapNotNull { script ->
      runCatching { jsSandbox.runApprovedScript(script, draftText).summaryForAi }.getOrNull()
    }
    val wordLen = draftText.count { !it.isWhitespace() }
    return buildString {
      append("自动复审完成：成稿 $wordLen 字，Markdown 结构检查通过。")
      if (scriptNotes.isNotEmpty()) {
        append(" 隔离沙箱报告：")
        append(scriptNotes.joinToString(" | "))
      }
    }
  }

  // --- Finalize Chapter & Handoff Brief (Pillar 2) ---

  fun finalizeChapterAndGenerateHandoff() {
    val st = _uiState.value
    val bookId = st.activeBookId ?: return
    val chapter = st.activeChapter ?: return

    viewModelScope.launch(Dispatchers.IO) {
      QingJianPipelineService.updateProgress(appContext, "青简定稿交接", "并发 Subagent 正在提炼事实、经验与伏笔…", 50)
      val finalized = chapter.copy(status = ChapterStatus.FINALIZED, updatedAt = System.currentTimeMillis())
      saveChapterToRepo(layoutForBook(bookId), finalized)

      val likedSnippets = finalized.toBlocks().filter { it.vote == FeedbackVote.UP }.map { "沿用获赞段落写法：「${it.markdown.take(26)}…」" }
      val dislikedSnippets = finalized.toBlocks().filter { it.vote == FeedbackVote.DOWN }.map { "规避踩点段落笔病：「${it.markdown.take(26)}…」" }

      // Concurrent subagents: 1) Handoff Brief, 2) Lore Candidates
      val results = etaRuntime.runSubagentsConcurrently(
        listOf(
          suspend {
            HandoffBrief(
              chapterId = finalized.chapterId,
              chapterTitle = finalized.title,
              happenedFacts = listOf(
                "在「${finalized.title}」中，核心人物完成关键信息交锋，正文已定稿（${finalized.wordCount()} 字）。",
                finalized.toBlocks().firstOrNull { !it.markdown.startsWith("#") }?.markdown?.take(48)?.let { "关键开场事件：$it…" }
                  ?: "已确认本章场景时序与在场知情人物。",
              ),
              writingLessons = (likedSnippets + dislikedSnippets).ifEmpty {
                listOf("保持克制冷峻的动作细节，继续禁用段尾总结腔。")
              },
              nextChapterHooks = listOf(
                "承接本章结尾悬而未决的对峙疑问。",
                "回应未回收伏笔并推进守塔司旧档真相。",
              ),
              nextDirections = listOf(
                HandoffDirection(
                  title = "路线一：密室结盟（和解查案）",
                  premise = "沈砚与顾衡暂时搁置旧怨，联手比对青铜印锉痕，查明内鬼去向。",
                  tension = "双方各藏底牌，信任随时破裂",
                ),
                HandoffDirection(
                  title = "路线二：当场翻脸（孤身潜逃）",
                  premise = "沈砚识破顾衡拖延之计，抢先携残诏从暗阁通风井脱身，触发全塔戒严。",
                  tension = "步步追杀，信息全面封锁",
                ),
              ),
            )
          },
          suspend {
            extractCandidatesFromChapter(finalized)
          },
        )
      )
      QingJianPipelineService.stop(appContext)

      val brief = results[0] as HandoffBrief
      @Suppress("UNCHECKED_CAST")
      val extractedCandidates = results[1] as List<LoreExtractionCandidate>

      val layout = layoutForBook(bookId)
      writeJson(layout.handoffFile(finalized.chapterId), brief, HandoffBrief::class.java)
      val mergedCandidates = (extractedCandidates + _uiState.value.loreCandidates).distinctBy { "${it.chapterId}:${it.title}" }
      writeListJson(layout.loreCandidatesFile, mergedCandidates, LoreExtractionCandidate::class.java)

      // Add alternative directions into Plot Planning (strictly isolated from confirmed facts!)
      val newPlans = brief.nextDirections.map { dir ->
        PlotPlanIdea(
          title = dir.title,
          sourceChapterId = finalized.chapterId,
          premise = dir.premise,
          keyBeats = listOf(dir.tension, "承接「${finalized.title}」交接铺垫"),
          branchTag = "交接备选走向",
        )
      }
      val mergedPlans = (newPlans + _uiState.value.plotPlans).distinctBy { it.title }
      writeListJson(layout.plotPlansFile, mergedPlans, PlotPlanIdea::class.java)

      gitRepoForBook(bookId).commitAll("定稿并生成交接稿：${finalized.title}")
      reindexBook(bookId)
      refreshGitSnapshot(bookId)

      val allHandoffs = loadAllHandoffsFromDisk(layout)
      _uiState.update {
        it.copy(
          activeChapter = finalized,
          handoffs = allHandoffs,
          loreCandidates = mergedCandidates,
          plotPlans = mergedPlans,
          statusBanner = "已定稿「${finalized.title}」，交接稿与 ${extractedCandidates.size} 条待审资料已生成。",
        )
      }
    }
  }

  fun saveEditedHandoff(brief: HandoffBrief) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val layout = layoutForBook(bookId)
      writeJson(layout.handoffFile(brief.chapterId), brief, HandoffBrief::class.java)
      val allHandoffs = loadAllHandoffsFromDisk(layout)
      _uiState.update { it.copy(handoffs = allHandoffs, statusBanner = "已保存定稿交接稿修改") }
    }
  }

  fun generateSummaryForDirection(direction: HandoffDirection, onResult: (String) -> Unit) {
    viewModelScope.launch {
      val resp = runAiCall(
        taskLabel = "生成下一章摘要",
        systemPrompt = "根据上章交接稿与选定走向，生成 4 点结构清晰的下一章分镜摘要。",
        userPrompt = "走向：${direction.title}\n前提：${direction.premise}\n张力：${direction.tension}",
      )
      onResult(resp.text)
    }
  }

  fun createNextChapterFromDirection(direction: HandoffDirection, confirmedSummary: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val currentVol = _uiState.value.activeChapter?.volumeTitle ?: "第一卷"
    viewModelScope.launch(Dispatchers.IO) {
      val nextOrder = (_uiState.value.chapters.maxOfOrNull { it.chapterOrder } ?: 0) + 1
      val chId = "ch_%03d".format(nextOrder)
      val cleanTitle = "第${nextOrder}章 ${direction.title.substringAfter("：", direction.title).take(10)}"
      val doc = ChapterDocument(
        chapterId = chId,
        bookId = bookId,
        volumeTitle = currentVol,
        title = cleanTitle,
        order = nextOrder,
        summary = confirmedSummary,
        markdownContent = "## $cleanTitle\n\n（已锁定章节摘要，正在启动两阶段整章生成流水线…）",
      )
      saveChapterToRepo(layoutForBook(bookId), doc)
      reindexBook(bookId)
      _uiState.update { it.copy(activeChapter = doc, destination = MainDestination.EDITOR) }
      runChapterDraftPipeline(confirmedSummary, simulateReviewFailure = false)
    }
  }

  // --- Plot Workbench, 5-Stage Foreshadows, Causal Graph & Worldbook (Pillars 3 & 4) ---

  fun extractLoreFromActiveChapter() {
    val bookId = _uiState.value.activeBookId ?: return
    val ch = _uiState.value.activeChapter ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val extracted = extractCandidatesFromChapter(ch)
      val merged = (extracted + _uiState.value.loreCandidates).distinctBy { "${it.chapterId}:${it.title}" }
      writeListJson(layoutForBook(bookId).loreCandidatesFile, merged, LoreExtractionCandidate::class.java)
      _uiState.update { it.copy(loreCandidates = merged, statusBanner = "已从「${ch.title}」提取 ${extracted.size} 条带原文锚点候选") }
    }
  }

  fun approveLoreCandidate(candidateId: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val cand = _uiState.value.loreCandidates.firstOrNull { it.id == candidateId } ?: return
    val newEntry = LoreEntry(
      category = cand.category,
      title = cand.title,
      content = cand.summary,
      sourceChapterId = cand.chapterId,
      sourceBlockId = cand.blockId,
      sourceExcerpt = cand.verbatimExcerpt,
    )
    val nextLore = listOf(newEntry) + _uiState.value.loreEntries
    val nextCands = _uiState.value.loreCandidates.filterNot { it.id == candidateId }
    _uiState.update { it.copy(loreEntries = nextLore, loreCandidates = nextCands, statusBanner = "已审阅入库：${cand.title}") }
    viewModelScope.launch(Dispatchers.IO) {
      val layout = layoutForBook(bookId)
      writeListJson(layout.loreEntitiesFile, nextLore, LoreEntry::class.java)
      writeListJson(layout.loreCandidatesFile, nextCands, LoreExtractionCandidate::class.java)
    }
  }

  fun rejectLoreCandidate(candidateId: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val nextCands = _uiState.value.loreCandidates.filterNot { it.id == candidateId }
    _uiState.update { it.copy(loreCandidates = nextCands) }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).loreCandidatesFile, nextCands, LoreExtractionCandidate::class.java)
    }
  }

  fun createForeshadow(title: String, desc: String, stage: ForeshadowStage, excerpt: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val ch = _uiState.value.activeChapter ?: return
    val blkId = ch.toBlocks().firstOrNull()?.id ?: "blk_${ch.chapterId}_1"
    val anchor = ForeshadowAnchor(
      stage = stage,
      chapterId = ch.chapterId,
      chapterTitle = ch.title,
      blockId = blkId,
      excerpt = excerpt,
    )
    val thread = ForeshadowThread(title = title, description = desc).withAddedAnchor(anchor)
    val updated = listOf(thread) + _uiState.value.foreshadows
    persistForeshadows(bookId, updated)
  }

  fun addForeshadowAnchor(foreshadowId: String, stage: ForeshadowStage, excerpt: String, note: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val ch = _uiState.value.activeChapter ?: return
    val blkId = ch.toBlocks().firstOrNull()?.id ?: "blk_${ch.chapterId}_1"
    attachForeshadowToBlock(foreshadowId, "", stage, blkId, excerpt, note)
  }

  fun attachForeshadowToBlock(
    foreshadowId: String?,
    newTitle: String,
    stage: ForeshadowStage,
    blockId: String,
    excerpt: String,
    note: String = "",
  ) {
    val bookId = _uiState.value.activeBookId ?: return
    val ch = _uiState.value.activeChapter ?: return
    val anchor = ForeshadowAnchor(
      stage = stage,
      chapterId = ch.chapterId,
      chapterTitle = ch.title,
      blockId = blockId,
      excerpt = excerpt,
      note = note,
    )
    val current = _uiState.value.foreshadows
    val updated = if (foreshadowId != null && current.any { it.id == foreshadowId }) {
      current.map { if (it.id == foreshadowId) it.withAddedAnchor(anchor) else it }
    } else {
      val t = ForeshadowThread(
        title = newTitle.ifBlank { "未命名伏笔" },
        description = "绑定自 ${ch.title}",
      ).withAddedAnchor(anchor)
      listOf(t) + current
    }
    persistForeshadows(bookId, updated)
    val statusMsg = if (stage == ForeshadowStage.PAYOFF) "已添加「回收」锚点，该伏笔自动置为已回收。" else "已绑定「${stage.label}」伏笔锚点。"
    _uiState.update { it.copy(statusBanner = statusMsg) }
  }

  private fun persistForeshadows(bookId: String, list: List<ForeshadowThread>) {
    _uiState.update { it.copy(foreshadows = list) }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).foreshadowsFile, list, ForeshadowThread::class.java)
      val flatEntities = list.flatMap { thread ->
        thread.anchors.map { a ->
          ForeshadowAnchorEntity(
            id = a.id,
            bookId = bookId,
            foreshadowId = thread.id,
            foreshadowTitle = thread.title,
            stage = a.stage.name,
            chapterId = a.chapterId,
            blockId = a.blockId,
            excerpt = a.excerpt,
            resolved = thread.isResolved,
            createdAt = a.createdAt,
          )
        }
      }
      db.foreshadowIndexDao().clearForBook(bookId)
      db.foreshadowIndexDao().upsertAll(flatEntities)
    }
  }

  fun confirmCausalEdge(edgeId: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val updated = _uiState.value.causalGraph.copy(
      edges = _uiState.value.causalGraph.edges.map { if (it.id == edgeId) it.copy(confirmed = true) else it }
    )
    persistCausalGraph(bookId, updated, "已人工确认因果关系（虚线转为实线）")
  }

  fun rejectCausalEdge(edgeId: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val updated = _uiState.value.causalGraph.copy(
      edges = _uiState.value.causalGraph.edges.filterNot { it.id == edgeId }
    )
    persistCausalGraph(bookId, updated, "已移除未确认的 AI 因果建议")
  }

  private fun persistCausalGraph(bookId: String, graph: CausalGraphData, notice: String) {
    _uiState.update { it.copy(causalGraph = graph, statusBanner = notice) }
    viewModelScope.launch(Dispatchers.IO) {
      writeJson(layoutForBook(bookId).causalGraphFile, graph, CausalGraphData::class.java)
      db.causalEdgeDao().clearForBook(bookId)
      db.causalEdgeDao().upsertAll(
        graph.edges.map {
          CausalEdgeEntity(it.id, bookId, it.fromNodeId, it.toNodeId, it.relationLabel, it.confirmed, it.aiSuggested)
        }
      )
    }
  }

  fun addPlotPlan(title: String, premise: String, beats: List<String>) {
    val bookId = _uiState.value.activeBookId ?: return
    val plan = PlotPlanIdea(
      title = title,
      sourceChapterId = _uiState.value.activeChapter?.chapterId.orEmpty(),
      premise = premise,
      keyBeats = beats,
    )
    val next = listOf(plan) + _uiState.value.plotPlans
    _uiState.update { it.copy(plotPlans = next, statusBanner = "已保存剧情构思（独立于定稿事实）") }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).plotPlansFile, next, PlotPlanIdea::class.java)
    }
  }

  fun createChapterFromPlotPlan(plan: PlotPlanIdea) {
    val dir = HandoffDirection(
      title = plan.title,
      premise = plan.premise,
      tension = plan.keyBeats.firstOrNull().orEmpty(),
    )
    generateSummaryForDirection(dir) { summary ->
      createNextChapterFromDirection(dir, summary)
    }
  }

  fun upsertWorldbookEntry(entry: WorldbookEntry) {
    val bookId = _uiState.value.activeBookId ?: return
    val next = listOf(entry) + _uiState.value.worldbookEntries.filterNot { it.id == entry.id }
    _uiState.update { it.copy(worldbookEntries = next, statusBanner = "已保存世界书条目：${entry.title}") }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).worldbookEntriesFile, next, WorldbookEntry::class.java)
    }
  }

  fun toggleWorldbookEntry(entryId: String, enabled: Boolean) {
    val bookId = _uiState.value.activeBookId ?: return
    val next = _uiState.value.worldbookEntries.map { if (it.id == entryId) it.copy(enabled = enabled) else it }
    _uiState.update { it.copy(worldbookEntries = next) }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).worldbookEntriesFile, next, WorldbookEntry::class.java)
    }
  }

  fun updateWorldbookBudget(budget: Int) {
    _uiState.update { it.copy(contextConfig = it.contextConfig.copy(worldbookCharBudget = budget)) }
  }

  fun updateContextConfig(cfg: ContextWindowConfig) {
    _uiState.update { it.copy(contextConfig = cfg, statusBanner = "已更新上下文装配策略") }
  }

  fun importTavernWorldbookJson(json: String): Result<Int> = runCatching {
    val imported = WorldbookEngine.importTavernWorldbookJson(json)
    val bookId = _uiState.value.activeBookId ?: error("当前未打开书籍")
    val next = imported + _uiState.value.worldbookEntries
    _uiState.update { it.copy(worldbookEntries = next) }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).worldbookEntriesFile, next, WorldbookEntry::class.java)
    }
    imported.size
  }

  // --- Writing Skills & Isolated JS Sandbox (Pillar 5) ---

  fun toggleBuiltInSkill(skill: BuiltInSkillType, enabled: Boolean) {
    val bookId = _uiState.value.activeBookId ?: return
    val cur = _uiState.value.skillsState
    val nextSet = if (enabled) cur.enabledBuiltIn + skill else cur.enabledBuiltIn - skill
    persistSkillsState(bookId, cur.copy(enabledBuiltIn = nextSet))
  }

  fun runStyleDistillationWizard(sampleTitle: String, sampleText: String, correctionRules: List<String>) {
    val bookId = _uiState.value.activeBookId ?: return
    val profile = StyleDistillationAnalyzer.analyze(sampleTitle, sampleText)
    val nextSkills = _uiState.value.skillsState.copy(
      styleProfile = profile,
      customCorrectionRules = correctionRules,
    )
    persistSkillsState(bookId, nextSkills)
    val manifest = _uiState.value.activeManifest?.copy(
      aiWizardCompleted = true,
      referenceStyleExcerpt = sampleText.take(600),
      correctionRules = correctionRules,
    )
    if (manifest != null) {
      _uiState.update { it.copy(activeManifest = manifest, statusBanner = "文风蒸馏完成：${profile.pacingSignature}") }
      viewModelScope.launch(Dispatchers.IO) {
        writeJson(layoutForBook(bookId).bookManifestFile, manifest, BookManifest::class.java)
        reindexBook(bookId)
      }
    }
  }

  fun importSkillPackage(pkg: ImportedSkillPackage) {
    val bookId = _uiState.value.activeBookId ?: return
    val cur = _uiState.value.skillsState
    persistSkillsState(bookId, cur.copy(importedPackages = listOf(pkg) + cur.importedPackages))
    _uiState.update { it.copy(statusBanner = "已导入技能包：${pkg.name}") }
  }

  fun toggleImportedSkillPackage(pkgId: String, enabled: Boolean) {
    val bookId = _uiState.value.activeBookId ?: return
    val cur = _uiState.value.skillsState
    persistSkillsState(
      bookId,
      cur.copy(importedPackages = cur.importedPackages.map { if (it.id == pkgId) it.copy(enabled = enabled) else it }),
    )
  }

  fun addSandboxJsScript(name: String, description: String, code: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val cur = _uiState.value.skillsState
    val script = SandboxJsScript(name = name, description = description, sourceCode = code)
    persistSkillsState(bookId, cur.copy(scripts = listOf(script) + cur.scripts))
    _uiState.update { it.copy(statusBanner = "已添加脚本「$name」，需经作者审核批准后方可执行。") }
  }

  fun approveSandboxScript(scriptId: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val cur = _uiState.value.skillsState
    val nextScripts = cur.scripts.map { if (it.id == scriptId) jsSandbox.approveScript(it) else it }
    persistSkillsState(bookId, cur.copy(scripts = nextScripts))
    _uiState.update { it.copy(sandboxError = null, statusBanner = "已批准该脚本在隔离 JS 沙箱中运行。") }
  }

  fun revokeSandboxScript(scriptId: String) {
    val bookId = _uiState.value.activeBookId ?: return
    val cur = _uiState.value.skillsState
    val nextScripts = cur.scripts.map { if (it.id == scriptId) jsSandbox.revokeApproval(it) else it }
    persistSkillsState(bookId, cur.copy(scripts = nextScripts))
    _uiState.update { it.copy(statusBanner = "已撤销脚本执行权限。") }
  }

  fun runSandboxScriptOnActiveChapter(scriptId: String) {
    val script = _uiState.value.skillsState.scripts.firstOrNull { it.id == scriptId } ?: return
    val chapterText = _uiState.value.activeChapter?.markdownContent.orEmpty()
    viewModelScope.launch {
      runCatching { jsSandbox.runApprovedScript(script, chapterText) }
        .onSuccess { report ->
          _uiState.update {
            it.copy(
              lastScriptReport = report,
              sandboxError = null,
              statusBanner = "沙箱执行完成：检出 ${report.issues.size} 条待改项（${report.durationMs}ms）",
            )
          }
        }
        .onFailure { err ->
          _uiState.update { it.copy(sandboxError = err.message ?: "沙箱执行受阻") }
        }
    }
  }

  fun sendSandboxReportToAiRevision(report: ScriptExecutionReport) {
    val chapter = _uiState.value.activeChapter ?: return
    val targetBlock = chapter.toBlocks().firstOrNull { blk ->
      report.issues.any { issue -> issue.excerpt.isNotBlank() && blk.markdown.contains(issue.excerpt.take(6)) }
    } ?: chapter.toBlocks().firstOrNull() ?: return

    _uiState.update { it.copy(destination = MainDestination.EDITOR, pendingJumpBlockId = targetBlock.id) }
    requestSingleBlockRewrite(
      blockId = targetBlock.id,
      instruction = "请严格依据隔离 JS 沙箱检查报告进行针对性修订：\n${report.summaryForAi}",
    )
  }

  private fun persistSkillsState(bookId: String, state: BookSkillsState) {
    _uiState.update { it.copy(skillsState = state) }
    viewModelScope.launch(Dispatchers.IO) {
      writeJson(layoutForBook(bookId).skillsConfigFile, state, BookSkillsState::class.java)
    }
  }

  // --- Roleplay Rehearsal (Pillar 4) ---

  fun selectRpCard(cardId: String) {
    val card = _uiState.value.rpCards.firstOrNull { it.id == cardId } ?: return
    val intro = if (card.firstMessage.isNotBlank()) {
      listOf(RpTurnMessage("rp_init_${card.id}", false, card.name, card.firstMessage))
    } else emptyList()
    _uiState.update { it.copy(selectedRpCardId = cardId, rpMessages = intro) }
  }

  fun importRpCard(card: TavernCharacterCard) {
    val bookId = _uiState.value.activeBookId ?: return
    val next = listOf(card) + _uiState.value.rpCards
    _uiState.update {
      it.copy(
        rpCards = next,
        selectedRpCardId = card.id,
        rpMessages = if (card.firstMessage.isNotBlank()) listOf(RpTurnMessage("rp_init_${card.id}", false, card.name, card.firstMessage)) else emptyList(),
        statusBanner = "已导入角色卡：${card.name} (${card.specVersion})",
      )
    }
    viewModelScope.launch(Dispatchers.IO) {
      writeListJson(layoutForBook(bookId).rpCardsFile, next, TavernCharacterCard::class.java)
    }
  }

  fun projectLoreCharacterToRpCard(lore: LoreEntry) {
    val card = TavernCharacterCard(
      name = lore.title,
      description = lore.content,
      personality = "忠于《${_uiState.value.activeManifest?.title.orEmpty()}》既有定稿设定",
      scenario = "在当前章节情境中与作者对戏试口吻",
      firstMessage = "（${lore.title} 微微侧目，静待来人开口）",
      tags = listOf("本书人物投射"),
      linkedLoreId = lore.id,
    )
    importRpCard(card)
  }

  fun sendRpMessage(cardId: String, authorPrompt: String) {
    val st = _uiState.value
    val card = st.rpCards.firstOrNull { it.id == cardId } ?: return
    val authorMsg = RpTurnMessage("rp_u_${System.currentTimeMillis()}", true, "作者试戏", authorPrompt)
    val corpus = (st.rpMessages.takeLast(4).joinToString("\n") { it.content } + "\n" + authorPrompt)
    val wbHit = WorldbookEngine.evaluate(st.worldbookEntries, corpus, charBudget = 1800)

    _uiState.update { it.copy(rpMessages = it.rpMessages + authorMsg, isRpGenerating = true) }
    viewModelScope.launch {
      val sys = buildString {
        appendLine("扮演角色：${card.name}")
        appendLine("人设：${card.description}")
        if (card.personality.isNotBlank()) appendLine("性格：${card.personality}")
        if (wbHit.injectedText.isNotBlank()) {
          appendLine("共享小说世界书设定：")
          appendLine(wbHit.injectedText)
        }
      }
      val resp = runAiCall("角色扮演试戏", sys, authorPrompt)
      val reply = RpTurnMessage(
        id = "rp_a_${System.currentTimeMillis()}",
        isAuthor = false,
        speaker = card.name,
        content = resp.text,
        triggeredWorldbookTitles = wbHit.matchedTraces.filter { it.includedInBudget }.map { it.entry.title },
      )
      _uiState.update { it.copy(rpMessages = it.rpMessages + reply, isRpGenerating = false) }
    }
  }

  fun insertRpDialogueIntoActiveChapter(dialogueText: String) {
    val ch = _uiState.value.activeChapter ?: return
    val appended = ch.markdownContent.trimEnd() + "\n\n" + dialogueText.trim()
    pushUndoAndApply(ch, appended, "回写试戏对白至草稿")
    _uiState.update { it.copy(destination = MainDestination.EDITOR) }
  }

  // --- Git Version Control & Mandatory Anti-Loss Restore Points (Pillar 6) ---

  fun gitCommitAll(message: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val commit = gitRepoForBook(bookId).commitAll(message)
      refreshGitSnapshot(bookId, "已提交版本 ${commit.shortHash}：${commit.message}")
    }
  }

  fun gitCreateBranch(branchName: String, startCommitHash: String?) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = gitRepoForBook(bookId).createBranchFromCommit(branchName, startCommitHash, switchImmediately = true)
      recordRestorePoint(bookId, res.safetyRestorePoint)
      reindexBook(bookId)
      openBook(bookId, switchToEditor = false)
      refreshGitSnapshot(bookId, "已自动建立防丢还原点「${res.safetyRestorePoint.label}」，并切换至新路线分支 ${res.targetBranch}")
    }
  }

  fun gitSwitchBranch(branchName: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = gitRepoForBook(bookId).switchBranchWithSafetyGuard(branchName)
      recordRestorePoint(bookId, res.safetyRestorePoint)
      reindexBook(bookId)
      openBook(bookId, switchToEditor = false)
      refreshGitSnapshot(bookId, "防丢保护已触发：已自动备份当前工作区，并切换至路线 ${res.targetBranch}")
    }
  }

  fun gitInspectCommit(commitHash: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val diffs = gitRepoForBook(bookId).diffCommitsFileByFile(commitHash)
      _uiState.update { st ->
        st.copy(
          gitSnapshot = st.gitSnapshot.copy(
            selectedCommitHash = commitHash,
            fileDiffs = diffs,
          )
        )
      }
    }
  }

  fun gitLoadHistoricalCommit(commitHash: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val res = gitRepoForBook(bookId).loadHistoricalCommitWithSafetyGuard(commitHash)
      recordRestorePoint(bookId, res.safetyRestorePoint)
      reindexBook(bookId)
      openBook(bookId, switchToEditor = false)
      refreshGitSnapshot(bookId, "防丢保护已触发：已自动创建还原点，并安全载入历史提交 ${commitHash.take(7)}")
    }
  }

  fun createManualRestorePoint(label: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val snap = gitRepoForBook(bookId).createFullBookRestorePoint(label, reason = "MANUAL")
      recordRestorePoint(bookId, snap)
      refreshGitSnapshot(bookId, "已创建全书还原点：${snap.label}")
    }
  }

  fun rollbackRestorePoint(zipPath: String) {
    val bookId = _uiState.value.activeBookId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val preBackup = gitRepoForBook(bookId).restoreFromSnapshot(zipPath)
      recordRestorePoint(bookId, preBackup)
      reindexBook(bookId)
      openBook(bookId, switchToEditor = false)
      refreshGitSnapshot(bookId, "已恢复至选定还原点（回滚前已自动留存备份）")
    }
  }

  private suspend fun recordRestorePoint(bookId: String, snap: com.qingjian.core.git.RestorePointSnapshot) {
    db.restorePointDao().insert(
      RestorePointEntity(
        id = snap.id,
        bookId = bookId,
        label = snap.label,
        reason = snap.reason,
        branchName = snap.branchName,
        commitHash = snap.commitHash,
        snapshotZipPath = snap.snapshotZipPath,
        createdAt = snap.createdAt,
      )
    )
  }

  private suspend fun refreshGitSnapshot(bookId: String, safetyNotice: String? = null) {
    val git = gitRepoForBook(bookId)
    val branch = runCatching { git.currentBranch() }.getOrDefault("main")
    val branches = runCatching { git.listBranches() }.getOrDefault(emptyList())
    val commits = runCatching { git.listCommits() }.getOrDefault(emptyList())
    _uiState.update { st ->
      st.copy(
        gitSnapshot = st.gitSnapshot.copy(
          currentBranch = branch,
          branches = branches,
          commits = commits,
          lastSafetyNotice = safetyNotice ?: st.gitSnapshot.lastSafetyNotice,
        )
      )
    }
  }

  // --- Shared Context Assembly & AI Execution Helpers ---

  private fun assembleContextPrompt(chapter: ChapterDocument, centerBlockIdx: Int): String {
    val st = _uiState.value
    val cfg = st.contextConfig
    val blocks = chapter.toBlocks()
    val beforeSlice = blocks.subList((centerBlockIdx - cfg.neighborParagraphsBefore).coerceAtLeast(0), centerBlockIdx.coerceAtLeast(0))
    val afterSlice = if (centerBlockIdx + 1 < blocks.size) {
      blocks.subList(centerBlockIdx + 1, (centerBlockIdx + 1 + cfg.neighborParagraphsAfter).coerceAtMost(blocks.size))
    } else emptyList()

    val neighborCorpus = (beforeSlice + afterSlice).joinToString("\n") { it.markdown }
    val wbResult = WorldbookEngine.evaluate(
      entries = st.worldbookEntries,
      scanText = chapter.markdownContent + "\n" + neighborCorpus,
      charBudget = cfg.worldbookCharBudget,
    )

    val segments = buildList {
      if (cfg.includeOutline && chapter.summary.isNotBlank()) {
        add(ContextCompressionEngine.ContextSegment("本章摘要大纲", chapter.summary, priority = 90))
      }
      if (cfg.includeFinalizedHandoff && st.handoffs.isNotEmpty()) {
        val latest = st.handoffs.last()
        add(
          ContextCompressionEngine.ContextSegment(
            "上章定稿交接 (${latest.chapterTitle})",
            (latest.happenedFacts + latest.nextChapterHooks).joinToString("\n"),
            priority = 85,
          )
        )
      }
      if (wbResult.injectedText.isNotBlank()) {
        add(ContextCompressionEngine.ContextSegment("命中世界书设定", wbResult.injectedText, priority = 80))
      }
      if (cfg.includeAuthorFeedback) {
        val fb = chapter.toBlocks().mapNotNull { blk ->
          when (blk.vote) {
            FeedbackVote.UP -> "作者赞赏：${blk.markdown.take(36)}"
            FeedbackVote.DOWN -> "作者踩点需改：${blk.markdown.take(36)}"
            FeedbackVote.NONE -> null
          }
        }.joinToString("\n")
        if (fb.isNotBlank()) {
          add(ContextCompressionEngine.ContextSegment("作者赞踩反馈", fb, priority = 75))
        }
      }
      if (neighborCorpus.isNotBlank()) {
        add(ContextCompressionEngine.ContextSegment("前后邻段上下文", neighborCorpus, priority = 95, required = true))
      }
    }

    return ContextCompressionEngine.compressAndAssemble(segments, cfg.totalContextCharBudget).assembledPrompt
  }

  private suspend fun runAiCall(taskLabel: String, systemPrompt: String, userPrompt: String) =
    withContext(Dispatchers.IO) {
      val profile = activeProfile()
      val response = etaRuntime.complete(
        profile = profile,
        request = AiCompletionRequest(
          systemPrompt = systemPrompt,
          messages = listOf(ChatMessage("user", userPrompt)),
          taskLabel = taskLabel,
        ),
        allowLocalRuleEngineWhenNoKey = true,
      )
      db.aiUsageDao().insert(
        AiUsageEntity(
          id = "use_${UUID.randomUUID().toString().take(8)}",
          bookId = _uiState.value.activeBookId.orEmpty(),
          providerName = response.providerUsed,
          profileLabel = profile.label,
          modelName = response.modelUsed,
          thinkingLevel = profile.thinkingLevel.label,
          promptTokens = response.promptTokens,
          completionTokens = response.completionTokens,
          latencyMs = response.latencyMs,
          timestamp = System.currentTimeMillis(),
        )
      )
      response
    }

  private fun extractCandidatesFromChapter(chapter: ChapterDocument): List<LoreExtractionCandidate> {
    val blocks = chapter.toBlocks()
    val result = mutableListOf<LoreExtractionCandidate>()
    blocks.forEach { blk ->
      val text = blk.markdown
      if (text.contains("白塔") && text.contains("禁令")) {
        result.add(
          LoreExtractionCandidate(
            category = LoreCategory.WORLD_RULE,
            title = "白塔子时禁令",
            summary = "子时之后严禁擅启诏匣或私入白塔三层暗阁。",
            chapterId = chapter.chapterId,
            chapterTitle = chapter.title,
            blockId = blk.id,
            verbatimExcerpt = text.take(56),
          )
        )
      }
      if (text.contains("青铜印")) {
        result.add(
          LoreExtractionCandidate(
            category = LoreCategory.EVENT,
            title = "青铜旧印锉痕现身",
            summary = "印钮边缘留有掌印人亲手磨去的半枚暗记，牵出三年前北境军报失窃旧案。",
            chapterId = chapter.chapterId,
            chapterTitle = chapter.title,
            blockId = blk.id,
            verbatimExcerpt = text.take(56),
          )
        )
      }
      if (blk.annotation.isNotBlank()) {
        result.add(
          LoreExtractionCandidate(
            category = LoreCategory.ANNOTATION,
            title = "作者批注归档",
            summary = blk.annotation,
            chapterId = chapter.chapterId,
            chapterTitle = chapter.title,
            blockId = blk.id,
            verbatimExcerpt = text.take(48),
          )
        )
      }
    }
    if (result.isEmpty() && blocks.isNotEmpty()) {
      val first = blocks.first()
      result.add(
        LoreExtractionCandidate(
          category = LoreCategory.SCENE,
          title = "${chapter.title} 核心场景",
          summary = "自动识别自本章正文的关键场景事实，请审阅后决定是否入库。",
          chapterId = chapter.chapterId,
          chapterTitle = chapter.title,
          blockId = first.id,
          verbatimExcerpt = first.markdown.take(56),
        )
      )
    }
    return result.distinctBy { "${it.category}:${it.title}" }
  }

  // --- Disk & Room Sync Helpers ---

  private fun persistChapterAsync(doc: ChapterDocument) {
    viewModelScope.launch(Dispatchers.IO) {
      val layout = layoutForBook(doc.bookId)
      saveChapterToRepo(layout, doc)
      reindexBook(doc.bookId)
    }
  }

  private data class ChapterMetaDisk(
    val chapterId: String,
    val volumeTitle: String,
    val title: String,
    val order: Int,
    val status: ChapterStatus,
    val summary: String,
    val blockFeedback: Map<String, FeedbackVote>,
    val blockAnnotations: Map<String, String>,
    val updatedAt: Long,
  )

  private fun saveChapterToRepo(layout: BookRepoLayout, doc: ChapterDocument) {
    layout.ensureDirectories()
    layout.chapterFile(doc.chapterId).writeText(doc.markdownContent)
    writeJson(
      layout.chapterMetaFile(doc.chapterId),
      ChapterMetaDisk(
        chapterId = doc.chapterId,
        volumeTitle = doc.volumeTitle,
        title = doc.title,
        order = doc.order,
        status = doc.status,
        summary = doc.summary,
        blockFeedback = doc.blockFeedback,
        blockAnnotations = doc.blockAnnotations,
        updatedAt = doc.updatedAt,
      ),
      ChapterMetaDisk::class.java,
    )
  }

  private fun loadChapterFromDisk(layout: BookRepoLayout, bookId: String, chapterId: String): ChapterDocument? {
    val mdFile = layout.chapterFile(chapterId)
    if (!mdFile.exists()) return null
    val meta = readJson(layout.chapterMetaFile(chapterId), ChapterMetaDisk::class.java)
    return ChapterDocument(
      chapterId = chapterId,
      bookId = bookId,
      volumeTitle = meta?.volumeTitle ?: "第一卷",
      title = meta?.title ?: chapterId,
      order = meta?.order ?: 1,
      status = meta?.status ?: ChapterStatus.DRAFT,
      summary = meta?.summary.orEmpty(),
      markdownContent = mdFile.readText(),
      blockFeedback = meta?.blockFeedback.orEmpty(),
      blockAnnotations = meta?.blockAnnotations.orEmpty(),
      updatedAt = meta?.updatedAt ?: mdFile.lastModified(),
    )
  }

  private fun loadAllChaptersFromDisk(layout: BookRepoLayout, bookId: String): List<ChapterDocument> {
    val files = layout.chaptersDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
    return files.mapNotNull { f ->
      loadChapterFromDisk(layout, bookId, f.nameWithoutExtension)
    }.sortedBy { it.order }
  }

  private fun loadAllHandoffsFromDisk(layout: BookRepoLayout): List<HandoffBrief> {
    val files = layout.handoffsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
    return files.mapNotNull { f -> readJson(f, HandoffBrief::class.java) }.sortedBy { it.chapterId }
  }

  private suspend fun reindexBook(bookId: String) {
    val layout = layoutForBook(bookId)
    val manifest = readJson(layout.bookManifestFile, BookManifest::class.java) ?: return
    val chapters = loadAllChaptersFromDisk(layout, bookId)
    val branch = runCatching { BookGitRepository(layout).currentBranch() }.getOrDefault("main")
    val totalWords = chapters.sumOf { it.wordCount() }

    db.bookIndexDao().upsert(
      BookIndexEntity(
        bookId = manifest.id,
        title = manifest.title,
        author = manifest.author,
        genre = manifest.genre,
        synopsis = manifest.synopsis,
        coverColorHex = manifest.coverColorHex,
        coverAccentHex = manifest.coverAccentHex,
        wordCountGoalPerChapter = manifest.wordCountGoalPerChapter,
        editorWidthDp = manifest.editorWidthDp,
        totalWords = totalWords,
        chapterCount = chapters.size,
        currentBranch = branch,
        repoPath = layout.repoRoot.absolutePath,
        aiWizardCompleted = manifest.aiWizardCompleted,
        updatedAt = System.currentTimeMillis(),
      )
    )
    db.chapterIndexDao().clearForBook(bookId)
    chapters.forEach { ch ->
      db.chapterIndexDao().upsert(
        ChapterIndexEntity(
          compositeId = "${bookId}_${ch.chapterId}",
          bookId = bookId,
          chapterId = ch.chapterId,
          volumeTitle = ch.volumeTitle,
          chapterOrder = ch.order,
          title = ch.title,
          status = ch.status.name,
          wordCount = ch.wordCount(),
          hasHandoff = layout.handoffFile(ch.chapterId).exists(),
          searchExcerpt = ch.markdownContent.replace("\n", " ").take(120),
          updatedAt = ch.updatedAt,
        )
      )
    }
  }

  private fun <T> writeJson(file: File, value: T, clazz: Class<T>) {
    file.parentFile?.mkdirs()
    file.writeText(moshi.adapter(clazz).indent("  ").toJson(value))
  }

  private fun <T> readJson(file: File, clazz: Class<T>): T? {
    if (!file.exists()) return null
    return runCatching { moshi.adapter(clazz).fromJson(file.readText()) }.getOrNull()
  }

  private fun <T> writeListJson(file: File, value: List<T>, itemClazz: Class<T>) {
    file.parentFile?.mkdirs()
    val type = Types.newParameterizedType(List::class.java, itemClazz)
    val adapter = moshi.adapter<List<T>>(type).indent("  ")
    file.writeText(adapter.toJson(value))
  }

  private fun <T> readListJson(file: File, itemClazz: Class<T>): List<T> {
    if (!file.exists()) return emptyList()
    val type = Types.newParameterizedType(List::class.java, itemClazz)
    val adapter = moshi.adapter<List<T>>(type)
    return runCatching { adapter.fromJson(file.readText()) }.getOrNull().orEmpty()
  }

  // --- Rich First-Launch Sample Novel Seeding (《白塔遗诏》) ---

  private suspend fun seedSampleBookIfEmpty() = withContext(Dispatchers.IO) {
    val existing = db.bookIndexDao().getBook("book_white_tower")
    if (existing != null) {
      openBook("book_white_tower", switchToEditor = false)
      return@withContext
    }

    val bookId = "book_white_tower"
    val layout = layoutForBook(bookId)
    val git = BookGitRepository(layout)
    layout.ensureDirectories()

    val manifest = BookManifest(
      id = bookId,
      title = "白塔遗诏",
      author = "林墨",
      genre = "悬疑古风",
      synopsis = "永熙十四年冬夜，一纸缺角青铜印与半卷白塔禁令，将沉寂三年的北境军报失窃旧案重新推上风口。",
      coverColorHex = 0xFF1A3A3A,
      coverAccentHex = 0xFF2B6E63,
      wordCountGoalPerChapter = 3000,
      editorWidthDp = 680,
      writingMode = WritingMode.HUMAN_LED,
      aiWizardCompleted = true,
    )
    writeJson(layout.bookManifestFile, manifest, BookManifest::class.java)

    val ch1 = ChapterDocument(
      chapterId = "ch_001",
      bookId = bookId,
      volumeTitle = "第一卷 雪夜白塔",
      title = "第一章 寒灯启塔",
      order = 1,
      status = ChapterStatus.FINALIZED,
      summary = "沈砚冒雪赴约登白塔，发现青铜药杵缺口与守塔司夜禁异常。",
      markdownContent = """
        |## 第一章 寒灯启塔
        |
        |永熙十四年腊月初七，雪粒打在白塔青灰色的檐瓦上，发出细密如盐的声响。沈砚提着一盏防风铜灯拾级而上，靴底踩碎石阶薄冰，回声在空荡的塔廊里荡开三层。
        |
        |> 白塔禁令：子时之后，凡未持司礼监勘合者不得踏足三层暗阁，违者由守塔司就地拿问。
        |
        |他在二层转角停步，指尖掠过墙龛里半截被雪风吹冷的青铜药杵。药杵底座缺了一角，断口平滑发亮，显然不是经年磨损，而是被人用细锉刻意抹去了编号。
        |
        |---
        |
        |「沈大人来得比约定早了一刻。」阴影里传来低缓的脚步声，守塔司副使顾衡未佩长刀，只捧着一只缠了黑绸的木匣，「你若此刻回头，还能赶上城门落锁前的最后一班更鼓。」
      """.trimMargin(),
      blockFeedback = mapOf("blk_ch_001_2" to FeedbackVote.UP),
      blockAnnotations = mapOf("blk_ch_001_4" to "关键物证：青铜药杵缺口需在第三章与旧印暗记呼应"),
    )

    val ch2 = ChapterDocument(
      chapterId = "ch_002",
      bookId = bookId,
      volumeTitle = "第一卷 雪夜白塔",
      title = "第二章 暗阁旧档",
      order = 2,
      status = ChapterStatus.DRAFT,
      summary = "沈砚与顾衡在三层暗阁核对守塔司旧档，围绕青铜印锉痕与白塔禁令展开试探。",
      markdownContent = """
        |## 第二章 暗阁旧档
        |
        |三层暗阁没有窗，只有穹顶垂下一束冷白的雪光，正正落在案台中央那枚缺了半角的青铜印上。沈砚解开大氅时，肩头冰碴滚落地砖缝隙，化开几道极浅的水痕。
        |
        |顾衡坐在灯影边缘，将一卷泛黄的守塔司旧档推过案心。他不禁微微抬眸，眼中闪过一丝难以察觉的试探，空气仿佛凝固了一般。
        |
        |「白塔禁令白纸黑字写着，子时后严禁启封诏匣。」顾衡指节轻叩匣盖，「三年前北境军报失窃那夜，值守白塔的正是你兄长。今夜你若执意开匣，便等于替旧案重新掀棺。」
        |
        |---
        |
        |沈砚没有先看旧档，而是俯身按住青铜印钮侧面的浅痕。那道锉痕的弧度，与二层药杵底座的缺口如出一辙——总而言之，当年动手的绝非外贼，而是手握塔钥的掌印人。
      """.trimMargin(),
      blockFeedback = mapOf(
        "blk_ch_002_2" to FeedbackVote.UP,
        "blk_ch_002_3" to FeedbackVote.DOWN,
      ),
      blockAnnotations = mapOf("blk_ch_002_3" to "本段含“不禁”“眼中闪过一丝”“空气仿佛凝固”，可用隔离 JS 沙箱破甲检查或双击局部改稿"),
    )

    saveChapterToRepo(layout, ch1)
    saveChapterToRepo(layout, ch2)

    val ch1Handoff = HandoffBrief(
      chapterId = "ch_001",
      chapterTitle = "第一章 寒灯启塔",
      happenedFacts = listOf(
        "永熙十四年腊月初七雪夜，沈砚持铜灯独自登白塔赴约。",
        "沈砚在二层发现被细锉磨去编号的青铜药杵（仅沈砚本人知情）。",
        "守塔司副使顾衡手捧黑绸木匣现身，暗示城门将闭，劝沈砚退走。",
      ),
      writingLessons = listOf(
        "沿用：雪声、薄冰、铜灯等低饱和冷感官细节，叙事克制有压迫感。",
        "避免：禁止出现“不禁”“顿时”“眼中闪过一丝”及段尾“总而言之”式总结腔。",
      ),
      nextChapterHooks = listOf(
        "黑绸木匣中究竟盛放何物？为何顾衡冒着违背白塔禁令的风险夜约沈砚？",
        "二层青铜药杵的锉痕将在三层与青铜旧印形成直接物证呼应。",
      ),
      nextDirections = listOf(
        HandoffDirection(
          title = "和解路线：暗阁对档结盟",
          premise = "沈砚亮出药杵缺口与青铜印比对，迫使顾衡吐露三年前失窃当夜的值守名单。",
          tension = "两人各怀目的结成脆弱同盟，门外监察使将至",
        ),
        HandoffDirection(
          title = "决裂路线：夺匣夜遁",
          premise = "沈砚判定木匣系诱饵，反手熄灯制住顾衡，独自破开三层通风暗道查证。",
          tension = "守塔司全面封塔，主角陷入孤立追捕",
        ),
      ),
    )
    writeJson(layout.handoffFile("ch_001"), ch1Handoff, HandoffBrief::class.java)

    val foreshadows = listOf(
      ForeshadowThread(
        id = "fs_bronze_notch",
        title = "青铜器上的半寸锉痕",
        description = "二层药杵与三层掌印人青铜印拥有完全一致的细锉磨痕，指向三年前北境军报失窃内鬼。",
        anchors = listOf(
          ForeshadowAnchor(
            stage = ForeshadowStage.PLANT,
            chapterId = "ch_001",
            chapterTitle = "第一章 寒灯启塔",
            blockId = "blk_ch_001_4",
            excerpt = "药杵底座缺了一角，断口平滑发亮，显然不是经年磨损",
            note = "初次埋设物证特征",
          ),
          ForeshadowAnchor(
            stage = ForeshadowStage.ECHO,
            chapterId = "ch_002",
            chapterTitle = "第二章 暗阁旧档",
            blockId = "blk_ch_002_6",
            excerpt = "那道锉痕的弧度，与二层药杵底座的缺口如出一辙",
            note = "二章呼应，尚未回收幕后真凶",
          ),
        ),
        isResolved = false,
      ),
    )
    writeListJson(layout.foreshadowsFile, foreshadows, ForeshadowThread::class.java)

    val lore = listOf(
      LoreEntry(
        id = "lore_shen_yan",
        category = LoreCategory.CHARACTER,
        title = "沈砚",
        content = "前刑部录事，沈氏旧案幸存者。习惯通过器物细微磨损推理真相，言语克制冷峻。",
        sourceChapterId = "ch_001",
        sourceBlockId = "blk_ch_001_2",
        sourceExcerpt = "沈砚提着一盏防风铜灯拾级而上",
      ),
      LoreEntry(
        id = "lore_gu_heng",
        category = LoreCategory.CHARACTER,
        title = "顾衡",
        content = "守塔司副使，执掌白塔夜巡与旧档封存。看似恪守塔规，实则暗查三年前北境军报失窃真相。",
        sourceChapterId = "ch_001",
        sourceBlockId = "blk_ch_001_6",
        sourceExcerpt = "守塔司副使顾衡未佩长刀，只捧着一只缠了黑绸的木匣",
      ),
      LoreEntry(
        id = "lore_white_tower",
        category = LoreCategory.SCENE,
        title = "白塔三层暗阁",
        content = "无窗穹顶密室，专储历年绝密诏匣与司礼监旧档，仅子时前凭勘合可入。",
        sourceChapterId = "ch_002",
        sourceBlockId = "blk_ch_002_2",
        sourceExcerpt = "三层暗阁没有窗，只有穹顶垂下一束冷白的雪光",
      ),
    )
    writeListJson(layout.loreEntitiesFile, lore, LoreEntry::class.java)

    val candidates = listOf(
      LoreExtractionCandidate(
        category = LoreCategory.EVENT,
        title = "北境军报失窃旧案",
        summary = "三年前失窃当夜由沈砚兄长值守白塔，如今旧档与青铜印锉痕重现疑云。",
        chapterId = "ch_002",
        chapterTitle = "第二章 暗阁旧档",
        blockId = "blk_ch_002_4",
        verbatimExcerpt = "三年前北境军报失窃那夜，值守白塔的正是你兄长",
      )
    )
    writeListJson(layout.loreCandidatesFile, candidates, LoreExtractionCandidate::class.java)

    val causalGraph = CausalGraphData(
      nodes = listOf(
        CausalNode("n1", "ch_001", "雪夜赴约登白塔", "沈砚持灯进入禁区", 40f, 45f),
        CausalNode("n2", "ch_001", "发现药杵锉痕", "二层墙龛现人为磨痕", 260f, 45f),
        CausalNode("n3", "ch_002", "顾衡出示黑绸木匣", "暗阁核验守塔司旧档", 150f, 165f),
      ),
      edges = listOf(
        CausalEdge("e1", "n1", "n2", "沿阶上行途经二层墙龛", confirmed = true, aiSuggested = false),
        CausalEdge("e2", "n2", "n3", "药杵锉痕与青铜印暗记形成比对动机", confirmed = false, aiSuggested = true),
      ),
    )
    writeJson(layout.causalGraphFile, causalGraph, CausalGraphData::class.java)

    val plans = listOf(
      PlotPlanIdea(
        title = "卷末分支 A：和解查案路线",
        sourceChapterId = "ch_002",
        premise = "沈砚与顾衡在监察使敲门前达成秘密协定，伪造封条拖延时间。",
        keyBeats = listOf("核对司礼监墨印真伪", "合力揪出磨去暗记的第三人"),
        branchTag = "和解路线构思",
      ),
      PlotPlanIdea(
        title = "卷末分支 B：决裂潜逃路线",
        sourceChapterId = "ch_002",
        premise = "沈砚怀疑顾衡设局栽赃，熄灯夺印从暗阁风井撤离白塔。",
        keyBeats = listOf("触发白塔全境夜禁追捕", "转入城西药铺寻谢晚晴核验朱批"),
        branchTag = "决裂路线构思",
      ),
    )
    writeListJson(layout.plotPlansFile, plans, PlotPlanIdea::class.java)

    val worldbook = listOf(
      WorldbookEntry(
        id = "wb_era",
        title = "大昭永熙纪年",
        content = "故事发生于大昭永熙十四年隆冬，朝局由司礼监与守塔司分掌内廷机密。",
        alwaysActive = true,
        priority = 100,
      ),
      WorldbookEntry(
        id = "wb_tower_ban",
        title = "白塔夜禁律令",
        content = "白塔三层暗阁子时后封锁，凡无勘合擅启诏匣者，由守塔司按谋逆论处，立时落闸锁塔。",
        primaryKeys = listOf("白塔"),
        secondaryKeys = listOf("禁令", "夜禁", "子时"),
        secondaryLogic = SecondaryKeyLogic.AND_ANY,
        priority = 90,
        recursiveScan = true,
      ),
      WorldbookEntry(
        id = "wb_guard_bureau",
        title = "守塔司编制与青铜旧印",
        content = "守塔司直属御前，仅正副二使持有青铜旧印；每枚印钮内侧刻有唯一暗记，严禁私磨。",
        primaryKeys = listOf("守塔司", "青铜印"),
        secondaryLogic = SecondaryKeyLogic.NONE,
        priority = 80,
        recursiveScan = true,
      ),
    )
    writeListJson(layout.worldbookEntriesFile, worldbook, WorldbookEntry::class.java)

    val styleProfile = StyleDistillationAnalyzer.analyze("第一章 寒灯启塔", ch1.markdownContent)
    val skillsState = BookSkillsState(
      styleProfile = styleProfile,
      scripts = DefaultSandboxScripts.builtIns(),
    )
    writeJson(layout.skillsConfigFile, skillsState, BookSkillsState::class.java)

    val rpCards = listOf(
      TavernCharacterCard(
        id = "card_gu_heng",
        name = "顾衡",
        description = "守塔司副使，冷峻克制，擅长在对话中以退为进、借规矩施压。",
        personality = "滴水不漏、言简意赅、惜字如金",
        scenario = "永熙十四年雪夜，白塔三层暗阁灯下对峙",
        firstMessage = "（顾衡指尖轻压在黑绸木匣的铜扣上，未抬眼看灯）\n\n「你既把二层药杵的事咽回肚里，今夜便不必再绕弯子。白塔禁令响前，你只有一刻钟问话。」",
        tags = listOf("白塔遗诏", "守塔司"),
      )
    )
    writeListJson(layout.rpCardsFile, rpCards, TavernCharacterCard::class.java)

    // Initialize real JGit repo + create an alternative route branch so multi-route Git works immediately!
    val baseCommit = git.initIfNeeded("初始化《白塔遗诏》主线书稿")
    git.createBranchFromCommit("route-reconciliation", baseCommit.hash, switchImmediately = false)

    reindexBook(bookId)
    persistForeshadows(bookId, foreshadows)
    persistCausalGraph(bookId, causalGraph, "已加载示例长篇《白塔遗诏》")
    openBook(bookId, switchToEditor = false)
  }
}
