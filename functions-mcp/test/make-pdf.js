// Builds a 2-page test plan PDF (11x17 landscape) with pdfkit.
const PDFDocument = require("pdfkit"); const fs = require("fs");
const doc = new PDFDocument({ size: [1224, 792], autoFirstPage: true });
doc.pipe(fs.createWriteStream(process.argv[2]));
doc.lineWidth(4).rect(144, 144, 12 * 18, 10 * 18).stroke(); // 12'x10' at 1/4"=1'
doc.fontSize(24).text("KITCHEN", 200, 220);
doc.addPage({ size: [612, 792] }); doc.fontSize(30).text("Page 2", 100, 100);
doc.end();
