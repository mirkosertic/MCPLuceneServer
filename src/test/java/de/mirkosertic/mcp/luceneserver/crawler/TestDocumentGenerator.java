package de.mirkosertic.mcp.luceneserver.crawler;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility class to generate test documents in various formats.
 * Uses Apache POI for Office formats, PDFBox for PDF.
 * ODT/ODS files are created manually as ZIP archives with XML content.
 */
public final class TestDocumentGenerator {

    public static final String TEST_CONTENT = "This is test content for document extraction verification.";
    public static final String TEST_TITLE = "Test Document Title";
    public static final String TEST_AUTHOR = "Test Author";

    /**
     * Creates a plain text file with test content.
     */
    public static void createTxtFile(final Path path) throws IOException {
        Files.writeString(path, TEST_CONTENT, StandardCharsets.UTF_8);
    }

    /**
     * Creates a PDF file with test content using PDFBox.
     */
    public static void createPdfFile(final Path path) throws IOException {
        try (final PDDocument document = new PDDocument()) {
            final PDPage page = new PDPage();
            document.addPage(page);

            // Set document metadata
            document.getDocumentInformation().setTitle(TEST_TITLE);
            document.getDocumentInformation().setAuthor(TEST_AUTHOR);

            try (final PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(TEST_CONTENT);
                contentStream.endText();
            }

            document.save(path.toFile());
        }
    }

    /**
     * Creates a DOCX file with test content using Apache POI.
     */
    public static void createDocxFile(final Path path) throws IOException {
        try (final XWPFDocument document = new XWPFDocument()) {
            // Add metadata via core properties
            document.getProperties().getCoreProperties().setTitle(TEST_TITLE);
            document.getProperties().getCoreProperties().setCreator(TEST_AUTHOR);

            final XWPFParagraph paragraph = document.createParagraph();
            final XWPFRun run = paragraph.createRun();
            run.setText(TEST_CONTENT);

            try (final FileOutputStream out = new FileOutputStream(path.toFile())) {
                document.write(out);
            }
        }
    }

    /**
     * Creates a DOC file (legacy Word format).
     * Note: Apache POI HWPF doesn't support creating new DOC files from scratch.
     * We create a minimal RTF file which Word and Tika can read.
     */
    public static void createDocFile(final Path path) throws IOException {
        // RTF format is compatible with .doc and Tika can parse it
        final String rtfContent = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Helvetica;}}"
                + "{\\info{\\title " + TEST_TITLE + "}{\\author " + TEST_AUTHOR + "}}"
                + "\\f0\\fs24 " + TEST_CONTENT + "}";
        Files.writeString(path, rtfContent, StandardCharsets.US_ASCII);
    }

    /**
     * Creates an XLSX file with test content using Apache POI.
     */
    public static void createXlsxFile(final Path path) throws IOException {
        try (final XSSFWorkbook workbook = new XSSFWorkbook()) {
            // Set metadata
            workbook.getProperties().getCoreProperties().setTitle(TEST_TITLE);
            workbook.getProperties().getCoreProperties().setCreator(TEST_AUTHOR);

            final Sheet sheet = workbook.createSheet("Test Sheet");
            final Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(TEST_CONTENT);

            try (final FileOutputStream out = new FileOutputStream(path.toFile())) {
                workbook.write(out);
            }
        }
    }

    /**
     * Creates an XLS file (legacy Excel format) with test content using Apache POI.
     */
    public static void createXlsFile(final Path path) throws IOException {
        try (final HSSFWorkbook workbook = new HSSFWorkbook()) {
            workbook.createInformationProperties();
            workbook.getSummaryInformation().setTitle(TEST_TITLE);
            workbook.getSummaryInformation().setAuthor(TEST_AUTHOR);

            final Sheet sheet = workbook.createSheet("Test Sheet");
            final Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(TEST_CONTENT);

            try (final FileOutputStream out = new FileOutputStream(path.toFile())) {
                workbook.write(out);
            }
        }
    }

    /**
     * Creates a PPTX file with test content using Apache POI.
     */
    public static void createPptxFile(final Path path) throws IOException {
        try (final XMLSlideShow ppt = new XMLSlideShow()) {
            // Set metadata
            ppt.getProperties().getCoreProperties().setTitle(TEST_TITLE);
            ppt.getProperties().getCoreProperties().setCreator(TEST_AUTHOR);

            final XSLFSlide slide = ppt.createSlide();
            final XSLFTextBox textBox = slide.createTextBox();
            textBox.setText(TEST_CONTENT);
            textBox.setAnchor(new java.awt.Rectangle(50, 50, 500, 100));

            try (final FileOutputStream out = new FileOutputStream(path.toFile())) {
                ppt.write(out);
            }
        }
    }

    /**
     * Creates a PPT file (legacy PowerPoint format) with test content using Apache POI.
     */
    public static void createPptFile(final Path path) throws IOException {
        try (final HSLFSlideShow ppt = new HSLFSlideShow()) {
            ppt.createSlide();
            final var slide = ppt.getSlides().getFirst();

            final HSLFTextBox textBox = slide.createTextBox();
            textBox.setText(TEST_CONTENT);
            textBox.setAnchor(new java.awt.Rectangle(50, 50, 500, 100));

            try (final FileOutputStream out = new FileOutputStream(path.toFile())) {
                ppt.write(out);
            }
        }
    }

    /**
     * Creates an ODT file (OpenDocument Text) with test content.
     * ODT is a ZIP file with XML content following the ODF standard.
     */
    public static void createOdtFile(final Path path) throws IOException {
        try (final OutputStream fos = Files.newOutputStream(path);
             final ZipOutputStream zos = new ZipOutputStream(fos)) {

            // mimetype must be first and uncompressed
            zos.setMethod(ZipOutputStream.STORED);
            final byte[] mimeBytes = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
            final ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setSize(mimeBytes.length);
            mimeEntry.setCompressedSize(mimeBytes.length);
            mimeEntry.setCrc(calculateCrc32(mimeBytes));
            zos.putNextEntry(mimeEntry);
            zos.write(mimeBytes);
            zos.closeEntry();

            // Switch to DEFLATED for remaining entries
            zos.setMethod(ZipOutputStream.DEFLATED);

            // META-INF/manifest.xml
            final String manifest = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                      <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
                      <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                    </manifest:manifest>
                    """;
            addZipEntry(zos, "META-INF/manifest.xml", manifest);

            // meta.xml with document metadata
            final String meta = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                          xmlns:dc="http://purl.org/dc/elements/1.1/"
                                          xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
                                          office:version="1.2">
                      <office:meta>
                        <dc:title>%s</dc:title>
                        <dc:creator>%s</dc:creator>
                      </office:meta>
                    </office:document-meta>
                    """.formatted(TEST_TITLE, TEST_AUTHOR);
            addZipEntry(zos, "meta.xml", meta);

            // content.xml with the actual text content
            final String content = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                             xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                             office:version="1.2">
                      <office:body>
                        <office:text>
                          <text:p>%s</text:p>
                        </office:text>
                      </office:body>
                    </office:document-content>
                    """.formatted(TEST_CONTENT);
            addZipEntry(zos, "content.xml", content);
        }
    }

    /**
     * Creates an ODS file (OpenDocument Spreadsheet) with test content.
     * ODS is a ZIP file with XML content following the ODF standard.
     */
    public static void createOdsFile(final Path path) throws IOException {
        try (final OutputStream fos = Files.newOutputStream(path);
             final ZipOutputStream zos = new ZipOutputStream(fos)) {

            // mimetype must be first and uncompressed
            zos.setMethod(ZipOutputStream.STORED);
            final byte[] mimeBytes = "application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.UTF_8);
            final ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setSize(mimeBytes.length);
            mimeEntry.setCompressedSize(mimeBytes.length);
            mimeEntry.setCrc(calculateCrc32(mimeBytes));
            zos.putNextEntry(mimeEntry);
            zos.write(mimeBytes);
            zos.closeEntry();

            // Switch to DEFLATED for remaining entries
            zos.setMethod(ZipOutputStream.DEFLATED);

            // META-INF/manifest.xml
            final String manifest = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                      <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                      <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                    </manifest:manifest>
                    """;
            addZipEntry(zos, "META-INF/manifest.xml", manifest);

            // meta.xml with document metadata
            final String meta = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                          xmlns:dc="http://purl.org/dc/elements/1.1/"
                                          xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
                                          office:version="1.2">
                      <office:meta>
                        <dc:title>%s</dc:title>
                        <dc:creator>%s</dc:creator>
                      </office:meta>
                    </office:document-meta>
                    """.formatted(TEST_TITLE, TEST_AUTHOR);
            addZipEntry(zos, "meta.xml", meta);

            // content.xml with spreadsheet content
            final String content = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                             xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                                             xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                             office:version="1.2">
                      <office:body>
                        <office:spreadsheet>
                          <table:table table:name="Sheet1">
                            <table:table-row>
                              <table:table-cell office:value-type="string">
                                <text:p>%s</text:p>
                              </table:table-cell>
                            </table:table-row>
                          </table:table>
                        </office:spreadsheet>
                      </office:body>
                    </office:document-content>
                    """.formatted(TEST_CONTENT);
            addZipEntry(zos, "content.xml", content);
        }
    }

    private static void addZipEntry(final ZipOutputStream zos, final String name, final String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * Creates an EML file (RFC 822 email format) with test content.
     */
    public static void createEmlFile(final Path path) throws IOException {
        final String emlContent = "From: " + TEST_AUTHOR + " <test@example.com>\r\n"
                + "To: recipient@example.com\r\n"
                + "Subject: " + TEST_TITLE + "\r\n"
                + "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + TEST_CONTENT;
        Files.writeString(path, emlContent, StandardCharsets.UTF_8);
    }

    /**
     * Creates an MSG file (Outlook message format) with test content.
     * MSG files are OLE2 compound documents with specific property streams.
     */
    public static void createMsgFile(final Path path) throws IOException {
        // MSG files are OLE2 compound documents. Create a minimal one using POI.
        try (final org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            // MSG properties are stored in specific streams
            // Subject property (0x0037001F) - Unicode string
            // Body property (0x1000001F) - Unicode string
            final byte[] subjectBytes = TEST_TITLE.getBytes(StandardCharsets.UTF_16LE);
            fs.createDocument(new java.io.ByteArrayInputStream(subjectBytes), "__substg1.0_0037001F");

            final byte[] bodyBytes = TEST_CONTENT.getBytes(StandardCharsets.UTF_16LE);
            fs.createDocument(new java.io.ByteArrayInputStream(bodyBytes), "__substg1.0_1000001F");

            // Sender name property (0x0C1A001F)
            final byte[] senderBytes = TEST_AUTHOR.getBytes(StandardCharsets.UTF_16LE);
            fs.createDocument(new java.io.ByteArrayInputStream(senderBytes), "__substg1.0_0C1A001F");

            // Properties stream (required for Tika to recognize as MSG)
            // Minimal properties header: 32 bytes reserved + property entries
            final byte[] propsHeader = new byte[32]; // Reserved/header bytes
            fs.createDocument(new java.io.ByteArrayInputStream(propsHeader), "__properties_version1.0");

            try (final OutputStream out = Files.newOutputStream(path)) {
                fs.writeFilesystem(out);
            }
        }
    }

    /**
     * Creates a Markdown file with test content.
     */
    public static void createMdFile(final Path path) throws IOException {
        final String mdContent = "# " + TEST_TITLE + "\n\n"
                + "**Author:** " + TEST_AUTHOR + "\n\n"
                + TEST_CONTENT + "\n";
        Files.writeString(path, mdContent, StandardCharsets.UTF_8);
    }

    /**
     * Creates a reStructuredText file with test content.
     */
    public static void createRstFile(final Path path) throws IOException {
        final String rstContent = TEST_TITLE + "\n"
                + "=".repeat(TEST_TITLE.length()) + "\n\n"
                + ":Author: " + TEST_AUTHOR + "\n\n"
                + TEST_CONTENT + "\n";
        Files.writeString(path, rstContent, StandardCharsets.UTF_8);
    }

    /**
     * Creates an HTML file with test content.
     */
    public static void createHtmlFile(final Path path) throws IOException {
        final String htmlContent = "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "  <title>" + TEST_TITLE + "</title>\n"
                + "  <meta name=\"author\" content=\"" + TEST_AUTHOR + "\">\n"
                + "</head>\n"
                + "<body>\n"
                + "  <p>" + TEST_CONTENT + "</p>\n"
                + "</body>\n"
                + "</html>";
        Files.writeString(path, htmlContent, StandardCharsets.UTF_8);
    }

    /**
     * Creates an RTF file with test content.
     */
    public static void createRtfFile(final Path path) throws IOException {
        final String rtfContent = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Helvetica;}}"
                + "{\\info{\\title " + TEST_TITLE + "}{\\author " + TEST_AUTHOR + "}}"
                + "\\f0\\fs24 " + TEST_CONTENT + "}";
        Files.writeString(path, rtfContent, StandardCharsets.US_ASCII);
    }

    /**
     * Creates an EPUB file with test content.
     * EPUB is a ZIP file with OPF metadata and XHTML content.
     */
    public static void createEpubFile(final Path path) throws IOException {
        try (final OutputStream fos = Files.newOutputStream(path);
             final ZipOutputStream zos = new ZipOutputStream(fos)) {

            // mimetype must be first and uncompressed (EPUB spec)
            zos.setMethod(ZipOutputStream.STORED);
            final byte[] mimeBytes = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
            final ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setSize(mimeBytes.length);
            mimeEntry.setCompressedSize(mimeBytes.length);
            mimeEntry.setCrc(calculateCrc32(mimeBytes));
            zos.putNextEntry(mimeEntry);
            zos.write(mimeBytes);
            zos.closeEntry();

            zos.setMethod(ZipOutputStream.DEFLATED);

            // META-INF/container.xml
            final String container = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """;
            addZipEntry(zos, "META-INF/container.xml", container);

            // content.opf (OPF package with metadata)
            final String opf = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="uid">test-epub-001</dc:identifier>
                        <dc:title>%s</dc:title>
                        <dc:creator>%s</dc:creator>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest>
                        <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="chapter1"/>
                      </spine>
                    </package>
                    """.formatted(TEST_TITLE, TEST_AUTHOR);
            addZipEntry(zos, "content.opf", opf);

            // chapter1.xhtml (actual content)
            final String chapter = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>%s</title></head>
                    <body>
                      <h1>%s</h1>
                      <p>%s</p>
                    </body>
                    </html>
                    """.formatted(TEST_TITLE, TEST_TITLE, TEST_CONTENT);
            addZipEntry(zos, "chapter1.xhtml", chapter);
        }
    }

    private static long calculateCrc32(final byte[] data) {
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
