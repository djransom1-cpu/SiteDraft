package com.example.sitedraft.platform

import com.example.sitedraft.util.formatFixed

/**
 * A minimal single-page PDF holding one RGB image, for platforms without a PDF library. The
 * caller supplies the pixel data already compressed with Flate (zlib), or uncompressed when
 * no zlib is at hand ([flate] = false); either is valid PDF.
 */
object PdfWriter {
    /** One page: [width] x [height] image pixels shown on a [pageWidthPt] x [pageHeightPt] page. */
    class ImagePage(
        val width: Int,
        val height: Int,
        val rgb: ByteArray,
        val flate: Boolean,
        val pageWidthPt: Float = width.toFloat(),
        val pageHeightPt: Float = height.toFloat()
    )

    /**
     * [width] x [height] image pixels on a page of [pageWidthPt] x [pageHeightPt] PDF points
     * (1/72 in); by default one pixel per point.
     */
    fun singleImagePage(
        width: Int,
        height: Int,
        rgb: ByteArray,
        flate: Boolean,
        pageWidthPt: Float = width.toFloat(),
        pageHeightPt: Float = height.toFloat()
    ): ByteArray = imagePages(listOf(ImagePage(width, height, rgb, flate, pageWidthPt, pageHeightPt)))

    /** A PDF of several pages (a sheet set), one image each, each page its own paper size. */
    fun imagePages(pages: List<ImagePage>): ByteArray {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val objects = ArrayList<ByteArray>()
        fun obj(text: String) = objects.add(text.encodeToByteArray())
        // 1 catalog, 2 page tree, then for page i: 3+3i the page, 4+3i its content, 5+3i its image.
        val kids = pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        pages.forEachIndexed { i, p ->
            val pw = formatFixed(p.pageWidthPt.toDouble(), 2)
            val ph = formatFixed(p.pageHeightPt.toDouble(), 2)
            val pageObj = 3 + 3 * i
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> >> /Contents ${pageObj + 1} 0 R >>")
            val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
            obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            val filter = if (p.flate) " /Filter /FlateDecode" else ""
            val header = "<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} /ColorSpace /DeviceRGB /BitsPerComponent 8$filter /Length ${p.rgb.size} >>\nstream\n".encodeToByteArray()
            objects.add(header + p.rgb + "\nendstream".encodeToByteArray())
        }

        val out = ArrayList<Byte>(pages.sumOf { it.rgb.size } + 1024 * pages.size)
        fun write(bytes: ByteArray) { for (b in bytes) out.add(b) }
        fun write(text: String) = write(text.encodeToByteArray())
        write("%PDF-1.4\n%âãÏÓ\n")
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { i, body ->
            offsets[i] = out.size
            write("${i + 1} 0 obj\n")
            write(body)
            write("\nendobj\n")
        }
        val xref = out.size
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) write(o.toString().padStart(10, '0') + " 00000 n \n")
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
