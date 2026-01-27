package de.mirkosertic.mcp.luceneserver.crawler;

import java.nio.file.Path;

public interface FileChangeListener {

    void onFileCreated(Path file);

    void onFileModified(Path file);

    void onFileDeleted(Path file);
}
