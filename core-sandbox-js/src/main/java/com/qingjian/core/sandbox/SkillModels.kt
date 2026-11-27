package com.qingjian.core.sandbox

import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

enum class BuiltInSkillType(val displayName: String, val summary: String) {
  STYLE_DISTILL("文风蒸馏", "从参考样稿提炼句长分布、叙述距离、对话节奏等结构化风格档案，并在生成时严格对齐。"),
  ARMOR_BREAK("破甲", "识别并替换惯用转折、套话与空泛修饰（如“不禁”“顿时”“眼中闪过一丝”），恢复具体动作与感官质感。"),
  DE_AI_TONE("去 AI 味", "清理机械段落总结、工整排比、说教式升华与过度解释情绪，保留克制留白。"),
}

data class StyleProfile(
  val sampleTitle: String,
  val avgSentenceChars: Double,
  val shortSentenceRatio: Double,
  val longSentenceRatio: Double,
  val dialogueRatio: Double,
  val narrativeDistance: String,
  val pacingSignature: String,
  val distilledRules: List<String>,
)

data class ImportedSkillPackage(
  val id: String = "pkg_${UUID.randomUUID().toString().take(8)}",
  val name: String,
  val description: String,
  val promptGuidelines: String,
  val referenceFiles: Map<String, String> = emptyMap(),
  val enabled: Boolean = true,
)

data class SandboxJsScript(
  val id: String = "js_${UUID.randomUUID().toString().take(8)}",
  val name: String,
  val version: String = "1.0.0",
  val description: String,
  val sourceCode: String,
  val timeoutMs: Long = 2000L,
  val maxMemoryBytes: Long = 16L * 1024L * 1024L,
  val approvedSha256: String? = null,
  val enabled: Boolean = false,
) {
  fun currentSha256(): String = sha256Hex(sourceCode)
  fun isApprovedForCurrentSource(): Boolean = approvedSha256 != null && approvedSha256 == currentSha256()
}

data class ScriptLintIssue(
  val rule: String,
  val severity: String,
  val excerpt: String,
  val suggestion: String,
)

data class ScriptExecutionReport(
  val scriptId: String,
  val scriptName: String,
  val engineName: String,
  val durationMs: Long,
  val issues: List<ScriptLintIssue>,
  val summaryForAi: String,
)

data class BookSkillsState(
  val enabledBuiltIn: Set<BuiltInSkillType> = setOf(
    BuiltInSkillType.STYLE_DISTILL,
    BuiltInSkillType.ARMOR_BREAK,
    BuiltInSkillType.DE_AI_TONE,
  ),
  val styleProfile: StyleProfile? = null,
  val customCorrectionRules: List<String> = listOf(
    "严禁在段尾使用“总而言之”“由此可见”等总结句",
    "人物情绪必须通过细微动作或环境细节外化，不直接宣称“他感到非常愤怒”",
  ),
  val importedPackages: List<ImportedSkillPackage> = emptyList(),
  val scripts: List<SandboxJsScript> = DefaultSandboxScripts.builtIns(),
) {
  fun buildSystemPromptDirectives(): String = buildString {
    if (BuiltInSkillType.STYLE_DISTILL in enabledBuiltIn && styleProfile != null) {
      appendLine("【文风蒸馏档案 · ${styleProfile.sampleTitle}】")
      appendLine("- 平均句长约 ${styleProfile.avgSentenceChars.roundToInt()} 字，短句占比 ${(styleProfile.shortSentenceRatio * 100).roundToInt()}%，对话占比 ${(styleProfile.dialogueRatio * 100).roundToInt()}%")
      appendLine("- 叙述距离：${styleProfile.narrativeDistance}；节奏特征：${styleProfile.pacingSignature}")
      styleProfile.distilledRules.forEach { appendLine("- $it") }
      appendLine()
    }
    if (BuiltInSkillType.ARMOR_BREAK in enabledBuiltIn) {
      appendLine("【破甲规则】禁用“不禁”“顿时”“缓缓地”“眼中闪过一丝”“空气仿佛凝固”等高频套话，改用可观察的动作、器物与声响。")
    }
    if (BuiltInSkillType.DE_AI_TONE in enabledBuiltIn) {
      appendLine("【去 AI 味规则】禁止工整三连排比、禁止在段落末尾做主题升华或情绪总结，允许留白与克制转折。")
    }
    if (customCorrectionRules.isNotEmpty()) {
      appendLine("【作者专属纠偏】")
      customCorrectionRules.forEach { appendLine("- $it") }
    }
    importedPackages.filter { it.enabled }.forEach { pkg ->
      appendLine("【扩展技能 · ${pkg.name}】")
      appendLine(pkg.promptGuidelines.take(800))
    }
  }.trim()
}

object StyleDistillationAnalyzer {
  fun analyze(sampleTitle: String, text: String): StyleProfile {
    val clean = text.trim()
    val sentences = clean.split(Regex("[。！？!?\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
    val avgLen = if (sentences.isEmpty()) 0.0 else sentences.map { it.length }.average()
    val shortCount = sentences.count { it.length <= 16 }
    val longCount = sentences.count { it.length >= 36 }
    val shortRatio = if (sentences.isEmpty()) 0.0 else shortCount.toDouble() / sentences.size
    val longRatio = if (sentences.isEmpty()) 0.0 else longCount.toDouble() / sentences.size

    val dialogueChars = Regex("[「“\"][^」”\"]+[」”\"]").findAll(clean).sumOf { it.value.length }
    val dialogueRatio = if (clean.isEmpty()) 0.0 else (dialogueChars.toDouble() / clean.length).coerceIn(0.0, 1.0)

    val firstPersonHits = Regex("我").findAll(clean).count()
    val distance = when {
      firstPersonHits >= 6 -> "近距第一人称贴身视角"
      dialogueRatio >= 0.35 -> "中近距对话驱动视角"
      else -> "克制冷峻的第三人称限知视角"
    }
    val pacing = when {
      shortRatio >= 0.55 -> "短促凌厉、切镜紧凑"
      longRatio >= 0.30 -> "绵密铺陈、长句舒展"
      else -> "长短交替、张弛均衡"
    }

    val rules = buildList {
      add("单句长度围绕 ${avgLen.roundToInt()} 字波动，保持「$pacing」节奏。")
      add("叙述站位维持「$distance」。")
      if (dialogueRatio < 0.25) {
        add("对白克制凝练，多用行动与场景冷光替代直接解释。")
      } else {
        add("以对白交锋推动信息揭露，穿插细微肢体停顿。")
      }
    }

    return StyleProfile(
      sampleTitle = sampleTitle.ifBlank { "参考样稿" },
      avgSentenceChars = avgLen,
      shortSentenceRatio = shortRatio,
      longSentenceRatio = longRatio,
      dialogueRatio = dialogueRatio,
      narrativeDistance = distance,
      pacingSignature = pacing,
      distilledRules = rules,
    )
  }
}

/**
 * Parses an imported SKILL.md document with optional YAML frontmatter (`name`, `description`).
 */
object SkillMarkdownParser {
  fun parse(markdown: String, references: Map<String, String> = emptyMap()): ImportedSkillPackage {
    val trimmed = markdown.trim()
    var name = "未命名技能"
    var description = "外部导入的写作技能"
    var body = trimmed

    if (trimmed.startsWith("---")) {
      val endIdx = trimmed.indexOf("\n---", startIndex = 3)
      if (endIdx > 0) {
        val frontmatter = trimmed.substring(3, endIdx)
        body = trimmed.substring(endIdx + 4).trim()
        frontmatter.lineSequence().forEach { line ->
          val colon = line.indexOf(':')
          if (colon > 0) {
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim().trim('"', '\'')
            when (key) {
              "name" -> if (value.isNotBlank()) name = value
              "description" -> if (value.isNotBlank()) description = value
            }
          }
        }
      }
    } else {
      val firstHeading = trimmed.lineSequence().firstOrNull { it.startsWith("#") }
      if (firstHeading != null) {
        name = firstHeading.trimStart('#', ' ').trim()
      }
    }

    return ImportedSkillPackage(
      name = name,
      description = description,
      promptGuidelines = body,
      referenceFiles = references,
      enabled = true,
    )
  }
}

object DefaultSandboxScripts {
  fun builtIns(): List<SandboxJsScript> = listOf(
    SandboxJsScript(
      id = "js_anti_cliche",
      name = "破甲与套话巡检脚本",
      version = "1.0.0",
      description = "扫描正文中的高频套话与 AI 总结腔（如“不禁”“顿时”“眼中闪过一丝”“总而言之”），输出结构化改稿建议。",
      sourceCode = """
        // 规范声明：@permission READ_TEXT_ONLY @timeout 2000ms
        function checkManuscript(text) {
          var rules = [
            { pattern: "不禁", rule: "破甲-惯用虚词", suggestion: "删除“不禁”，直接写人物动作" },
            { pattern: "顿时", rule: "破甲-廉价转折", suggestion: "替换为具体声响或时间停顿" },
            { pattern: "眼中闪过一丝", rule: "破甲-套话描写", suggestion: "改写为视线移动或神态细节" },
            { pattern: "空气仿佛凝固", rule: "破甲-陈词滥调", suggestion: "以环境微声反衬寂静" },
            { pattern: "总而言之", rule: "去AI味-机械总结", suggestion: "删除总结句，保持叙事留白" },
            { pattern: "综上所述", rule: "去AI味-论文腔", suggestion: "删除议论性收尾" }
          ];
          var issues = [];
          for (var i = 0; i < rules.length; i++) {
            var r = rules[i];
            var idx = text.indexOf(r.pattern);
            if (idx !== -1) {
              var start = Math.max(0, idx - 8);
              var end = Math.min(text.length, idx + r.pattern.length + 12);
              issues.push({
                rule: r.rule,
                severity: "WARN",
                excerpt: text.substring(start, end),
                suggestion: r.suggestion
              });
            }
          }
          return JSON.stringify(issues);
        }
      """.trimIndent(),
      approvedSha256 = null,
      enabled = false,
    ),
    SandboxJsScript(
      id = "js_pacing_audit",
      name = "长句拥堵与对话节律检查",
      version = "1.0.0",
      description = "检测超过 95 字未断句的超长段落以及连续高频排比句，生成节奏优化清单交由 AI 辅助复审。",
      sourceCode = """
        // 规范声明：@permission READ_TEXT_ONLY @timeout 2000ms
        function checkManuscript(text) {
          var issues = [];
          var sentences = text.split(/[。！？\n]+/);
          for (var i = 0; i < sentences.length; i++) {
            var s = sentences[i].trim();
            if (s.length > 95) {
              issues.push({
                rule: "节奏-超长句拥堵",
                severity: "INFO",
                excerpt: s.substring(0, 28) + "…",
                suggestion: "该句长达 " + s.length + " 字，建议拆分为短句以增强呼吸感"
              });
            }
          }
          return JSON.stringify(issues);
        }
      """.trimIndent(),
      approvedSha256 = null,
      enabled = false,
    ),
  )
}

fun sha256Hex(input: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
  return digest.joinToString("") { "%02x".format(it) }
}
