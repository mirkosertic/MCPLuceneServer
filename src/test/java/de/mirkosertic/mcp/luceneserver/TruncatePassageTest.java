package de.mirkosertic.mcp.luceneserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LuceneIndexService#truncatePassage(String, int)}.
 *
 * <p>The truncation algorithm creates a window centred on the highlighted
 * ({@code <em>}) terms, trimming both leading and trailing unhighlighted
 * text.  These tests verify the windowing, word-boundary snapping, ellipsis
 * indicators, and edge cases.</p>
 */
@DisplayName("Passage Truncation")
class TruncatePassageTest {

    @Test
    @DisplayName("Should not truncate text within the limit")
    void shouldNotTruncateShortText() {
        final String text = "Short passage with <em>highlighted</em> term.";
        assertThat(LuceneIndexService.truncatePassage(text, 500))
                .isEqualTo(text);
    }

    @Test
    @DisplayName("Should return full text when exactly at limit")
    void shouldReturnFullTextWhenExactlyAtLimit() {
        final String text = "Exactly right.";
        assertThat(LuceneIndexService.truncatePassage(text, text.length()))
                .isEqualTo(text);
    }

    @Test
    @DisplayName("Should disable truncation when maxLength is 0 or negative")
    void shouldDisableTruncationWhenMaxLengthNonPositive() {
        final String text = "This should not be truncated at all.";

        assertThat(LuceneIndexService.truncatePassage(text, 0)).isEqualTo(text);
        assertThat(LuceneIndexService.truncatePassage(text, -1)).isEqualTo(text);
    }

    @Test
    @DisplayName("Should handle null input")
    void shouldHandleNullInput() {
        assertThat(LuceneIndexService.truncatePassage(null, 100)).isNull();
    }

    // ==================== Highlight-centred windowing ====================

    @Test
    @DisplayName("Should centre window around highlighted terms")
    void shouldCentreWindowAroundHighlights() {
        // Highlight is in the middle, surrounded by long unhighlighted text
        final String text = "Long leading text that is not relevant at all. " +
                "<em>keyword</em> is the important part. " +
                "Long trailing text that is also not relevant at all either.";
        final String result = LuceneIndexService.truncatePassage(text, 60);

        // The highlight must be preserved
        assertThat(result).contains("<em>keyword</em>");
        // Both sides should be trimmed
        assertThat(result).startsWith("...");
        assertThat(result).endsWith("...");
        // Should be within budget (plus some slack for word boundary + ellipsis)
        assertThat(result.length()).isLessThanOrEqualTo(75);
    }

    @Test
    @DisplayName("Should trim only trailing text when highlight is near the start")
    void shouldTrimOnlyTrailingWhenHighlightNearStart() {
        final String text = "<em>keyword</em> appears at the start. " +
                "Then a very long section of text follows that has nothing to do with the " +
                "search terms and just continues on and on.";
        final String result = LuceneIndexService.truncatePassage(text, 80);

        assertThat(result)
                .contains("<em>keyword</em>")
                .doesNotStartWith("...")  // highlight is at the start, no leading trim
                .endsWith("...");
    }

    @Test
    @DisplayName("Should trim only leading text when highlight is near the end")
    void shouldTrimOnlyLeadingWhenHighlightNearEnd() {
        final String text = "A very long introduction that is completely irrelevant to the search query " +
                "and goes on for quite a while before we finally reach the <em>keyword</em>.";
        final String result = LuceneIndexService.truncatePassage(text, 60);

        assertThat(result)
                .contains("<em>keyword</em>")
                .startsWith("...")
                .doesNotEndWith("..."); // highlight is at the end, no trailing trim (text ends with ".")
    }

    @Test
    @DisplayName("Should preserve all <em> tags within the highlight span")
    void shouldPreserveAllEmTagsInSpan() {
        final String text = "Some leading context. <em>first</em> word <em>second</em> and more. " +
                "Then a very long trailing section that goes on and on without any highlights.";
        final String result = LuceneIndexService.truncatePassage(text, 70);

        assertThat(result)
                .contains("<em>first</em>")
                .contains("<em>second</em>");
    }

    @Test
    @DisplayName("Should truncate long trailing text after highlights")
    void shouldTruncateLongTrailingText() {
        final String text = "<em>keyword</em> appears at the start. " +
                "Then a very long section of text follows that has nothing to do with the " +
                "search terms and just continues because the sentence boundary was very far away " +
                "from the highlighted term in the original document content.";
        final String result = LuceneIndexService.truncatePassage(text, 80);

        assertThat(result)
                .contains("<em>keyword</em>")
                .endsWith("...")
                .hasSizeLessThan(text.length());
    }

    // ==================== Fallback: no highlights ====================

    @Test
    @DisplayName("Should fall back to tail truncation when no highlights present")
    void shouldFallBackToTailTruncationWithoutHighlights() {
        final String text = "Word1 Word2 Word3 Word4 Word5 Word6 Word7 Word8";
        final String result = LuceneIndexService.truncatePassage(text, 20);

        assertThat(result)
                .endsWith("...")
                .doesNotStartWith("...");
    }

    @Test
    @DisplayName("Should handle text with no spaces gracefully (no highlights)")
    void shouldHandleNoSpaces() {
        final String text = "averylongwordwithoutanyspacesinit";
        final String result = LuceneIndexService.truncatePassage(text, 10);

        assertThat(result).endsWith("...");
        assertThat(result).hasSize(13); // 10 + "..."
    }

    // ==================== Edge cases ====================

    @Test
    @DisplayName("Should handle highlight span larger than maxLength")
    void shouldHandleHighlightSpanLargerThanMaxLength() {
        // The highlight itself is very long (e.g. an entire sentence was matched)
        final String text = "Before <em>this is a very long highlighted span that exceeds the maximum length</em> after.";
        final String result = LuceneIndexService.truncatePassage(text, 30);

        // Should still contain the start of the highlight
        assertThat(result).contains("<em>");
    }

    @Test
    @DisplayName("Should redistribute budget when one side hits text boundary")
    void shouldRedistributeBudgetAtBoundary() {
        // Highlight is at offset 0 — all extra budget should go to the trailing side
        final String text = "<em>word</em> and then some trailing context here that is moderately long.";
        final String result = LuceneIndexService.truncatePassage(text, 50);

        assertThat(result)
                .contains("<em>word</em>")
                .doesNotStartWith("...");  // no leading trim since highlight is at start
        // More trailing context should be kept since start budget is redistributed
        assertThat(result.length()).isGreaterThan(20);
    }
}
