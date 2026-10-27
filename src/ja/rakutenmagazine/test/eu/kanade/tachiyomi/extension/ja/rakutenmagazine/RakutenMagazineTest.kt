package eu.kanade.tachiyomi.extension.ja.rakutenmagazine

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.Base64

class RakutenMagazineTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun testDecryptDocumentInfoAndAvedProj() {
        val docInfoFile = File("/tmp/rakuten_inspect/complete_docInfo.proj")
        val avedFile = File("/tmp/rakuten_inspect/complete_AVED0.proj")
        if (!docInfoFile.exists() || !avedFile.exists()) return

        val rawDocInfo = docInfoFile.readBytes()
        val headerEnd = String(rawDocInfo, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        val bodyDocInfo = if (headerEnd >= 0) {
            rawDocInfo.copyOfRange(headerEnd + 4, rawDocInfo.size)
        } else {
            rawDocInfo
        }

        val passphrase = Base64.getDecoder().decode("TFhiSndMdTdsTlM3THBoOA==")
        assertTrue(RakutenMagazineCrypto.isSaltedOpenSsl(bodyDocInfo))

        val decryptedDocInfo = String(
            RakutenMagazineCrypto.decryptOpenSslAes256Cbc(bodyDocInfo, passphrase),
            Charsets.UTF_8,
        )
        val docInfo = json.decodeFromString<AquafadasDocumentInfo>(decryptedDocInfo)
        val readers = docInfo.readers.orEmpty()
        assertEquals(1, readers.size)
        assertEquals("PDF", readers[0].type)
        assertEquals(92, readers[0].pageCount)
        assertEquals("AVED0.proj", readers[0].fileName)

        val bodyAved = avedFile.readBytes()
        assertTrue(RakutenMagazineCrypto.isSaltedOpenSsl(bodyAved))
        val decryptedAved = String(
            RakutenMagazineCrypto.decryptOpenSslAes256Cbc(bodyAved, passphrase),
            Charsets.UTF_8,
        )
        val avedProj = json.decodeFromString<AquafadasAvedProj>(decryptedAved)
        val flatPages = avedProj.pages.orEmpty().flatMap { it.pages.orEmpty() }
        assertEquals(92, flatPages.size)
        assertEquals("AVED0_A0_L0_P00.pdf", flatPages[0].fileName)
        assertEquals("AVED0_A0_L0_P91.pdf", flatPages.last().fileName)
    }

    @Test
    fun testExtractJpegFromCompletePdfs() {
        for (fileName in listOf("complete_p0_pdf.bin", "complete_p5_pdf.bin", "complete_p50_pdf.bin")) {
            val f = File("/tmp/rakuten_inspect/$fileName")
            if (!f.exists()) continue
            val pdfBytes = f.readBytes()
            val jpegBytes = RakutenMagazineCrypto.extractJpegFromAquafadasPdf(pdfBytes)
            assertNotNull("Failed to extract JPEG from $fileName", jpegBytes)
            assertTrue("JPEG too small in $fileName", jpegBytes!!.size > 100_000)
            // Verify JPEG SOI (FF D8) and EOI (FF D9)
            assertEquals(0xFF.toByte(), jpegBytes[0])
            assertEquals(0xD8.toByte(), jpegBytes[1])
            assertEquals(0xFF.toByte(), jpegBytes[jpegBytes.size - 2])
            assertEquals(0xD9.toByte(), jpegBytes[jpegBytes.size - 1])
        }
    }

    @Test
    fun testLiveCatalogAndTitleBacknumbersParse() {
        val latestJson = URI("https://magazine.rakuten.co.jp/json/v1.0/latestIssues").toURL().readText()
        val latest = json.decodeFromString<LatestIssuesResponse>(latestJson)
        val issues = latest.latestIssues.orEmpty()
        assertTrue("Expected >1000 issues in catalog", issues.size > 1000)

        val sample = issues.first()
        assertTrue(!sample.titleIdentifier.isNullOrEmpty())
        assertTrue(!sample.issueId.isNullOrEmpty())

        val relatedJson = URI("https://magazine.rakuten.co.jp/title/${sample.titleIdentifier}/relatedIssue.json").toURL().readText()
        val related = json.decodeFromString<RelatedIssuesResponse>(relatedJson)
        assertTrue("Expected at least 1 issue in backNumber", !related.backNumber.isNullOrEmpty())
    }
}
