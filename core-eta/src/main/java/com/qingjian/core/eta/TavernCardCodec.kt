package com.qingjian.core.eta

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32

/**
 * Unified Tavern Character Card (V1 / V2 / V3) model shared with RP rehearsal
 * and novel character projection.
 */
data class TavernCharacterCard(
  val id: String = "card_${UUID.randomUUID().toString().take(8)}",
  val specVersion: String = "chara_card_v2",
  val name: String,
  val description: String,
  val personality: String = "",
  val scenario: String = "",
  val firstMessage: String = "",
  val messageExample: String = "",
  val systemPrompt: String = "",
  val tags: List<String> = emptyList(),
  val linkedLoreId: String? = null,
)

/**
 * Pure Kotlin parser & serializer for SillyTavern V1 / V2 / V3 JSON cards and
 * PNG character cards embedding Base64 JSON inside `tEXt` (`chara` / `ccv3`) chunks.
 */
object TavernCardCodec {
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val pngSignature = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
  )

  @Suppress("UNCHECKED_CAST")
  fun parseJson(jsonText: String): TavernCharacterCard {
    val root = moshi.adapter(Map::class.java).fromJson(jsonText) as? Map<String, Any?>
      ?: throw IllegalArgumentException("角色卡 JSON 解析失败")

    val spec = (root["spec"] as? String)?.trim().orEmpty()
    val dataNode = (root["data"] as? Map<String, Any?>) ?: root
    val name = (dataNode["name"] as? String)?.trim().orEmpty()
    require(name.isNotBlank()) { "角色卡缺少 name 字段" }

    val tags = (dataNode["tags"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    return TavernCharacterCard(
      specVersion = spec.ifBlank { "chara_card_v1" },
      name = name,
      description = (dataNode["description"] as? String).orEmpty(),
      personality = (dataNode["personality"] as? String).orEmpty(),
      scenario = (dataNode["scenario"] as? String).orEmpty(),
      firstMessage = (dataNode["first_mes"] as? String).orEmpty(),
      messageExample = (dataNode["mes_example"] as? String).orEmpty(),
      systemPrompt = (dataNode["system_prompt"] as? String).orEmpty(),
      tags = tags,
    )
  }

  fun toV2Json(card: TavernCharacterCard): String {
    val payload = linkedMapOf<String, Any>(
      "spec" to "chara_card_v2",
      "spec_version" to "2.0",
      "data" to linkedMapOf(
        "name" to card.name,
        "description" to card.description,
        "personality" to card.personality,
        "scenario" to card.scenario,
        "first_mes" to card.firstMessage,
        "mes_example" to card.messageExample,
        "system_prompt" to card.systemPrompt,
        "tags" to card.tags,
      ),
    )
    return moshi.adapter(Map::class.java).toJson(payload)
  }

  /**
   * Reads a PNG binary byte array and extracts the `ccv3` or `chara` `tEXt` chunk
   * containing Base64-encoded Tavern character JSON.
   */
  fun parsePngBytes(pngBytes: ByteArray): TavernCharacterCard {
    require(pngBytes.size > 8 && pngBytes.copyOfRange(0, 8).contentEquals(pngSignature)) {
      "无效的 PNG 文件头"
    }
    var offset = 8
    var charaBase64: String? = null
    var ccv3Base64: String? = null

    while (offset + 12 <= pngBytes.size) {
      val length = ByteBuffer.wrap(pngBytes, offset, 4).int
      val chunkType = String(pngBytes, offset + 4, 4, StandardCharsets.US_ASCII)
      val dataStart = offset + 8
      val dataEnd = dataStart + length
      if (length < 0 || dataEnd + 4 > pngBytes.size) break

      if (chunkType == "tEXt") {
        val chunkData = pngBytes.copyOfRange(dataStart, dataEnd)
        val nulIdx = chunkData.indexOf(0.toByte())
        if (nulIdx > 0) {
          val keyword = String(chunkData, 0, nulIdx, StandardCharsets.ISO_8859_1)
          val value = String(chunkData, nulIdx + 1, chunkData.size - nulIdx - 1, StandardCharsets.ISO_8859_1).trim()
          if (keyword.equals("ccv3", ignoreCase = true)) {
            ccv3Base64 = value
          } else if (keyword.equals("chara", ignoreCase = true)) {
            charaBase64 = value
          }
        }
      }
      offset = dataEnd + 4
      if (chunkType == "IEND") break
    }

    val chosen = ccv3Base64 ?: charaBase64
      ?: throw IllegalArgumentException("PNG 中未包含酒馆角色卡元数据块 (chara / ccv3)")

    val decodedJson = String(Base64.getDecoder().decode(chosen), StandardCharsets.UTF_8)
    return parseJson(decodedJson)
  }

  /**
   * Embeds a Tavern V2 JSON character card into a valid minimal PNG with a `tEXt` (`chara`) chunk.
   */
  fun embedIntoMinimalPng(card: TavernCharacterCard): ByteArray {
    val json = toV2Json(card)
    val b64 = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    val out = ByteArrayOutputStream()
    out.write(pngSignature)

    // Minimal 1x1 IHDR
    val ihdr = ByteBuffer.allocate(13)
      .putInt(1) // width
      .putInt(1) // height
      .put(8) // bit depth
      .put(2) // RGB
      .put(0)
      .put(0)
      .put(0)
      .array()
    writeChunk(out, "IHDR", ihdr)

    // tEXt chunk: "chara\0<base64>"
    val textData = ByteArrayOutputStream().apply {
      write("chara".toByteArray(StandardCharsets.ISO_8859_1))
      write(0)
      write(b64.toByteArray(StandardCharsets.ISO_8859_1))
    }.toByteArray()
    writeChunk(out, "tEXt", textData)

    // IEND
    writeChunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
  }

  private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
    out.write(ByteBuffer.allocate(4).putInt(data.size).array())
    out.write(typeBytes)
    out.write(data)
    val crc = CRC32().apply {
      update(typeBytes)
      update(data)
    }
    out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
  }
}
