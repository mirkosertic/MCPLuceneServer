package de.mirkosertic.mcp.luceneserver.crawler;

import org.apache.lucene.document.*;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Component
public class DocumentIndexer {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIndexer.class);

    // FacetsConfig for faceting configuration
    private final FacetsConfig facetsConfig;

    public DocumentIndexer() {
        this.facetsConfig = new FacetsConfig();
        // Configure multi-valued facet fields
        facetsConfig.setMultiValued("author", true);
        facetsConfig.setMultiValued("creator", true);
        facetsConfig.setMultiValued("subject", true);
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

        // content (analyzed, stored, with term vectors)
        if (extracted.content() != null && !extracted.content().isEmpty()) {
            final FieldType contentFieldType = new FieldType(TextField.TYPE_STORED);
            contentFieldType.setStoreTermVectors(true);
            doc.add(new Field("content", extracted.content(), contentFieldType));
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
            final long createdDate = Files.getAttribute(filePath, "creationTime", java.nio.file.LinkOption.NOFOLLOW_LINKS).hashCode();
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

        // Metadata fields
        if (extracted.metadata() != null) {
            // title
            final String title = extracted.metadata().get("title");
            if (title != null && !title.isEmpty()) {
                doc.add(new TextField("title", title, Field.Store.YES));
            }

            // author (faceted)
            final String author = extracted.metadata().get("Author");
            if (author != null && !author.isEmpty()) {
                doc.add(new TextField("author", author, Field.Store.YES));
                doc.add(new SortedSetDocValuesFacetField("author", author));
            }

            // creator (faceted)
            final String creator = extracted.metadata().get("creator");
            if (creator != null && !creator.isEmpty()) {
                doc.add(new TextField("creator", creator, Field.Store.YES));
                doc.add(new SortedSetDocValuesFacetField("creator", creator));
            }

            // subject (faceted)
            final String subject = extracted.metadata().get("subject");
            if (subject != null && !subject.isEmpty()) {
                doc.add(new TextField("subject", subject, Field.Store.YES));
                doc.add(new SortedSetDocValuesFacetField("subject", subject));
            }

            // keywords
            final String keywords = extracted.metadata().get("Keywords");
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

    public void indexDocuments(final IndexWriter writer, final List<Document> documents) throws IOException {
        for (final Document document : documents) {
            indexDocument(writer, document);
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
}
