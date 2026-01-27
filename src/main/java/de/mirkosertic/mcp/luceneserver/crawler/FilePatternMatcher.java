package de.mirkosertic.mcp.luceneserver.crawler;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

public class FilePatternMatcher {

    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;

    public FilePatternMatcher(final List<String> includePatterns, final List<String> excludePatterns) {
        this.includeMatchers = includePatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        this.excludeMatchers = excludePatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    public boolean shouldInclude(final Path file) {
        // Check if file matches any exclude pattern
        for (final PathMatcher excludeMatcher : excludeMatchers) {
            if (excludeMatcher.matches(file)) {
                return false;
            }
        }

        // If no include patterns specified, include all (except excluded)
        if (includeMatchers.isEmpty()) {
            return true;
        }

        // Check if file matches any include pattern
        for (final PathMatcher includeMatcher : includeMatchers) {
            if (includeMatcher.matches(file.getFileName())) {
                return true;
            }
        }

        return false;
    }
}
