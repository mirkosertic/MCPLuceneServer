package de.mirkosertic.mcp.luceneserver.crawler;

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
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class FileContentExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FileContentExtractor.class);

    private final CrawlerProperties properties;
    private final Tika tika;
    private final Parser parser;
    private final LanguageDetector languageDetector;

    public FileContentExtractor(final CrawlerProperties properties) {
        this.properties = properties;
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
            if (properties.getMaxContentLength() <= 0) {
                handler = new BodyContentHandler(-1);
                logger.debug("Using unlimited content extraction for file: {}", file);
            } else {
                handler = new BodyContentHandler((int) properties.getMaxContentLength());
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
            if (properties.isExtractMetadata()) {
                for (final String name : metadata.names()) {
                    final String value = metadata.get(name);
                    if (value != null && !value.isEmpty()) {
                        metadataMap.put(name, value);
                    }
                }
            }

            // Detect language if enabled
            String detectedLanguage = null;
            if (properties.isDetectLanguage() && content != null && !content.isEmpty()) {
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

            return new ExtractedDocument(
                    content,
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
}
