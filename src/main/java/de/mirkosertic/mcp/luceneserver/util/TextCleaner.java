package de.mirkosertic.mcp.luceneserver.util;

import java.util.regex.Pattern;

/**
 * Utility class for cleaning text content by removing invalid, broken, or problematic characters.
 *
 * <p>This helps ensure clean search results and passages by filtering out:</p>
 * <ul>
 *   <li>Unicode replacement characters (�) from failed decoding</li>
 *   <li>Control characters that aren't whitespace</li>
 *   <li>Zero-width characters</li>
 *   <li>Null characters</li>
 *   <li>Other problematic Unicode sequences</li>
 * </ul>
 */
public final class TextCleaner {

    /**
     * Pattern matching characters to remove:
     * <ul>
     *   <li>U+FFFD: Replacement character (�)</li>
     *   <li>U+0000: Null character</li>
     *   <li>U+0001-U+001F: Control characters (except \t \n \r which are U+0009, U+000A, U+000D)</li>
     *   <li>U+200B: Zero-width space</li>
     *   <li>U+200C: Zero-width non-joiner</li>
     *   <li>U+200D: Zero-width joiner</li>
     *   <li>U+FEFF: Byte order mark / zero-width no-break space</li>
     * </ul>
     */
    private static final Pattern INVALID_CHARS = Pattern.compile(
        "[" +
        "\u0000" +                    // NULL
        "\u0001-\u0008" +             // Control chars before TAB
        "\u000B-\u000C" +             // Control chars between TAB and CR (excluding LF)
        "\u000E-\u001F" +             // Control chars after CR
        "\u200B" +                    // Zero-width space
        "\u200C" +                    // Zero-width non-joiner
        "\u200D" +                    // Zero-width joiner
        "\uFEFF" +                    // Byte order mark
        "\uFFFD" +                    // Replacement character (�)
        "]"
    );

    /**
     * Pattern to collapse multiple consecutive whitespace characters into a single space.
     */
    private static final Pattern MULTIPLE_WHITESPACE = Pattern.compile("\\s{2,}");

    private TextCleaner() {
        // Utility class, no instances
    }

    /**
     * Clean text by removing invalid characters and normalizing whitespace.
     *
     * @param text the text to clean (may be null)
     * @return cleaned text, or null if input was null
     */
    public static String clean(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Remove invalid characters
        String cleaned = INVALID_CHARS.matcher(text).replaceAll("");

        // Normalize multiple whitespace to single space
        cleaned = MULTIPLE_WHITESPACE.matcher(cleaned).replaceAll(" ");

        // Trim leading/trailing whitespace
        cleaned = cleaned.trim();

        return cleaned;
    }

    /**
     * Clean text but preserve original whitespace structure (don't collapse or trim).
     * Useful when whitespace positioning matters.
     *
     * @param text the text to clean (may be null)
     * @return cleaned text with original whitespace preserved, or null if input was null
     */
    public static String cleanPreservingWhitespace(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Only remove invalid characters, keep whitespace as-is
        return INVALID_CHARS.matcher(text).replaceAll("");
    }
}
