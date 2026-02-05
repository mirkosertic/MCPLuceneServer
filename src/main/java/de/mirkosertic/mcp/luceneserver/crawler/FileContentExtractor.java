package de.mirkosertic.mcp.luceneserver.crawler;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts text content and metadata from documents using Apache Tika.
 * Supports PDF, Office documents, OpenOffice documents, and plain text.
 */
public class FileContentExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FileContentExtractor.class);

    private final ApplicationConfig config;
    private final Tika tika;
    private final Parser parser;
    private final LanguageDetector languageDetector;

    public FileContentExtractor(final ApplicationConfig config) {
        this.config = config;
        this.tika = new Tika();
        this.parser = new AutoDetectParser();
        this.languageDetector = new OptimaizeLangDetector().loadModels();
    }

    public ExtractedDocument extract(final Path file) throws IOException {
        final long fileSize = Files.size(file);

        try (final InputStream stream = Files.newInputStream(file)) {
            // Create metadata object
            final Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());

            // Create content handler with max length limit
            // -1 means unlimited (extract full content regardless of size)
            final BodyContentHandler handler;
            if (config.getMaxContentLength() <= 0) {
                handler = new BodyContentHandler(-1);
                logger.debug("Using unlimited content extraction for file: {}", file);
            } else {
                handler = new BodyContentHandler((int) config.getMaxContentLength());
            }

            // Parse the document
            final ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            try {
                parser.parse(stream, handler, metadata, context);
            } catch (final SAXException | TikaException e) {
                logger.error("Error parsing file: {}", file, e);
                throw new IOException("Failed to parse document", e);
            }

            // Extract content
            final String content = handler.toString();
            logger.debug("Extracted {} characters from file: {}", content.length(), file);

            // Extract metadata if enabled
            final Map<String, String> metadataMap = new HashMap<>();
            if (config.isExtractMetadata()) {
                for (final String name : metadata.names()) {
                    final String value = metadata.get(name);
                    if (value != null && !value.isEmpty()) {
                        metadataMap.put(name, value);
                    }
                }
            }

            // Detect language if enabled
            String detectedLanguage = null;
            if (config.isDetectLanguage() && !content.isEmpty()) {
                try {
                    final LanguageResult result = languageDetector.detect(content);
                    if (result.isReasonablyCertain()) {
                        detectedLanguage = result.getLanguage();
                    }
                } catch (final Exception e) {
                    logger.warn("Language detection failed for file: {}", file, e);
                }
            }

            // Detect MIME type
            final String fileType = tika.detect(file);

            final String normalizedContent = normalizeContent(content);

            return new ExtractedDocument(
                    normalizedContent,
                    metadataMap,
                    detectedLanguage,
                    fileType,
                    fileSize
            );

        } catch (final IOException e) {
            logger.error("I/O error reading file: {}", file, e);
            throw e;
        }
    }

    /**
     * Normalize extracted text content to clean up artifacts common in PDF and Office extraction.
     *
     * <p>Steps applied in order:</p>
     * <ol>
     *   <li>HTML entity decoding – converts numeric entities ({@code &#123;}, {@code &#x7B;})
     *       and common named entities ({@code &amp;}, {@code &lt;}, {@code &gt;}, etc.)
     *       to their Unicode equivalents.</li>
     *   <li>URL decoding – converts percent-encoded sequences ({@code %XX}) including
     *       multi-byte UTF-8 sequences to their Unicode equivalents.</li>
     *   <li>NFKC Unicode normalization – expands compatibility characters such as
     *       ligatures (fi-ligature → fi, fl-ligature → fl) and full-width forms.</li>
     *   <li>Control-character removal – strips U+0000–U+0008, U+000B–U+000C,
     *       U+000E–U+001F, and U+007F–U+009F while preserving newline (U+000A)
     *       and tab (U+0009).</li>
     *   <li>Unicode-whitespace normalisation – replaces non-breaking space (U+00A0),
     *       narrow no-break space (U+202F), medium mathematical space (U+205F),
     *       ideographic space (U+3000), and other Unicode whitespace variants
     *       with a regular ASCII space (U+0020).</li>
     *   <li>Whitespace collapsing – collapses runs of spaces (excluding newlines) into a
     *       single space, then collapses runs of newlines (with optional surrounding spaces)
     *       into a single newline.</li>
     *   <li>Final trim.</li>
     * </ol>
     *
     * @param content raw content as returned by Tika; may be null or empty
     * @return the normalised string; empty string if input was null or empty
     */
    static String normalizeContent(final String content) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }

        // Step 1: Decode HTML entities (&#123; &#x7B; &amp; etc.)
        String result = decodeHtmlEntities(content);

        // Step 2: Decode URL-encoded sequences (%XX)
        result = decodeUrlEncodedSequences(result);

        // Step 3: NFKC normalization (expands ligatures, full-width chars, etc.)
        result = Normalizer.normalize(result, Normalizer.Form.NFKC);

        // Step 4: Remove control characters except newline (U+000A) and tab (U+0009)
        // Ranges: U+0000-U+0008, U+000B-U+000C, U+000E-U+001F, U+007F-U+009F
        result = result.replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]", "");

        // Step 5: Replace various Unicode whitespace with regular ASCII space
        // U+00A0 NO-BREAK SPACE, U+1680 OGHAM SPACE MARK, U+2000-U+200A (EN QUAD through
        // HAIR SPACE), U+200B ZERO WIDTH SPACE, U+202F NARROW NO-BREAK SPACE,
        // U+205F MEDIUM MATHEMATICAL SPACE, U+3000 IDEOGRAPHIC SPACE, U+FEFF BOM/ZWNBSP
        result = result.replaceAll("[\u00A0\u1680\u2000-\u200B\u202F\u205F\u3000\uFEFF]", " ");

        // Step 6: Collapse whitespace
        // 6a: Collapse runs of horizontal whitespace (spaces and tabs) into a single space,
        //     but do not touch newlines yet.
        result = result.replaceAll("[\\t ]+", " ");

        // 6b: Collapse runs of newlines (with optional surrounding spaces) into a single newline.
        result = result.replaceAll(" *\\n *( *\\n *)*", "\n");

        // Step 7: Trim leading and trailing whitespace
        return result.trim();
    }

    /**
     * Decode HTML entities to their Unicode equivalents.
     *
     * <p>Handles:</p>
     * <ul>
     *   <li>Decimal numeric entities: {@code &#123;} → corresponding Unicode character</li>
     *   <li>Hexadecimal numeric entities: {@code &#x7B;} → corresponding Unicode character</li>
     *   <li>Common named entities: {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;},
     *       {@code &apos;}, {@code &nbsp;}, {@code &copy;}, {@code &reg;}, {@code &euro;},
     *       {@code &mdash;}, {@code &ndash;}, {@code &hellip;}, {@code &lsquo;}, {@code &rsquo;},
     *       {@code &ldquo;}, {@code &rdquo;}</li>
     * </ul>
     *
     * @param text the text potentially containing HTML entities
     * @return text with entities decoded to Unicode
     */
    private static String decodeHtmlEntities(final String text) {
        if (text == null || !text.contains("&")) {
            return text;
        }

        // Use a StringBuilder for efficient replacement
        final StringBuilder result = new StringBuilder(text.length());
        int i = 0;

        while (i < text.length()) {
            final char c = text.charAt(i);

            if (c == '&') {
                // Look for the semicolon
                final int semicolon = text.indexOf(';', i + 1);

                if (semicolon > i && semicolon <= i + 10) { // Reasonable entity length
                    final String entity = text.substring(i + 1, semicolon);
                    final String decoded = decodeEntity(entity);

                    if (decoded != null) {
                        result.append(decoded);
                        i = semicolon + 1;
                        continue;
                    }
                }
            }

            result.append(c);
            i++;
        }

        return result.toString();
    }

    /**
     * Decode URL-encoded sequences (%XX) to their character equivalents.
     * Handles UTF-8 multi-byte sequences.
     *
     * @param text the text potentially containing URL-encoded sequences
     * @return text with sequences decoded
     */
    private static String decodeUrlEncodedSequences(final String text) {
        if (text == null || !text.contains("%")) {
            return text;
        }

        final StringBuilder result = new StringBuilder(text.length());
        final byte[] bytes = new byte[4]; // Buffer for multi-byte UTF-8 sequences
        int byteCount = 0;
        int i = 0;

        while (i < text.length()) {
            final char c = text.charAt(i);

            if (c == '%' && i + 2 < text.length()) {
                // Try to parse hex value
                final String hex = text.substring(i + 1, i + 3);
                try {
                    final int value = Integer.parseInt(hex, 16);
                    bytes[byteCount++] = (byte) value;

                    // Check if this could be part of a UTF-8 multi-byte sequence
                    if ((value & 0x80) == 0) {
                        // Single byte character (ASCII)
                        result.append((char) value);
                        byteCount = 0;
                    } else if ((value & 0xC0) == 0x80 && byteCount > 1) {
                        // Continuation byte - check if sequence is complete
                        final int firstByte = bytes[0] & 0xFF;
                        final int expectedLength;
                        if ((firstByte & 0xE0) == 0xC0) expectedLength = 2;
                        else if ((firstByte & 0xF0) == 0xE0) expectedLength = 3;
                        else if ((firstByte & 0xF8) == 0xF0) expectedLength = 4;
                        else expectedLength = 1;

                        if (byteCount >= expectedLength) {
                            // Decode the UTF-8 sequence
                            result.append(new String(bytes, 0, byteCount, java.nio.charset.StandardCharsets.UTF_8));
                            byteCount = 0;
                        }
                    }
                    i += 3;
                    continue;
                } catch (final NumberFormatException e) {
                    // Not a valid hex sequence, flush any pending bytes and output literally
                    if (byteCount > 0) {
                        result.append(new String(bytes, 0, byteCount, java.nio.charset.StandardCharsets.UTF_8));
                        byteCount = 0;
                    }
                }
            } else {
                // Flush any pending bytes
                if (byteCount > 0) {
                    result.append(new String(bytes, 0, byteCount, java.nio.charset.StandardCharsets.UTF_8));
                    byteCount = 0;
                }
            }

            result.append(c);
            i++;
        }

        // Flush any remaining bytes
        if (byteCount > 0) {
            result.append(new String(bytes, 0, byteCount, java.nio.charset.StandardCharsets.UTF_8));
        }

        return result.toString();
    }

    /**
     * Decode a single HTML entity (without the leading '&' and trailing ';').
     *
     * @param entity the entity name or numeric value (e.g., "amp", "#123", "#x7B")
     * @return the decoded character(s), or null if not recognized
     */
    private static String decodeEntity(final String entity) {
        if (entity == null || entity.isEmpty()) {
            return null;
        }

        // Numeric entity: &#123; or &#x7B;
        if (entity.charAt(0) == '#') {
            try {
                final int codePoint;
                if (entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                    // Hexadecimal: &#xHHHH;
                    codePoint = Integer.parseInt(entity.substring(2), 16);
                } else {
                    // Try decimal first
                    final String numPart = entity.substring(1);
                    int parsed;
                    try {
                        parsed = Integer.parseInt(numPart, 10);
                    } catch (final NumberFormatException e) {
                        // If decimal fails, try hex (some malformed entities omit the 'x')
                        parsed = Integer.parseInt(numPart, 16);
                    }
                    codePoint = parsed;
                }

                if (Character.isValidCodePoint(codePoint)) {
                    return new String(Character.toChars(codePoint));
                }
            } catch (final NumberFormatException e) {
                // Invalid number, return null
            }
            return null;
        }

        // Named entity - handle common ones
        return switch (entity) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";  // Non-breaking space → regular space
            case "copy" -> "©";
            case "reg" -> "®";
            case "trade" -> "™";
            case "euro" -> "€";
            case "pound" -> "£";
            case "yen" -> "¥";
            case "cent" -> "¢";
            case "mdash" -> "—";
            case "ndash" -> "–";
            case "hellip" -> "…";
            case "lsquo" -> "\u2018";  // '
            case "rsquo" -> "\u2019";  // '
            case "ldquo" -> "\u201C";  // "
            case "rdquo" -> "\u201D";  // "
            case "laquo" -> "\u00AB";  // «
            case "raquo" -> "\u00BB";  // »
            case "bull" -> "•";
            case "middot" -> "·";
            case "deg" -> "°";
            case "plusmn" -> "±";
            case "times" -> "×";
            case "divide" -> "÷";
            case "frac12" -> "½";
            case "frac14" -> "¼";
            case "frac34" -> "¾";
            case "para" -> "¶";
            case "sect" -> "§";
            case "dagger" -> "†";
            case "Dagger" -> "‡";
            default -> null;  // Unknown entity, leave as-is
        };
    }
}
