package dev.toros.yde.app

import dev.toros.yde.app.ai.AiHttp
import dev.toros.yde.app.ui.GraphLayouts
import dev.toros.yde.app.ui.LensEdgeSpec
import dev.toros.yde.app.ui.LensLayoutKind
import dev.toros.yde.app.ui.LensNodeSize
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 同梱ランタイムと配布 JAR だけで実行するスモーク検査。外部 API には接続しない。 */
object DistributionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        AiHttp.client().close()
        val xlsx = ByteArrayOutputStream()
        XSSFWorkbook().use { book ->
            book.createSheet("test").createRow(0).createCell(0).setCellValue("日本語")
            book.write(xlsx)
        }
        XSSFWorkbook(ByteArrayInputStream(xlsx.toByteArray())).use {
            check(it.getSheetAt(0).getRow(0).getCell(0).stringCellValue == "日本語")
        }
        val docx = ByteArrayOutputStream()
        XWPFDocument().use { doc -> doc.createParagraph().createRun().setText("日本語"); doc.write(docx) }
        XWPFDocument(ByteArrayInputStream(docx.toByteArray())).use { check(it.paragraphs.single().text == "日本語") }
        for (kind in LensLayoutKind.entries) {
            val result = GraphLayouts.arrange(listOf(LensNodeSize("a", 100f, 40f), LensNodeSize("b", 100f, 40f)),
                listOf(LensEdgeSpec(0, 1)), kind, origin = 0)
            check(result.kind == kind && result.note == null && result.boxes.size == 2)
        }
        check(javaClass.getResource("/META-INF/LICENSE") != null)
        check(javaClass.getResource("/fonts/cica/LICENSE.txt") != null)
        check(javaClass.getResource("/help/limits.md") != null)
        println("同梱ランタイム: HTTP クライアント・Office 往復・グラフ配置・同梱資源 OK")
    }
}
