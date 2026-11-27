package com.qingjian.core.eta

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID

enum class SecondaryKeyLogic(val label: String) {
  NONE("无次级条件"),
  AND_ANY("AND (任一次级词命中)"),
  AND_ALL("AND (全部次级词命中)"),
  OR("OR (主词或次级词命中)"),
  NOT_ANY("NOT (不能出现任一次级词)"),
  NOT_ALL("NOT (不能同时出现全部次级词)"),
}

/**
 * Tavern-grade Worldbook Entry supporting:
 * - Always-active (常驻)
 * - Primary keys (主触发词)
 * - Secondary condition keys + AND/OR/NOT logic (次级条件)
 * - Priority ordering (优先级)
 * - Recursive association scan (关联递归触发)
 */
data class WorldbookEntry(
  val id: String = "wb_${UUID.randomUUID().toString().take(8)}",
  val title: String,
  val content: String,
  val enabled: Boolean = true,
  val alwaysActive: Boolean = false,
  val primaryKeys: List<String> = emptyList(),
  val secondaryKeys: List<String> = emptyList(),
  val secondaryLogic: SecondaryKeyLogic = SecondaryKeyLogic.NONE,
  val priority: Int = 50,
  val recursiveScan: Boolean = true,
  val preventFurtherRecursion: Boolean = false,
  val category: String = "世界设定",
)

data class WorldbookMatchTrace(
  val entry: WorldbookEntry,
  val triggerReason: String,
  val recursionDepth: Int,
  val includedInBudget: Boolean,
  val compressed: Boolean = false,
)

data class WorldbookEvaluationResult(
  val matchedTraces: List<WorldbookMatchTrace>,
  val injectedText: String,
  val totalCharsUsed: Int,
  val charBudget: Int,
  val truncatedCount: Int,
)

/**
 * Full Tavern-level Worldbook Engine with secondary logic, priority sorting,
 * cycle-safe recursive scanning, transparent hit preview, budget compression,
 * and SillyTavern JSON import.
 */
object WorldbookEngine {
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

  fun evaluate(
    entries: List<WorldbookEntry>,
    scanText: String,
    charBudget: Int = 2400,
    maxRecursionDepth: Int = 3,
  ): WorldbookEvaluationResult {
    val activeEntries = entries.filter { it.enabled }
    val matchedById = linkedMapOf<String, Pair<WorldbookEntry, Pair<String, Int>>>()

    // Depth 0: always-active + direct hits on scanText
    for (entry in activeEntries) {
      if (entry.alwaysActive) {
        matchedById[entry.id] = entry to ("常驻条目" to 0)
        continue
      }
      val reason = checkTrigger(entry, scanText)
      if (reason != null) {
        matchedById[entry.id] = entry to (reason to 0)
      }
    }

    // Recursive scan depths 1..maxRecursionDepth
    var frontierEntries = matchedById.values
      .map { it.first }
      .filter { it.recursiveScan && !it.preventFurtherRecursion }

    var depth = 1
    while (depth <= maxRecursionDepth && frontierEntries.isNotEmpty()) {
      val recursionCorpus = buildString {
        append(scanText)
        append('\n')
        frontierEntries.forEach {
          append(it.title).append('\n').append(it.content).append('\n')
        }
      }
      val newlyMatched = mutableListOf<WorldbookEntry>()
      for (entry in activeEntries) {
        if (matchedById.containsKey(entry.id)) continue
        val reason = checkTrigger(entry, recursionCorpus)
        if (reason != null) {
          matchedById[entry.id] = entry to ("递归第${depth}层 · $reason" to depth)
          if (entry.recursiveScan && !entry.preventFurtherRecursion) {
            newlyMatched.add(entry)
          }
        }
      }
      frontierEntries = newlyMatched
      depth++
    }

    // Order by alwaysActive -> priority descending -> recursionDepth ascending
    val ordered = matchedById.values.sortedWith(
      compareByDescending<Pair<WorldbookEntry, Pair<String, Int>>> { it.first.alwaysActive }
        .thenByDescending { it.first.priority }
        .thenBy { it.second.second }
    )

    var usedChars = 0
    var truncatedCount = 0
    val traces = mutableListOf<WorldbookMatchTrace>()
    val injectedBlocks = mutableListOf<String>()

    for ((entry, meta) in ordered) {
      val (reason, hitDepth) = meta
      val block = "【世界书 · ${entry.title}】\n${entry.content.trim()}"
      val remaining = charBudget - usedChars
      when {
        block.length <= remaining -> {
          usedChars += block.length + 2
          injectedBlocks.add(block)
          traces.add(WorldbookMatchTrace(entry, reason, hitDepth, includedInBudget = true))
        }
        remaining >= 90 -> {
          val compressedBody = entry.content.trim().take((remaining - entry.title.length - 36).coerceAtLeast(40)) + "…（预算截断）"
          val compressedBlock = "【世界书 · ${entry.title}】\n$compressedBody"
          usedChars += compressedBlock.length + 2
          injectedBlocks.add(compressedBlock)
          traces.add(WorldbookMatchTrace(entry, reason, hitDepth, includedInBudget = true, compressed = true))
        }
        else -> {
          truncatedCount++
          traces.add(WorldbookMatchTrace(entry, reason, hitDepth, includedInBudget = false))
        }
      }
    }

    return WorldbookEvaluationResult(
      matchedTraces = traces,
      injectedText = injectedBlocks.joinToString("\n\n"),
      totalCharsUsed = usedChars,
      charBudget = charBudget,
      truncatedCount = truncatedCount,
    )
  }

  fun checkTrigger(entry: WorldbookEntry, corpus: String): String? {
    val normalizedCorpus = corpus.lowercase()
    val primaryHits = entry.primaryKeys
      .map { it.trim() }
      .filter { it.isNotEmpty() && normalizedCorpus.contains(it.lowercase()) }

    val secondaryClean = entry.secondaryKeys.map { it.trim() }.filter { it.isNotEmpty() }
    val secondaryHits = secondaryClean.filter { normalizedCorpus.contains(it.lowercase()) }

    return when (entry.secondaryLogic) {
      SecondaryKeyLogic.NONE -> {
        if (primaryHits.isNotEmpty()) "命中主词: ${primaryHits.joinToString("/")}" else null
      }
      SecondaryKeyLogic.AND_ANY -> {
        if (primaryHits.isNotEmpty() && (secondaryClean.isEmpty() || secondaryHits.isNotEmpty())) {
          "主词(${primaryHits.first()}) AND 次级(${secondaryHits.joinToString("/")})"
        } else null
      }
      SecondaryKeyLogic.AND_ALL -> {
        if (primaryHits.isNotEmpty() && secondaryClean.isNotEmpty() && secondaryHits.size == secondaryClean.size) {
          "主词(${primaryHits.first()}) AND 全部次级(${secondaryHits.joinToString("+")})"
        } else null
      }
      SecondaryKeyLogic.OR -> {
        when {
          primaryHits.isNotEmpty() -> "主词命中: ${primaryHits.first()}"
          secondaryHits.isNotEmpty() -> "OR 次级命中: ${secondaryHits.first()}"
          else -> null
        }
      }
      SecondaryKeyLogic.NOT_ANY -> {
        if (primaryHits.isNotEmpty() && secondaryHits.isEmpty()) {
          "主词(${primaryHits.first()}) 且排除次级词"
        } else null
      }
      SecondaryKeyLogic.NOT_ALL -> {
        if (primaryHits.isNotEmpty() && (secondaryClean.isEmpty() || secondaryHits.size < secondaryClean.size)) {
          "主词(${primaryHits.first()}) 且未同时命中全部排除词"
        } else null
      }
    }
  }

  /**
   * Imports SillyTavern / Character Book JSON worldbook formats:
   * - `{ "entries": { "0": { "comment": ..., "key": [...], "keysecondary": [...], "content": ..., "constant": ..., "selectiveLogic": ... } } }`
   * - `{ "entries": [ ... ] }`
   */
  @Suppress("UNCHECKED_CAST")
  fun importTavernWorldbookJson(jsonText: String): List<WorldbookEntry> {
    val parsed = moshi.adapter(Map::class.java).fromJson(jsonText) as? Map<String, Any?>
      ?: throw IllegalArgumentException("无法解析世界书 JSON 结构")

    val rawEntries: List<Map<String, Any?>> = when (val entriesNode = parsed["entries"]) {
      is Map<*, *> -> entriesNode.values.mapNotNull { it as? Map<String, Any?> }
      is List<*> -> entriesNode.mapNotNull { it as? Map<String, Any?> }
      else -> throw IllegalArgumentException("JSON 中缺少 entries 字段")
    }

    return rawEntries.mapIndexedNotNull { idx, item ->
      val content = (item["content"] as? String)?.trim().orEmpty()
      if (content.isBlank()) return@mapIndexedNotNull null
      val title = ((item["comment"] as? String)
        ?: (item["name"] as? String)
        ?: (item["title"] as? String)
        ?: "导入条目 ${idx + 1}").trim()
      val primaryKeys = extractStringList(item["key"] ?: item["keys"])
      val secondaryKeys = extractStringList(item["keysecondary"] ?: item["secondary_keys"])
      val constant = (item["constant"] as? Boolean) ?: (item["alwaysActive"] as? Boolean) ?: false
      val enabled = !((item["disable"] as? Boolean) ?: false) && ((item["enabled"] as? Boolean) ?: true)
      val priority = ((item["order"] as? Number) ?: (item["priority"] as? Number))?.toInt() ?: 50
      val selective = (item["selective"] as? Boolean) ?: secondaryKeys.isNotEmpty()
      val logicCode = (item["selectiveLogic"] as? Number)?.toInt()
      val secondaryLogic = if (!selective || secondaryKeys.isEmpty()) {
        SecondaryKeyLogic.NONE
      } else {
        when (logicCode) {
          0 -> SecondaryKeyLogic.AND_ANY
          1 -> SecondaryKeyLogic.NOT_ALL
          2 -> SecondaryKeyLogic.NOT_ANY
          3 -> SecondaryKeyLogic.AND_ALL
          else -> SecondaryKeyLogic.AND_ANY
        }
      }
      val preventRecursion = (item["preventRecursion"] as? Boolean) ?: false

      WorldbookEntry(
        title = title,
        content = content,
        enabled = enabled,
        alwaysActive = constant,
        primaryKeys = primaryKeys,
        secondaryKeys = secondaryKeys,
        secondaryLogic = secondaryLogic,
        priority = priority,
        recursiveScan = true,
        preventFurtherRecursion = preventRecursion,
        category = "酒馆导入",
      )
    }
  }

  private fun extractStringList(node: Any?): List<String> = when (node) {
    is List<*> -> node.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
    is String -> node.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
  }
}
