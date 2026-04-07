package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

import java.util.List;

/**
 * A single document in search results with all metadata and highlighted passages.
 *
 * <p>The full document content is intentionally excluded to keep response sizes
 * manageable. Use the {@link #passages()} for relevant excerpts with highlighting,
 * or fetch the full document via getDocument() if needed.</p>
 */
public record SearchDocument(
    @Description("Lucene relevance score for this document")
    double score,

    @Description("Absolute file path of the indexed document")
    String filePath,

    @Description("File name without path")
    String fileName,

    @Description("Document title from metadata (may be null)")
    String title,

    @Description("Document author from metadata (may be null)")
    String author,

    @Description("Creator/application that created the document (may be null)")
    String creator,

    @Description("Document subject from metadata (may be null)")
    String subject,

    @Description("Detected language code, e.g. 'en', 'de' (may be null)")
    String language,

    @Description("File extension without dot, e.g. 'pdf', 'docx'")
    String fileExtension,

    @Description("MIME type of the file, e.g. 'application/pdf'")
    String fileType,

    @Description("File size in bytes")
    Long fileSize,

    @Description("File creation timestamp in milliseconds since epoch (may be null)")
    Long createdDate,

    @Description("File last modified timestamp in milliseconds since epoch (may be null)")
    Long modifiedDate,

    @Description("Timestamp when this document was indexed, in milliseconds since epoch")
    Long indexedDate,

    @Description("Highlighted passages from the document content with quality metadata")
    List<Passage> passages,

    @Description("Vector (semantic) match details when the document was retrieved or boosted via KNN search; null for pure text-match results")
    VectorMatchInfo vectorMatchInfo
) {
    /**
     * Builder for SearchDocument to simplify construction from Lucene Document.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double score;
        private String filePath;
        private String fileName;
        private String title;
        private String author;
        private String creator;
        private String subject;
        private String language;
        private String fileExtension;
        private String fileType;
        private Long fileSize;
        private Long createdDate;
        private Long modifiedDate;
        private Long indexedDate;
        private List<Passage> passages = List.of();
        private VectorMatchInfo vectorMatchInfo;

        public Builder score(final double score) { this.score = score; return this; }
        public Builder filePath(final String filePath) { this.filePath = filePath; return this; }
        public Builder fileName(final String fileName) { this.fileName = fileName; return this; }
        public Builder title(final String title) { this.title = title; return this; }
        public Builder author(final String author) { this.author = author; return this; }
        public Builder creator(final String creator) { this.creator = creator; return this; }
        public Builder subject(final String subject) { this.subject = subject; return this; }
        public Builder language(final String language) { this.language = language; return this; }
        public Builder fileExtension(final String fileExtension) { this.fileExtension = fileExtension; return this; }
        public Builder fileType(final String fileType) { this.fileType = fileType; return this; }
        public Builder fileSize(final Long fileSize) { this.fileSize = fileSize; return this; }
        public Builder fileSize(final String fileSize) {
            this.fileSize = fileSize != null ? Long.parseLong(fileSize) : null;
            return this;
        }
        public Builder createdDate(final Long createdDate) { this.createdDate = createdDate; return this; }
        public Builder createdDate(final String createdDate) {
            this.createdDate = createdDate != null ? Long.parseLong(createdDate) : null;
            return this;
        }
        public Builder modifiedDate(final Long modifiedDate) { this.modifiedDate = modifiedDate; return this; }
        public Builder modifiedDate(final String modifiedDate) {
            this.modifiedDate = modifiedDate != null ? Long.parseLong(modifiedDate) : null;
            return this;
        }
        public Builder indexedDate(final Long indexedDate) { this.indexedDate = indexedDate; return this; }
        public Builder indexedDate(final String indexedDate) {
            this.indexedDate = indexedDate != null ? Long.parseLong(indexedDate) : null;
            return this;
        }
        public Builder passages(final List<Passage> passages) { this.passages = passages; return this; }
        public Builder vectorMatchInfo(final VectorMatchInfo vectorMatchInfo) { this.vectorMatchInfo = vectorMatchInfo; return this; }

        public SearchDocument build() {
            return new SearchDocument(score, filePath, fileName, title, author, creator,
                subject, language, fileExtension, fileType, fileSize, createdDate,
                modifiedDate, indexedDate, passages, vectorMatchInfo);
        }
    }
}
