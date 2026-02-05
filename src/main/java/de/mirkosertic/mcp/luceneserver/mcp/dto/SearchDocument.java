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
    List<Passage> passages
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

        public Builder score(double score) { this.score = score; return this; }
        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder author(String author) { this.author = author; return this; }
        public Builder creator(String creator) { this.creator = creator; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder fileExtension(String fileExtension) { this.fileExtension = fileExtension; return this; }
        public Builder fileType(String fileType) { this.fileType = fileType; return this; }
        public Builder fileSize(Long fileSize) { this.fileSize = fileSize; return this; }
        public Builder fileSize(String fileSize) {
            this.fileSize = fileSize != null ? Long.parseLong(fileSize) : null;
            return this;
        }
        public Builder createdDate(Long createdDate) { this.createdDate = createdDate; return this; }
        public Builder createdDate(String createdDate) {
            this.createdDate = createdDate != null ? Long.parseLong(createdDate) : null;
            return this;
        }
        public Builder modifiedDate(Long modifiedDate) { this.modifiedDate = modifiedDate; return this; }
        public Builder modifiedDate(String modifiedDate) {
            this.modifiedDate = modifiedDate != null ? Long.parseLong(modifiedDate) : null;
            return this;
        }
        public Builder indexedDate(Long indexedDate) { this.indexedDate = indexedDate; return this; }
        public Builder indexedDate(String indexedDate) {
            this.indexedDate = indexedDate != null ? Long.parseLong(indexedDate) : null;
            return this;
        }
        public Builder passages(List<Passage> passages) { this.passages = passages; return this; }

        public SearchDocument build() {
            return new SearchDocument(score, filePath, fileName, title, author, creator,
                subject, language, fileExtension, fileType, fileSize, createdDate,
                modifiedDate, indexedDate, passages);
        }
    }
}
