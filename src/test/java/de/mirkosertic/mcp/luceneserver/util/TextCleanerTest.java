package de.mirkosertic.mcp.luceneserver.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerTest {

    @Test
    void shouldRemoveReplacementCharacter() {
        final String input = "Hello � world";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Hello world");
    }

    @Test
    void shouldRemoveMultipleReplacementCharacters() {
        final String input = "Text with ��� many broken chars";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Text with many broken chars");
    }

    @Test
    void shouldRemoveNullCharacter() {
        final String input = "Text\u0000with\u0000nulls";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Textwithnulls");
    }

    @Test
    void shouldRemoveControlCharacters() {
        final String input = "Text\u0001\u0002\u0003with\u001Fcontrol";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Textwithcontrol");
    }

    @Test
    void shouldPreserveAllowedWhitespace() {
        final String input = "Line1\nLine2\tTabbed\rReturn";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).contains("\n").contains("\t").contains("\r");
    }

    @Test
    void shouldRemoveZeroWidthCharacters() {
        final String input = "Text\u200Bwith\u200Czero\u200Dwidth";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Textwithzerowidth");
    }

    @Test
    void shouldRemoveByteOrderMark() {
        final String input = "\uFEFFText with BOM";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Text with BOM");
    }

    @Test
    void shouldCollapseMultipleSpaces() {
        final String input = "Text  with    many     spaces";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Text with many spaces");
    }

    @Test
    void shouldTrimWhitespace() {
        final String input = "  Text with spaces  ";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Text with spaces");
    }

    @Test
    void shouldHandleNullInput() {
        final String cleaned = TextCleaner.clean(null);
        assertThat(cleaned).isNull();
    }

    @Test
    void shouldHandleEmptyInput() {
        final String cleaned = TextCleaner.clean("");
        assertThat(cleaned).isEmpty();
    }

    @Test
    void shouldPreserveNormalText() {
        final String input = "Normal text with punctuation! And numbers 123.";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo(input);
    }

    @Test
    void shouldPreserveUnicode() {
        final String input = "Café München Zürich";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo(input);
    }

    @Test
    void shouldPreserveEmojis() {
        final String input = "Hello 👋 World 🌍";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo(input);
    }

    @Test
    void cleanPreservingWhitespace_shouldNotCollapseSpaces() {
        final String input = "Text  with    many     spaces";
        final String cleaned = TextCleaner.cleanPreservingWhitespace(input);
        assertThat(cleaned).isEqualTo(input);
    }

    @Test
    void cleanPreservingWhitespace_shouldStillRemoveInvalidChars() {
        final String input = "Text � with  broken  chars";
        final String cleaned = TextCleaner.cleanPreservingWhitespace(input);
        assertThat(cleaned).isEqualTo("Text  with  broken  chars");
    }

    @Test
    void shouldHandleMixedInvalidCharacters() {
        final String input = "Mixed\u0000�\u200B\uFEFFtest";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEqualTo("Mixedtest");
    }

    @Test
    void shouldHandleOnlyInvalidCharacters() {
        final String input = "\u0000�\u200B\uFEFF";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEmpty();
    }

    @Test
    void shouldHandleOnlyWhitespace() {
        final String input = "   \t\n\r   ";
        final String cleaned = TextCleaner.clean(input);
        assertThat(cleaned).isEmpty();
    }
}
