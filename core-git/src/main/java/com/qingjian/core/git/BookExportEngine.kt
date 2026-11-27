package com.qingjian.core.git

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportableChapter(
  val id: String,
  val volumeTitle: String,
  val title: String,
  val order: Int,
  val markdownContent: String,
)

data class ExportableBook(
  val id: String,
  val title: String,
  val author: String,
  val synopsis: String,
  val chapters: List<ExportableChapter>,
)

enum class ExportFormat(val label: String, val extension: String) {
  JSON("JSON 全量数据包", "json"),
  MARKDOWN("Markdown 合集", "md"),
  TXT("纯文本 TXT", "txt"),
  EPUB("电子书 EPUB", "epub"),
}

/**
 * Multi-format book export engine supporting JSON, Markdown, TXT, and spec-compliant EPUB 2/3 ZIP.
 */
object BookExportEngine {
  fun exportBook(book: ExportableBook, format: ExportFormat, outputDir: File): File {
    if (!outputDir.exists()) outputDir.mkdirs()
    val safeName = book.title.replace(Regex("[^\\p{L}\\p{N}_\\-]"), "_").ifBlank { "book" }
    val outFile = File(outputDir, "${safeName}_${System.currentTimeMillis()}.${format.extension}")
    when (format) {
      ExportFormat.JSON -> outFile.writeText(renderJson(book), StandardCharsets.UTF_8)
      ExportFormat.MARKDOWN -> outFile.writeText(renderMarkdown(book), StandardCharsets.UTF_8)
      ExportFormat.TXT -> outFile.writeText(renderPlainText(book), StandardCharsets.UTF_8)
      ExportFormat.EPUB -> writeEpub(book, outFile)
    }
    return outFile
  }

  fun renderMarkdown(book: ExportableBook): String = buildString {
    appendLine("# ${book.title}")
    appendLine()
    appendLine("作者：${book.author}")
    if (book.synopsis.isNotBlank()) {
      appendLine()
      appendLine("> ${book.synopsis.replace("\n", "\n> ")}")
    }
    appendLine()
    var lastVolume = ""
    book.chapters.sortedBy { it.order }.forEach { ch ->
      if (ch.volumeTitle.isNotBlank() && ch.volumeTitle != lastVolume) {
        appendLine("## ${ch.volumeTitle}")
        appendLine()
        lastVolume = ch.volumeTitle
      }
      appendLine("### ${ch.title}")
      appendLine()
      appendLine(ch.markdownContent.trim())
      appendLine()
      appendLine("---")
      appendLine()
    }
  }

  fun renderPlainText(book: ExportableBook): String = buildString {
    appendLine(book.title)
    appendLine("作者：${book.author}")
    appendLine()
    book.chapters.sortedBy { it.order }.forEach { ch ->
      appendLine("${ch.volumeTitle} · ${ch.title}")
      appendLine()
      val stripped = ch.markdownContent
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("~~(.+?)~~"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("(?m)^>\\s?"), "")
      appendLine(stripped.trim())
      appendLine()
      appendLine()
    }
  }

  fun renderJson(book: ExportableBook): String {
    fun esc(s: String): String = s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

    val chaptersJson = book.chapters.sortedBy { it.order }.joinToString(",\n") { ch ->
      """
      |    {
      |      "id": "${esc(ch.id)}",
      |      "volumeTitle": "${esc(ch.volumeTitle)}",
      |      "title": "${esc(ch.title)}",
      |      "order": ${ch.order},
      |      "markdownContent": "${esc(ch.markdownContent)}"
      |    }
      """.trimMargin()
    }
    return """
      |{
      |  "format": "qingjian-book-v1",
      |  "id": "${esc(book.id)}",
      |  "title": "${esc(book.title)}",
      |  "author": "${esc(book.author)}",
      |  "synopsis": "${esc(book.synopsis)}",
      |  "chapters": [
      |$chaptersJson
      |  ]
      |}
    """.trimMargin()
  }

  fun writeEpub(book: ExportableBook, outFile: File) {
    val bookUuid = "urn:uuid:${UUID.nameUUIDFromBytes(book.id.toByteArray())}"
    val sorted = book.chapters.sortedBy { it.order }

    ZipOutputStream(FileOutputStream(outFile)).use { zos ->
      // 1. Uncompressed mimetype must be first entry per EPUB spec
      val mimeBytes = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
      val mimeEntry = ZipEntry("mimetype").apply {
        method = ZipEntry.STORED
        size = mimeBytes.size.toLong()
        compressedSize = mimeBytes.size.toLong()
        val checksum = CRC32().apply { update(mimeBytes) }
        crc = checksum.value
      }
      zos.putNextEntry(mimeEntry)
      zos.write(mimeBytes)
      zos.closeEntry()

      // 2. META-INF/container.xml
      writeZipString(
        zos,
        "META-INF/container.xml",
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""",
      )

      // 3. OEBPS/style.css
      writeZipString(
        zos,
        "OEBPS/style.css",
        """body { font-family: serif; line-height: 1.8; margin: 1.2em; }
h1, h2 { font-weight: 600; margin-top: 1.5em; }
p { text-indent: 2em; margin: 0.6em 0; }
hr { border: none; border-top: 1px solid #888; margin: 1.5em auto; width: 40%; }""",
      )

      // 4. Chapter XHTML files
      sorted.forEachIndexed { idx, ch ->
        val paragraphsHtml = ch.markdownContent
          .split(Regex("\\n\\s*\\n"))
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .joinToString("\n") { block ->
            when {
              block == "---" || block == "***" -> "  <hr/>"
              block.startsWith("## ") -> "  <h2>${escapeXml(block.removePrefix("## ").trim())}</h2>"
              block.startsWith("# ") -> "  <h1>${escapeXml(block.removePrefix("# ").trim())}</h1>"
              else -> "  <p>${escapeXml(block).replace("\n", "<br/>")}</p>"
            }
          }
        writeZipString(
          zos,
          "OEBPS/ch_${idx + 1}.xhtml",
          """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <title>${escapeXml(ch.title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h1>${escapeXml(ch.title)}</h1>
$paragraphsHtml
</body>
</html>""",
        )
      }

      // 5. OEBPS/toc.ncx
      val navPoints = sorted.mapIndexed { idx, ch ->
        """    <navPoint id="navPoint-${idx + 1}" playOrder="${idx + 1}">
      <navLabel><text>${escapeXml(ch.title)}</text></navLabel>
      <content src="ch_${idx + 1}.xhtml"/>
    </navPoint>"""
      }.joinToString("\n")

      writeZipString(
        zos,
        "OEBPS/toc.ncx",
        """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$bookUuid"/>
  </head>
  <docTitle><text>${escapeXml(book.title)}</text></docTitle>
  <navMap>
$navPoints
  </navMap>
</ncx>""",
      )

      // 6. OEBPS/content.opf
      val manifestItems = sorted.mapIndexed { idx, _ ->
        """    <item id="ch_${idx + 1}" href="ch_${idx + 1}.xhtml" media-type="application/xhtml+xml"/>"""
      }.joinToString("\n")
      val spineRefs = sorted.mapIndexed { idx, _ ->
        """    <itemref idref="ch_${idx + 1}"/>"""
      }.joinToString("\n")

      writeZipString(
        zos,
        "OEBPS/content.opf",
        """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeXml(book.title)}</dc:title>
    <dc:creator>${escapeXml(book.author)}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId">$bookUuid</dc:identifier>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
$manifestItems
  </manifest>
  <spine toc="ncx">
$spineRefs
  </spine>
</package>""",
      )
    }
  }

  private fun writeZipString(zos: ZipOutputStream, entryName: String, content: String) {
    zos.putNextEntry(ZipEntry(entryName))
    zos.write(content.toByteArray(StandardCharsets.UTF_8))
    zos.closeEntry()
  }

  private fun escapeXml(input: String): String = input
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
}
