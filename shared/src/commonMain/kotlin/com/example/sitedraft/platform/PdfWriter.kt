package com.example.sitedraft.platform

/**
 * A minimal single-page PDF holding one RGB image, for platforms without a PDF library. The
 * caller supplies the pixel data already compressed with Flate (zlib), or uncompressed when
 * no zlib is at hand ([flate] = false); either is valid PDF.
 */
object PdfWriter {
    fun singleImagePage(width: Int, height: Int, rgb: ByteArray, flate: Boolean): ByteArray {
        val objects = ArrayList<ByteArray>()
        fun obj(text: String) = objects.add(text.encodeToByteArray())
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>")
        val content = "q $width 0 0 $height 0 0 cm /Im0 Do Q"
        obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val filter = if (flate) " /Filter /FlateDecode" else ""
        val header = "<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8$filter /Length ${rgb.size} >>\nstream\n".encodeToByteArray()
        objects.add(header + rgb + "\nendstream".encodeToByteArray())

        val out = ArrayList<Byte>(rgb.size + 1024)
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
