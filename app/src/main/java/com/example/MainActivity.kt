package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AiAndAppearanceSettingsDialog
import com.example.ui.theme.QingJianTheme
import com.qingjian.core.storage.WritingMode
import com.qingjian.feature.bookshelf.BookshelfScreen
import com.qingjian.feature.editor.EditorWorkspaceScreen
import com.qingjian.feature.lore.PlotWorkbenchScreen
import com.qingjian.feature.rp.RoleplayRehearsalScreen
import com.qingjian.feature.skills.SkillsAndSandboxScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      QingJianAppRoot()
    }
  }
}

private fun MainDestination.icon(): ImageVector = when (this) {
  MainDestination.BOOKSHELF -> Icons.Outlined.AutoStories
  MainDestination.EDITOR -> Icons.Outlined.EditNote
  MainDestination.LORE -> Icons.Outlined.AccountTree
  MainDestination.SKILLS -> Icons.Outlined.Build
  MainDestination.RP -> Icons.Outlined.Forum
}

@Composable
fun QingJianAppRoot(
  vm: QingJianViewModel = viewModel(),
) {
  val state by vm.uiState.collectAsState()
  var showAiSettings by remember { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(state.statusBanner) {
    val msg = state.statusBanner
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      vm.clearBanner()
    }
  }

  QingJianTheme(
    darkTheme = state.isDarkTheme,
    accent = state.accent,
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val isTabletWide = maxWidth >= 840.dp

      Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
          if (!isTabletWide) {
            NavigationBar {
              MainDestination.entries.forEach { dest ->
                NavigationBarItem(
                  selected = state.destination == dest,
                  onClick = { vm.navigate(dest) },
                  icon = { Icon(dest.icon(), contentDescription = dest.label) },
                  label = { Text(dest.label, maxLines = 1) },
                )
              }
            }
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (isTabletWide) {
            NavigationRail(
              header = {
                IconButton(onClick = { showAiSettings = true }) {
                  Icon(Icons.Outlined.Settings, contentDescription = "AI 引擎与外观设置")
                }
              },
            ) {
              MainDestination.entries.forEach { dest ->
                NavigationRailItem(
                  selected = state.destination == dest,
                  onClick = { vm.navigate(dest) },
                  icon = { Icon(dest.icon(), contentDescription = dest.label) },
                  label = { Text(dest.label, maxLines = 1) },
                )
              }
            }
            VerticalDivider()
          }

          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val activeManifest = state.activeManifest
            val activeChapter = state.activeChapter

            when (state.destination) {
              MainDestination.BOOKSHELF -> BookshelfScreen(
                books = state.books,
                activeBookId = state.activeBookId,
                isGridView = state.isGridView,
                onToggleViewMode = vm::toggleViewMode,
                onOpenBook = { vm.openBook(it, switchToEditor = true) },
                onCreateBook = vm::createNewBook,
                onDeleteBook = vm::deleteBook,
                onUpdateBookSettings = vm::updateBookSettings,
                onExportBook = vm::exportBookSync,
                onOpenAiSettings = { showAiSettings = true },
              )

              MainDestination.EDITOR -> {
                if (activeManifest != null && activeChapter != null) {
                  EditorWorkspaceScreen(
                    bookTitle = activeManifest.title,
                    writingMode = activeManifest.writingMode,
                    wordCountGoal = activeManifest.wordCountGoalPerChapter,
                    editorWidthDp = activeManifest.editorWidthDp,
                    isDarkTheme = state.isDarkTheme,
                    accentHex = state.accent.hex,
                    isTabletWide = isTabletWide,
                    chapters = state.chapters,
                    activeChapter = activeChapter,
                    activeHandoff = state.handoffs.firstOrNull { it.chapterId == activeChapter.chapterId },
                    foreshadows = state.foreshadows,
                    loreEntries = state.loreEntries,
                    worldbookEntries = state.worldbookEntries,
                    contextConfig = state.contextConfig,
                    inlineCandidate = state.inlineCandidate,
                    fullScreenDiff = state.fullScreenDiff,
                    activePipelineRun = state.activePipelineRun,
                    gitSnapshot = state.gitSnapshot,
                    canUndo = state.canUndo,
                    pendingJumpBlockId = state.pendingJumpBlockId,
                    onConsumeJumpBlockId = vm::consumeJumpBlockId,
                    onToggleWritingMode = vm::setWritingMode,
                    onSelectChapter = { vm.selectChapter(it) },
                    onCreateBlankChapter = vm::createBlankChapter,
                    onDocumentChanged = vm::onDocumentChanged,
                    onUpdateSingleBlockDirect = vm::updateSingleBlockDirect,
                    onSetBlockVote = vm::setBlockVote,
                    onSetBlockAnnotation = vm::setBlockAnnotation,
                    onAttachForeshadowToBlock = { fId, title, stage, blkId, ex ->
                      vm.attachForeshadowToBlock(fId, title, stage, blkId, ex)
                    },
                    onRequestSingleBlockRewrite = vm::requestSingleBlockRewrite,
                    onCycleInlineCandidate = vm::cycleInlineCandidate,
                    onUpdateInlineCandidateText = vm::updateInlineCandidateText,
                    onAcceptInlineCandidate = vm::acceptInlineCandidate,
                    onRejectInlineCandidate = vm::rejectInlineCandidate,
                    onRequestBatchRewrite = vm::requestBatchRewrite,
                    onAcceptFullScreenDiff = vm::acceptFullScreenDiff,
                    onRejectFullScreenDiff = vm::rejectFullScreenDiff,
                    onUndoLastRevision = vm::undoLastRevision,
                    onTriggerChapterDraftPipeline = vm::runChapterDraftPipeline,
                    onResumeFailedPipeline = vm::resumeFailedPipeline,
                    onFinalizeChapterAndGenerateHandoff = vm::finalizeChapterAndGenerateHandoff,
                    onSaveHandoffBrief = vm::saveEditedHandoff,
                    onCreateNextChapterFromDirection = vm::createNextChapterFromDirection,
                    onGenerateSummaryForDirection = vm::generateSummaryForDirection,
                    onUpdateContextConfig = vm::updateContextConfig,
                    onGitCommit = vm::gitCommitAll,
                    onGitCreateBranch = vm::gitCreateBranch,
                    onGitSwitchBranch = vm::gitSwitchBranch,
                    onGitInspectCommit = vm::gitInspectCommit,
                    onGitLoadCommit = vm::gitLoadHistoricalCommit,
                    onCreateManualRestorePoint = vm::createManualRestorePoint,
                    onRollbackRestorePoint = vm::rollbackRestorePoint,
                  )
                } else {
                  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在载入书稿仓库…")
                  }
                }
              }

              MainDestination.LORE -> PlotWorkbenchScreen(
                bookTitle = activeManifest?.title ?: "未命名书稿",
                handoffs = state.handoffs,
                loreEntries = state.loreEntries,
                loreCandidates = state.loreCandidates,
                foreshadows = state.foreshadows,
                causalGraph = state.causalGraph,
                plotPlans = state.plotPlans,
                worldbookEntries = state.worldbookEntries,
                worldbookCharBudget = state.contextConfig.worldbookCharBudget,
                activeChapterId = activeChapter?.chapterId.orEmpty(),
                activeChapterTitle = activeChapter?.title ?: "当前章节",
                activeChapterSampleText = activeChapter?.markdownContent.orEmpty(),
                onExtractLoreCandidates = vm::extractLoreFromActiveChapter,
                onApproveLoreCandidate = vm::approveLoreCandidate,
                onRejectLoreCandidate = vm::rejectLoreCandidate,
                onJumpToAnchor = vm::jumpToForeshadowOrLoreAnchor,
                onCreateForeshadow = vm::createForeshadow,
                onAddForeshadowAnchor = vm::addForeshadowAnchor,
                onConfirmCausalEdge = vm::confirmCausalEdge,
                onRejectCausalEdge = vm::rejectCausalEdge,
                onAddPlotPlan = vm::addPlotPlan,
                onCreateNextChapterFromPlan = vm::createChapterFromPlotPlan,
                onUpsertWorldbookEntry = vm::upsertWorldbookEntry,
                onToggleWorldbookEntry = vm::toggleWorldbookEntry,
                onUpdateWorldbookBudget = vm::updateWorldbookBudget,
                onImportTavernWorldbookJson = vm::importTavernWorldbookJson,
              )

              MainDestination.SKILLS -> SkillsAndSandboxScreen(
                bookTitle = activeManifest?.title ?: "未命名书稿",
                skillsState = state.skillsState,
                aiWizardCompleted = activeManifest?.aiWizardCompleted ?: false,
                lastScriptReport = state.lastScriptReport,
                sandboxError = state.sandboxError,
                onToggleBuiltInSkill = vm::toggleBuiltInSkill,
                onRunStyleDistillation = vm::runStyleDistillationWizard,
                onImportSkillPackage = vm::importSkillPackage,
                onToggleImportedPackage = vm::toggleImportedSkillPackage,
                onAddJsScript = vm::addSandboxJsScript,
                onApproveJsScript = vm::approveSandboxScript,
                onRevokeJsScript = vm::revokeSandboxScript,
                onRunJsScriptOnActiveChapter = vm::runSandboxScriptOnActiveChapter,
                onSendReportToAiRevision = vm::sendSandboxReportToAiRevision,
              )

              MainDestination.RP -> RoleplayRehearsalScreen(
                bookTitle = activeManifest?.title ?: "未命名书稿",
                cards = state.rpCards,
                selectedCardId = state.selectedRpCardId,
                messages = state.rpMessages,
                worldbookEntries = state.worldbookEntries,
                novelCharacters = state.loreEntries,
                isGenerating = state.isRpGenerating,
                onSelectCard = vm::selectRpCard,
                onSendMessage = vm::sendRpMessage,
                onImportCard = vm::importRpCard,
                onProjectLoreCharacterToCard = vm::projectLoreCharacterToRpCard,
                onInsertDialogueToDraft = vm::insertRpDialogueIntoActiveChapter,
              )
            }
          }
        }
      }
    }

    if (showAiSettings) {
      AiAndAppearanceSettingsDialog(
        isDarkTheme = state.isDarkTheme,
        selectedAccent = state.accent,
        profiles = state.aiProfiles,
        activeProfileId = state.activeProfileId,
        usageLogs = state.usageLogs,
        onToggleDarkTheme = vm::setDarkTheme,
        onSelectAccent = vm::setAccent,
        onSelectProfile = vm::selectAiProfile,
        onUpdateProfile = vm::updateAiProfile,
        onAddProfile = vm::addAiProfile,
        onDismiss = { showAiSettings = false },
      )
    }
  }
}
