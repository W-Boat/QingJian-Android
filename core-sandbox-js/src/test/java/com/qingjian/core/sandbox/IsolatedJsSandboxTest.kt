package com.qingjian.core.sandbox

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolatedJsSandboxTest {

  @Test
  fun unapprovedAndTamperedScriptsAreBlockedByApprovalGate() = runTest {
    val sandbox = IsolatedJsSandbox(context = null)
    val rawScript = DefaultSandboxScripts.builtIns().first()

    // 1. Unapproved script MUST throw ScriptApprovalRequiredException
    var blockedUnapproved = false
    try {
      sandbox.runApprovedScript(rawScript, "他不禁叹了口气，总而言之局面已定。")
    } catch (_: ScriptApprovalRequiredException) {
      blockedUnapproved = true
    }
    assertTrue("Unapproved script must be blocked", blockedUnapproved)

    // 2. After explicit author approval, script executes and returns structured lint issues
    val approved = sandbox.approveScript(rawScript)
    assertTrue(approved.isApprovedForCurrentSource())
    val report = sandbox.runApprovedScript(approved, "他不禁叹了口气，眼中闪过一丝寒意，总而言之局面已定。")
    assertEquals(3, report.issues.size)
    assertTrue(report.summaryForAi.contains("破甲-惯用虚词"))
    assertTrue(report.summaryForAi.contains("去AI味-机械总结"))

    // 3. Modifying even 1 character of sourceCode invalidates approval hash
    val tampered = approved.copy(sourceCode = approved.sourceCode + "\n// tampered")
    assertFalse("Tampered script must invalidate prior approval", tampered.isApprovedForCurrentSource())
    var blockedTampered = false
    try {
      sandbox.runApprovedScript(tampered, "正文")
    } catch (_: ScriptApprovalRequiredException) {
      blockedTampered = true
    }
    assertTrue("Tampered script must be blocked until re-approved", blockedTampered)
  }

  @Test
  fun infiniteLoopScriptTriggersTimeoutException() = runTest {
    val sandbox = IsolatedJsSandbox(context = null)
    val badScript = sandbox.approveScript(
      SandboxJsScript(
        name = "超时测试脚本",
        description = "测试沙箱超时熔断",
        sourceCode = "function checkManuscript(t) { while (true) {} }",
        timeoutMs = 150L,
      )
    )
    var timedOut = false
    try {
      sandbox.runApprovedScript(badScript, "测试文本")
    } catch (_: ScriptExecutionTimeoutException) {
      timedOut = true
    }
    assertTrue("Script exceeding timeout must be terminated", timedOut)
  }

  @Test
  fun skillMarkdownParserAndStyleDistillationWorkAccurately() {
    val skillMd = """
      ---
      name: 悬疑节奏控场
      description: 控制信息释放节奏与章末钩子
      ---
      每章末尾必须保留一个尚未闭合的事实疑问。
    """.trimIndent()
    val pkg = SkillMarkdownParser.parse(skillMd)
    assertEquals("悬疑节奏控场", pkg.name)
    assertEquals("控制信息释放节奏与章末钩子", pkg.description)
    assertTrue(pkg.promptGuidelines.contains("事实疑问"))

    val profile = StyleDistillationAnalyzer.analyze(
      "白塔样稿",
      "风雪未歇。他推开木门。「人不在。」廊下只有冷风掠过灯笼，火苗微微一歪。",
    )
    assertTrue(profile.avgSentenceChars > 0.0)
    assertTrue(profile.dialogueRatio > 0.0)
    assertTrue(profile.distilledRules.isNotEmpty())
  }
}
