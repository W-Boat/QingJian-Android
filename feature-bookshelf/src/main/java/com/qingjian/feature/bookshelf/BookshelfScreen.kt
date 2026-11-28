package com.qingjian.feature.bookshelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingjian.core.git.ExportFormat
import com.qingjian.core.storage.BookIndexEntity
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CoverPresets = listOf(
  Triple("青玉笺", 0xFF1A3A3A, 0xFF2B6E63),
  Triple("墨松纸", 0xFF1C2D27, 0xFF3E6B58),
  Triple("绛砂卷", 0xFF3E1E22, 0xFF8C3B42),
  Triple("靛青录", 0xFF1B273B, 0xFF3A5B8C),
  Triple("素檀册", 0xFF382C20, 0xFF82623F),
)

@Composable
fun BookshelfScreen(
  books: List<BookIndexEntity>,
  activeBookId: String?,
  isGridView: Boolean,
  onToggleViewMode: () -> Unit,
  onOpenBook: (String) -> Unit,
  onCreateBook: (title: String, author: String, genre: String, synopsis: String, coverBg: Long, coverAccent: Long, wordGoal: Int) -> Unit,
  onDeleteBook: (String) -> Unit,
  onUpdateBookSettings: (bookId: String, wordGoal: Int, editorWidthDp: Int, coverBg: Long, coverAccent: Long) -> Unit,
  onExportBook: (bookId: String, format: ExportFormat) -> String,
  onOpenAiSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showCreateDialog by remember { mutableStateOf(false) }
  var exportTargetBook by remember { mutableStateOf<BookIndexEntity?>(null) }
  var settingsTargetBook by remember { mutableStateOf<BookIndexEntity?>(null) }
  var lastExportResult by remember { mutableStateOf<String?>(null) }

  Column(modifier = modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
    // Clean single-row Miuix top bar
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        MiuixText(
          text = "青简书架",
          style = MiuixTheme.textStyles.title2,
          fontWeight = FontWeight.SemiBold,
          color = MiuixTheme.colorScheme.onBackground,
        )
        MiuixText(
          text = "共 ${books.size} 部独立 Git 仓库书稿 · 本地单一事实来源",
          style = MiuixTheme.textStyles.footnote1,
          color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(onClick = onToggleViewMode) {
          Icon(
            imageVector = if (isGridView) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
            contentDescription = "切换视图",
            tint = MiuixTheme.colorScheme.onSurface,
          )
        }
        IconButton(onClick = onOpenAiSettings) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Eta 引擎与 Miuix 主题设置",
            tint = MiuixTheme.colorScheme.onSurface,
          )
        }
        MiuixTextButton(
          text = "新建书稿",
          onClick = { showCreateDialog = true },
          colors = MiuixButtonDefaults.textButtonColorsPrimary(),
          minHeight = 38.dp,
        )
      }
    }

    MiuixHorizontalDivider()

    if (books.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        MiuixCard(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            MiuixText(
              text = "书架尚空",
              style = MiuixTheme.textStyles.title3,
              color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            MiuixText(
              text = "新建一部长篇书稿，系统将自动初始化独立本地 Eclipse JGit 仓库与 Room 索引；随后请在右上角设置中绑定 API Key 或完成 ChatGPT Codex OAuth 授权。",
              style = MiuixTheme.textStyles.body2,
              color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              MiuixTextButton(
                text = "创建第一部作品",
                onClick = { showCreateDialog = true },
                colors = MiuixButtonDefaults.textButtonColorsPrimary(),
                minHeight = 40.dp,
              )
              MiuixTextButton(
                text = "配置 Eta 引擎",
                onClick = onOpenAiSettings,
                colors = MiuixButtonDefaults.textButtonColors(),
                minHeight = 40.dp,
              )
            }
          }
        }
      }
    } else if (isGridView) {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(books, key = { it.bookId }) { book ->
          BookGridCard(
            book = book,
            isActive = book.bookId == activeBookId,
            onOpen = { onOpenBook(book.bookId) },
            onSettings = { settingsTargetBook = book },
            onExport = { exportTargetBook = book },
          )
        }
      }
    } else {
      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(books, key = { it.bookId }) { book ->
          BookHorizontalRow(
            book = book,
            isActive = book.bookId == activeBookId,
            onOpen = { onOpenBook(book.bookId) },
            onSettings = { settingsTargetBook = book },
            onExport = { exportTargetBook = book },
            onDelete = { onDeleteBook(book.bookId) },
          )
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateBookDialog(
      onDismiss = { showCreateDialog = false },
      onConfirm = { title, author, genre, synopsis, bg, accent, goal ->
        onCreateBook(title, author, genre, synopsis, bg, accent, goal)
        showCreateDialog = false
      },
    )
  }

  exportTargetBook?.let { target ->
    AlertDialog(
      onDismissRequest = {
        exportTargetBook = null
        lastExportResult = null
      },
      title = { Text("导出《${target.title}》") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "直接从本地 Git 仓库正文文件编译导出，支持全格式归档与电子书打包。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          ExportFormat.entries.forEach { fmt ->
            OutlinedButton(
              onClick = { lastExportResult = onExportBook(target.bookId, fmt) },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp),
            ) {
              Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(8.dp))
              Text(fmt.label)
            }
          }
          lastExportResult?.let { msg ->
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          exportTargetBook = null
          lastExportResult = null
        }) {
          Text("完成")
        }
      },
    )
  }

  settingsTargetBook?.let { target ->
    BookSettingsDialog(
      book = target,
      onDismiss = { settingsTargetBook = null },
      onSave = { goal, widthDp, bg, accent ->
        onUpdateBookSettings(target.bookId, goal, widthDp, bg, accent)
        settingsTargetBook = null
      },
      onDelete = {
        onDeleteBook(target.bookId)
        settingsTargetBook = null
      },
    )
  }
}

@Composable
private fun BookGridCard(
  book: BookIndexEntity,
  isActive: Boolean,
  onOpen: () -> Unit,
  onSettings: () -> Unit,
  onExport: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(
      width = if (isActive) 1.5.dp else 1.dp,
      color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
  ) {
    Column {
      // 2:3 Typographic Editorial Book Cover
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(2f / 3f)
          .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
          .background(
            Brush.verticalGradient(
              colors = listOf(Color(book.coverColorHex), Color(book.coverAccentHex)),
            )
          )
          .padding(16.dp),
      ) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = book.genre.ifBlank { "长篇连载" },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.78f),
          )
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = book.title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
              color = Color.White,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.30f))
            Text(
              text = book.author,
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.85f),
            )
          }
          Text(
            text = "分支 ${book.currentBranch}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "${book.chapterCount} 章 · ${book.totalWords} 字",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "目标 ${book.wordCountGoalPerChapter} 字/章",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row {
            IconButton(onClick = onSettings, modifier = Modifier.size(30.dp)) {
              Icon(Icons.Outlined.Tune, contentDescription = "书籍设置", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onExport, modifier = Modifier.size(30.dp)) {
              Icon(Icons.Outlined.FileDownload, contentDescription = "导出", modifier = Modifier.size(16.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BookHorizontalRow(
  book: BookIndexEntity,
  isActive: Boolean,
  onOpen: () -> Unit,
  onSettings: () -> Unit,
  onExport: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(
      width = if (isActive) 1.5.dp else 1.dp,
      color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier = Modifier
          .width(56.dp)
          .height(84.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(
            Brush.verticalGradient(listOf(Color(book.coverColorHex), Color(book.coverAccentHex)))
          )
          .padding(6.dp),
        contentAlignment = Alignment.BottomStart,
      ) {
        Text(
          text = book.title.take(4),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          maxLines = 2,
        )
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = book.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${book.author} · ${book.genre} · 分支 ${book.currentBranch}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = book.synopsis.ifBlank { "暂无简介" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${book.chapterCount} 章 · 共 ${book.totalWords} 字 · 每章目标 ${book.wordCountGoalPerChapter} 字",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        IconButton(onClick = onSettings) {
          Icon(Icons.Outlined.Tune, contentDescription = "书籍设置")
        }
        IconButton(onClick = onExport) {
          Icon(Icons.Outlined.FileDownload, contentDescription = "导出")
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除")
        }
      }
    }
  }
}

@Composable
private fun CreateBookDialog(
  onDismiss: () -> Unit,
  onConfirm: (String, String, String, String, Long, Long, Int) -> Unit,
) {
  var title by remember { mutableStateOf("") }
  var author by remember { mutableStateOf("林墨") }
  var genre by remember { mutableStateOf("悬疑古风") }
  var synopsis by remember { mutableStateOf("") }
  var wordGoal by remember { mutableIntStateOf(3000) }
  var selectedCover by remember { mutableIntStateOf(0) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("新建书稿仓库") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("书名") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = author,
            onValueChange = { author = it },
            label = { Text("作者") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = genre,
            onValueChange = { genre = it },
            label = { Text("题材") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        OutlinedTextField(
          value = synopsis,
          onValueChange = { synopsis = it },
          label = { Text("一句话梗概") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
        Text("单章字数目标：$wordGoal 字", style = MaterialTheme.typography.bodySmall)
        Slider(
          value = wordGoal.toFloat(),
          onValueChange = { wordGoal = (it.toInt() / 500) * 500 },
          valueRange = 1000f..8000f,
        )
        Text("封面色调", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CoverPresets.forEachIndexed { idx, preset ->
            val selected = selectedCover == idx
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(preset.second),
              border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
              modifier = Modifier.size(34.dp).clickable { selectedCover = idx },
            ) {}
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val preset = CoverPresets[selectedCover]
          onConfirm(
            title.trim().ifBlank { "未命名长篇" },
            author.trim().ifBlank { "佚名" },
            genre.trim().ifBlank { "长篇小说" },
            synopsis.trim(),
            preset.second,
            preset.third,
            wordGoal,
          )
        },
      ) {
        Text("初始化仓库")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

@Composable
private fun BookSettingsDialog(
  book: BookIndexEntity,
  onDismiss: () -> Unit,
  onSave: (wordGoal: Int, editorWidthDp: Int, coverBg: Long, coverAccent: Long) -> Unit,
  onDelete: () -> Unit,
) {
  var wordGoal by remember { mutableIntStateOf(book.wordCountGoalPerChapter) }
  var editorWidth by remember { mutableIntStateOf(book.editorWidthDp) }
  var bgHex by remember { mutableLongStateOf(book.coverColorHex) }
  var accentHex by remember { mutableLongStateOf(book.coverAccentHex) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("书籍排版与封面设置") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("章节字数目标：$wordGoal 字", style = MaterialTheme.typography.bodySmall)
        Slider(
          value = wordGoal.toFloat(),
          onValueChange = { wordGoal = (it.toInt() / 500) * 500 },
          valueRange = 1000f..8000f,
        )
        Text("编辑区最大宽度：${editorWidth}dp", style = MaterialTheme.typography.bodySmall)
        Slider(
          value = editorWidth.toFloat(),
          onValueChange = { editorWidth = (it.toInt() / 20) * 20 },
          valueRange = 480f..860f,
        )
        Text("切换封面色系", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CoverPresets.forEach { preset ->
            val selected = bgHex == preset.second
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(preset.second),
              border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
              modifier = Modifier.size(34.dp).clickable {
                bgHex = preset.second
                accentHex = preset.third
              },
            ) {}
          }
        }
        HorizontalDivider()
        TextButton(onClick = onDelete) {
          Text("删除此书稿与索引", color = MaterialTheme.colorScheme.error)
        }
      }
    },
    confirmButton = {
      Button(onClick = { onSave(wordGoal, editorWidth, bgHex, accentHex) }) {
        Text("保存设置")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}
