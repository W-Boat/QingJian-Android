package com.qingjian.core.eta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookAndCardTest {

  @Test
  fun worldbookEnforcesSecondaryAndNotConditionsAndRecursiveScan() {
    val towerRule = WorldbookEntry(
      id = "wb_tower_ban",
      title = "白塔夜禁律令",
      content = "凡子时后私闯白塔三层者，由守塔司以玄铁枷锁押送暗阁。",
      primaryKeys = listOf("白塔"),
      secondaryKeys = listOf("禁令", "夜禁"),
      secondaryLogic = SecondaryKeyLogic.AND_ANY,
      priority = 90,
      recursiveScan = true,
    )
    val guardBureau = WorldbookEntry(
      id = "wb_guard_bureau",
      title = "守塔司编制",
      content = "守塔司直属御前，司长掌青铜旧印，不受刑部节制。",
      primaryKeys = listOf("守塔司"),
      secondaryLogic = SecondaryKeyLogic.NONE,
      priority = 70,
      recursiveScan = true,
    )
    val excludedRule = WorldbookEntry(
      id = "wb_peace_mode",
      title = "白塔白日祭仪",
      content = "白日开塔供奉香火。",
      primaryKeys = listOf("白塔"),
      secondaryKeys = listOf("禁令"),
      secondaryLogic = SecondaryKeyLogic.NOT_ANY,
      priority = 60,
    )
    val constantEntry = WorldbookEntry(
      id = "wb_const",
      title = "纪年历法",
      content = "大昭永熙十四年冬。",
      alwaysActive = true,
      priority = 100,
    )

    // Case 1: Only "白塔" without "禁令" -> towerRule must NOT fire, excludedRule MUST fire
    val dayResult = WorldbookEngine.evaluate(
      entries = listOf(towerRule, guardBureau, excludedRule, constantEntry),
      scanText = "沈砚白日路过白塔长阶。",
    )
    val dayIds = dayResult.matchedTraces.map { it.entry.id }
    assertTrue(dayIds.contains("wb_const"))
    assertFalse("AND_ANY must not trigger when secondary key is absent", dayIds.contains("wb_tower_ban"))
    assertTrue("NOT_ANY must trigger when excluded key is absent", dayIds.contains("wb_peace_mode"))

    // Case 2: Both "白塔" and "禁令" -> towerRule fires -> recursively triggers guardBureau ("守塔司")!
    val nightResult = WorldbookEngine.evaluate(
      entries = listOf(towerRule, guardBureau, excludedRule, constantEntry),
      scanText = "沈砚在雪夜违背白塔禁令。",
    )
    val nightIds = nightResult.matchedTraces.map { it.entry.id }
    assertTrue(nightIds.contains("wb_tower_ban"))
    assertTrue("Recursive scan must trigger guardBureau via towerRule content", nightIds.contains("wb_guard_bureau"))
    assertFalse("NOT_ANY must NOT trigger when secondary key is present", nightIds.contains("wb_peace_mode"))
    val recursiveTrace = nightResult.matchedTraces.first { it.entry.id == "wb_guard_bureau" }
    assertEquals(1, recursiveTrace.recursionDepth)
  }

  @Test
  fun tavernWorldbookJsonImportParsesSelectiveLogicAndKeys() {
    val json = """
      {
        "entries": {
          "0": {
            "comment": "白塔禁令",
            "key": ["白塔", "高塔"],
            "keysecondary": ["禁令"],
            "selective": true,
            "selectiveLogic": 0,
            "content": "子时后严禁登塔。",
            "order": 85,
            "constant": false
          }
        }
      }
    """.trimIndent()
    val imported = WorldbookEngine.importTavernWorldbookJson(json)
    assertEquals(1, imported.size)
    assertEquals("白塔禁令", imported.first().title)
    assertEquals(SecondaryKeyLogic.AND_ANY, imported.first().secondaryLogic)
    assertEquals(listOf("白塔", "高塔"), imported.first().primaryKeys)
    assertEquals(85, imported.first().priority)
  }

  @Test
  fun tavernPngCardRoundTripPreservesMetadata() {
    val card = TavernCharacterCard(
      name = "顾衡",
      description = "守塔司副使，冷峻克制，擅长审讯与旧档比对。",
      personality = "滴水不漏、言简意赅",
      scenario = "雪夜白塔三层暗阁",
      firstMessage = "你来得比我算的晚了一刻钟。",
      tags = listOf("白塔", "权谋"),
    )
    val pngBytes = TavernCardCodec.embedIntoMinimalPng(card)
    val decoded = TavernCardCodec.parsePngBytes(pngBytes)
    assertEquals("顾衡", decoded.name)
    assertEquals("雪夜白塔三层暗阁", decoded.scenario)
    assertEquals(listOf("白塔", "权谋"), decoded.tags)
  }
}
