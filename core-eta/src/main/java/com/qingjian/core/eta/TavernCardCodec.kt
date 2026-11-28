package com.qingjian.core.eta

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Inflater

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
 * Pure Kotlin Tavern V1/V2/V3 JSON & PNG character card codec, upgraded with
 * Mangi-11/Eta `CharacterCardPng.kt` structural CRC validation, `tEXt` + `iTXt` + `zTXt`
 * extraction (`ccv3` preferred over `chara`), and dual V2/V3 PNG embedding.
 */
object TavernCardCodec {
  const val MAX_FILE_BYTES = 32 * 1024 * 1024
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val pngSignature = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
  )

  private data class PngChunk(val type: String, val data: ByteArray)

  @Suppress("UNCHECKED_CAST")
  fun parseJson(jsonText: String): TavernCharacterCard {
    val root = moshi.adapter(Map::class.java).fromJson(jsonText) as? Map<String, Any?>
      ?: throw IllegalArgumentException("角色卡 JSON 解析失败")

    val spec = (root["spec"] as? String)?.trim().orEmpty()
    val dataNode = (root["data"] as? Map<String, Any?>) ?: root
    val name = (dataNode["name"] as? String)?.trim().orEmpty()
    require(name.isNotBlank()) { "角色卡缺少 name 字段" }

    val tags = (dataNode["tags"] as? List<*>)
      ?.mapNotNull { it?.toString()?.trim() }
      ?.filter { it.isNotEmpty() }
      .orEmpty()

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

  fun toV2Json(card: TavernCharacterCard): String = exportJson(card, version = 2)

  fun toV3Json(card: TavernCharacterCard): String = exportJson(card, version = 3)

  private fun exportJson(card: TavernCharacterCard, version: Int): String {
    val spec = if (version >= 3) "chara_card_v3" else "chara_card_v2"
    val specVer = if (version >= 3) "3.0" else "2.0"
    val payload = linkedMapOf<String, Any>(
      "spec" to spec,
      "spec_version" to specVer,
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

  fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(pngSignature)

  /**
   * Reads a PNG binary byte array with full CRC validation (matching Eta `CharacterCardPng.kt`),
   * extracting Base64 JSON from `tEXt`, `iTXt`, or `zTXt` chunks (`ccv3` prioritized over `chara`).
   */
  fun parsePngBytes(pngBytes: ByteArray): TavernCharacterCard {
    val chunks = readAndValidateChunks(pngBytes)
    var charaBase64: String? = null
    var ccv3Base64: String? = null

    for (chunk in chunks) {
      val kv = when (chunk.type) {
        "tEXt" -> parseTextChunk(chunk.data)
        "iTXt" -> parseITxtChunk(chunk.data)
        "zTXt" -> parseZTxtChunk(chunk.data)
        else -> null
      } ?: continue
      val key = kv.first.lowercase()
      val value = kv.second.trim()
      if (key == "ccv3" && value.isNotEmpty()) ccv3Base64 = value
      if (key == "chara" && value.isNotEmpty()) charaBase64 = value
    }

    val chosen = ccv3Base64 ?: charaBase64
      ?: throw IllegalArgumentException("PNG 中未包含酒馆角色卡元数据块 (chara / ccv3)")

    val decodedJson = String(Base64.getMimeDecoder().decode(chosen), StandardCharsets.UTF_8)
    return parseJson(decodedJson)
  }

  /**
   * Embeds a Tavern character card into an existing valid PNG image (stripping old chara/ccv3
   * chunks and writing both V2 `chara` and V3 `ccv3` `tEXt` chunks before `IEND`).
   */
  fun writeIntoPng(sourcePng: ByteArray, card: TavernCharacterCard): ByteArray {
    val original = readAndValidateChunks(sourcePng)
    val out = ByteArrayOutputStream()
    out.write(pngSignature)
    DataOutputStream(out).use { stream ->
      original.forEach { chunk ->
        val keyword = if (chunk.type == "tEXt" || chunk.type == "iTXt" || chunk.type == "zTXt") {
          chunk.data.takeWhile { it != 0.toByte() }.toByteArray().toString(StandardCharsets.ISO_8859_1).lowercase()
        } else {
          ""
        }
        if (keyword in setOf("chara", "ccv3")) return@forEach
        if (chunk.type == "IEND") {
          val v2B64 = Base64.getEncoder().encode(toV2Json(card).toByteArray(StandardCharsets.UTF_8))
          val v3B64 = Base64.getEncoder().encode(toV3Json(card).toByteArray(StandardCharsets.UTF_8))
          writeChunk(stream, "tEXt", "chara".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) + v2B64)
          writeChunk(stream, "tEXt", "ccv3".toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) + v3B64)
        }
        writeChunk(stream, chunk.type, chunk.data)
      }
    }
    require(out.size() <= MAX_FILE_BYTES) { "PNG 角色卡超过 32 MiB 限制" }
    return out.toByteArray()
  }

  /**
   * Embeds a Tavern V2/V3 character card into a valid minimal 1x1 PNG (with IHDR, IDAT, tEXt, IEND).
   */
  fun embedIntoMinimalPng(card: TavernCharacterCard): ByteArray {
    val base = ByteArrayOutputStream()
    base.write(pngSignature)
    DataOutputStream(base).use { stream ->
      val ihdr = ByteBuffer.allocate(13)
        .putInt(1).putInt(1).put(8).put(2).put(0).put(0).put(0)
        .array()
      writeChunk(stream, "IHDR", ihdr)
      // Minimal valid zlib-compressed 1x1 RGB scanline (filter=0, R=31, G=122, B=110)
      val idat = byteArrayOf(
        0x78, 0x01, 0x63, 0xE8.toByte(), 0xB4.toByte(), 0xF5.toByte(), 0x03, 0x00, 0x02, 0x68, 0x01, 0x08,
      )
      writeChunk(stream, "IDAT", idat)
      writeChunk(stream, "IEND", ByteArray(0))
    }
    return writeIntoPng(base.toByteArray(), card)
  }

  private fun readAndValidateChunks(bytes: ByteArray): List<PngChunk> {
    require(bytes.size <= MAX_FILE_BYTES) { "角色卡图片超过 32 MiB 限制" }
    require(isPng(bytes)) { "无效的 PNG 文件头" }
    val result = mutableListOf<PngChunk>()
    var offset = 8
    while (offset < bytes.size) {
      require(bytes.size - offset >= 12) { "PNG 数据不完整" }
      val size = ByteBuffer.wrap(bytes, offset, 4).int
      require(size >= 0 && size <= bytes.size - offset - 12) { "PNG 数据块长度无效" }
      val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
      val data = bytes.copyOfRange(offset + 8, offset + 8 + size)
      val expectedCrc = ByteBuffer.wrap(bytes, offset + 8 + size, 4).int
      val actualCrc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
      require(actualCrc == expectedCrc) { "PNG 数据块 CRC 校验失败" }
      val type = typeBytes.toString(StandardCharsets.US_ASCII)
      result += PngChunk(type, data)
      offset += size + 12
      if (type == "IEND") break
    }
    require(result.firstOrNull()?.type == "IHDR" && result.lastOrNull()?.type == "IEND") {
      "PNG 结构不完整（缺少 IHDR 或 IEND）"
    }
    return result
  }

  private fun parseTextChunk(data: ByteArray): Pair<String, String>? {
    val nul = data.indexOf(0.toByte())
    if (nul <= 0) return null
    val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
    val value = String(data, nul + 1, data.size - nul - 1, StandardCharsets.ISO_8859_1)
    return key to value
  }

  private fun parseZTxtChunk(data: ByteArray): Pair<String, String>? {
    val nul = data.indexOf(0.toByte())
    if (nul <= 0 || nul + 2 >= data.size) return null
    val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
    if (data[nul + 1] != 0.toByte()) return null
    val compressed = data.copyOfRange(nul + 2, data.size)
    val inflated = inflateBytes(compressed) ?: return null
    return key to String(inflated, StandardCharsets.UTF_8)
  }

  private fun parseITxtChunk(data: ByteArray): Pair<String, String>? {
    val keyEnd = data.indexOf(0.toByte())
    if (keyEnd <= 0 || keyEnd + 4 >= data.size) return null
    val key = String(data, 0, keyEnd, StandardCharsets.ISO_8859_1)
    val compFlag = data[keyEnd + 1].toInt()
    // Skip compression method (keyEnd + 2), language tag\0, translated keyword\0
    var cursor = keyEnd + 3
    while (cursor < data.size && data[cursor] != 0.toByte()) cursor++
    cursor++
    while (cursor < data.size && data[cursor] != 0.toByte()) cursor++
    cursor++
    if (cursor > data.size) return null
    val textBytes = data.copyOfRange(cursor, data.size)
    val decoded = if (compFlag == 1) inflateBytes(textBytes) ?: return null else textBytes
    return key to String(decoded, StandardCharsets.UTF_8)
  }

  private fun inflateBytes(input: ByteArray): ByteArray? = runCatching {
    val inflater = Inflater()
    try {
      inflater.setInput(input)
      val out = ByteArrayOutputStream()
      val buf = ByteArray(4096)
      while (!inflater.finished()) {
        val count = inflater.inflate(buf)
        if (count <= 0) break
        out.write(buf, 0, count)
      }
      out.toByteArray()
    } finally {
      inflater.end()
    }
  }.getOrNull()

  private fun writeChunk(stream: DataOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
    stream.writeInt(data.size)
    stream.write(typeBytes)
    stream.write(data)
    val crc = CRC32().apply {
      update(typeBytes)
      update(data)
    }
    stream.writeInt(crc.value.toInt())
  }
}
