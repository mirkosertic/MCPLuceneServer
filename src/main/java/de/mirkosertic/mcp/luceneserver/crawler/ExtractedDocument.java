package de.mirkosertic.mcp.luceneserver.crawler;

import java.util.Map;

public record ExtractedDocument(
        String content,
        Map<String, String> metadata,
        String detectedLanguage,
        String fileType,
        long fileSize
) {
}
