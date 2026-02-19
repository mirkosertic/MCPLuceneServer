package de.mirkosertic.mcp.luceneserver.crawler;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Creates and indexes Lucene documents with consistent field schema and faceting support.
 */
public class DocumentIndexer {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIndexer.class);

    /**
     * Schema version for the index.
     * MUST be incremented whenever the index schema changes (fields added/removed/modified, analyzers changed, etc.).
     * Version 2: Added text cleaning to remove broken/invalid characters during indexing.
     * Version 3: Added content_stemmed_de and content_stemmed_en fields for Snowball stemming.
     * Version 4: Replaced Snowball stemmed fields with OpenNLP lemmatized fields (content_lemma_de, content_lemma_en).
     * Version 5: Removed creator and subject from faceted fields (kept as stored/searchable TextField only).
     * Version 6: Always index BOTH content_lemma_de and content_lemma_en fields for mixed-language support.
     * Version 7: Added content_translit_de field for German umlaut digraph transliteration (ae→ä, oe→ö, ue→ü).
     */
    public static final int SCHEMA_VERSION = 7;

    // FacetsConfig for faceting configuration
    private final FacetsConfig facetsConfig;

    public DocumentIndexer() {
        this.facetsConfig = new FacetsConfig();
        // Configure multi-valued facet fields
        facetsConfig.setMultiValued("author", true);
    }

    public FacetsConfig getFacetsConfig() {
        return facetsConfig;
    }

    public Document createDocument(final Path filePath, final ExtractedDocument extracted) {
        final Document doc = new Document();

        // file_path - unique ID (not analyzed, stored)
        doc.add(new StringField("file_path", filePath.toString(), Field.Store.YES));

        // file_name (analyzed, stored)
        doc.add(new TextField("file_name", filePath.getFileName().toString(), Field.Store.YES));

        // content (analyzed, stored, with term vectors + positions + offsets)
        // Positions and offsets are required by UnifiedHighlighter to locate matched
        // terms within the stored value without re-analysing the text at query time.
        if (extracted.content() != null && !extracted.content().isEmpty()) {
            // Content is already cleaned by FileContentExtractor.normalizeContent()
            final String content = extracted.content();

            final FieldType contentFieldType = new FieldType(TextField.TYPE_STORED);
            contentFieldType.setStoreTermVectors(true);
            contentFieldType.setStoreTermVectorPositions(true);
            contentFieldType.setStoreTermVectorOffsets(true);
            doc.add(new Field("content", content, contentFieldType));

            // content_reversed (analyzed with ReverseUnicodeNormalizingAnalyzer, not stored)
            // Stores reversed tokens so that leading wildcard queries (*vertrag) can be
            // rewritten as efficient trailing wildcard queries on this field (gartrev*).
            // The PerFieldAnalyzerWrapper in LuceneIndexService routes this field to the
            // ReverseUnicodeNormalizingAnalyzer automatically.
            doc.add(new TextField("content_reversed", content, Field.Store.NO));

            // Lemmatized shadow fields (analyzed with OpenNLPLemmatizingAnalyzer, not stored)
            // BOTH German and English lemmatization fields are ALWAYS indexed, regardless of
            // detected language. This enables mixed-language matching: German documents with
            // English technical terms (e.g., "Recommendation Engine") can match English plural
            // queries (e.g., "Recommendation Engines") via the content_lemma_en field, and vice versa.
            // The PerFieldAnalyzerWrapper in LuceneIndexService routes each field to the
            // appropriate language-specific OpenNLPLemmatizingAnalyzer automatically.
            doc.add(new TextField("content_lemma_de", content, Field.Store.NO));
            doc.add(new TextField("content_lemma_en", content, Field.Store.NO));

            // German transliteration shadow field (analyzed with GermanTransliteratingAnalyzer, not stored)
            // Maps umlaut digraphs (ae→ä, oe→ö, ue→ü) so "Mueller" matches "Müller" via the
            // common normalized form "muller". Always indexed regardless of detected language.
            doc.add(new TextField("content_translit_de", content, Field.Store.NO));
        }

        // file_extension (not analyzed, stored, faceted)
        final String fileName = filePath.getFileName().toString();
        final int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            final String extension = fileName.substring(lastDot + 1).toLowerCase();
            doc.add(new StringField("file_extension", extension, Field.Store.YES));
            doc.add(new SortedSetDocValuesFacetField("file_extension", extension));
        }

        // file_type - MIME type (not analyzed, stored, faceted)
        if (extracted.fileType() != null) {
            doc.add(new StringField("file_type", extracted.fileType(), Field.Store.YES));
            doc.add(new SortedSetDocValuesFacetField("file_type", extracted.fileType()));
        }

        // file_size (numeric, stored)
        doc.add(new LongPoint("file_size", extracted.fileSize()));
        doc.add(new StoredField("file_size", extracted.fileSize()));

        // Timestamps
        try {
            final FileTime creationTime = (FileTime) Files.getAttribute(filePath, "creationTime",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            final long createdDate = creationTime.toMillis();
            final long modifiedDate = Files.getLastModifiedTime(filePath).toMillis();
            final long indexedDate = System.currentTimeMillis();

            doc.add(new LongPoint("created_date", createdDate));
            doc.add(new StoredField("created_date", createdDate));

            doc.add(new LongPoint("modified_date", modifiedDate));
            doc.add(new StoredField("modified_date", modifiedDate));

            doc.add(new LongPoint("indexed_date", indexedDate));
            doc.add(new StoredField("indexed_date", indexedDate));
        } catch (final IOException e) {
            logger.warn("Failed to read file timestamps for: {}", filePath, e);
        }

        // Metadata fields - use Tika standard keys with fallbacks to legacy keys
        if (extracted.metadata() != null) {
            final Map<String, String> meta = extracted.metadata();

            // title: dc:title > title > Title
            final String title = getMetadataWithFallback(meta, "dc:title", "title", "Title");
            if (title != null && !title.isEmpty()) {
                doc.add(new TextField("title", title, Field.Store.YES));
            }

            // author (faceted): dc:creator > meta:author > Author > author
            final String author = getMetadataWithFallback(meta, "dc:creator", "meta:author", "Author", "author");
            if (author != null && !author.isEmpty()) {
                doc.add(new TextField("author", author, Field.Store.YES));
                doc.add(new SortedSetDocValuesFacetField("author", author));
            }

            // creator: xmp:CreatorTool > creator > Creator > Application-Name
            final String creator = getMetadataWithFallback(meta, "xmp:CreatorTool", "creator", "Creator", "Application-Name");
            if (creator != null && !creator.isEmpty()) {
                doc.add(new TextField("creator", creator, Field.Store.YES));
            }

            // subject: dc:subject > subject > Subject
            final String subject = getMetadataWithFallback(meta, "dc:subject", "subject", "Subject");
            if (subject != null && !subject.isEmpty()) {
                doc.add(new TextField("subject", subject, Field.Store.YES));
            }

            // keywords: meta:keyword > Keywords > keywords
            final String keywords = getMetadataWithFallback(meta, "meta:keyword", "Keywords", "keywords");
            if (keywords != null && !keywords.isEmpty()) {
                doc.add(new TextField("keywords", keywords, Field.Store.YES));
            }
        }

        // language (not analyzed, stored, faceted)
        if (extracted.detectedLanguage() != null) {
            doc.add(new StringField("language", extracted.detectedLanguage(), Field.Store.YES));
            doc.add(new SortedSetDocValuesFacetField("language", extracted.detectedLanguage()));
        }

        // content_hash for change detection (SHA-256)
        if (extracted.content() != null) {
            try {
                final String contentHash = calculateSHA256(extracted.content());
                doc.add(new StringField("content_hash", contentHash, Field.Store.YES));
            } catch (final NoSuchAlgorithmException e) {
                logger.warn("Failed to calculate content hash for: {}", filePath, e);
            }
        }

        return doc;
    }

    public void indexDocument(final IndexWriter writer, final Document document) throws IOException {
        // Build the document with facets config
        final Document facetedDoc = facetsConfig.build(document);

        final String filePath = document.get("file_path");
        if (filePath != null) {
            // Update or insert document (using file_path as unique identifier)
            writer.updateDocument(new Term("file_path", filePath), facetedDoc);
        } else {
            writer.addDocument(facetedDoc);
        }
    }

    public void deleteDocument(final IndexWriter writer, final String filePath) throws IOException {
        writer.deleteDocuments(new Term("file_path", filePath));
    }

    private String calculateSHA256(final String content) throws NoSuchAlgorithmException {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hexString = new StringBuilder();
        for (final byte b : hash) {
            final String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Get metadata value trying multiple keys in order.
     * Returns the first non-null, non-empty value found, or null if none found.
     *
     * @param metadata the metadata map
     * @param keys     the keys to try in order (first match wins)
     * @return the first non-empty value, or null
     */
    private String getMetadataWithFallback(final Map<String, String> metadata, final String... keys) {
        for (final String key : keys) {
            final String value = metadata.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
