package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IndividualPassageFormatter}.
 */
@DisplayName("IndividualPassageFormatter")
class IndividualPassageFormatterTest {

    private final IndividualPassageFormatter formatter = new IndividualPassageFormatter();

    @Test
    @DisplayName("Should return one FormattedPassage per Lucene Passage")
    void shouldReturnOneFormattedPassagePerLucenePassage() {
        final String content = "Alpha Beta Gamma Delta Epsilon";

        final Passage p1 = createPassage(0, 10, 1.5f);
        p1.addMatch(0, 5, new BytesRef("alpha"), 1);

        final Passage p2 = createPassage(11, 25, 0.8f);
        p2.addMatch(11, 15, new BytesRef("gamma"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{p1, p2}, content);

        assertThat(result)
                .as("Should produce exactly 2 formatted passages")
                .hasSize(2);
    }

    @Test
    @DisplayName("Should wrap matched terms in <em> tags")
    void shouldWrapMatchedTermsInEmTags() {
        final String content = "The quick brown fox jumps over the lazy dog";

        final Passage passage = createPassage(0, content.length(), 2.0f);
        // "quick" is at positions 4-9
        passage.addMatch(4, 9, new BytesRef("quick"), 1);
        // "fox" is at positions 16-19
        passage.addMatch(16, 19, new BytesRef("fox"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().text())
                .isEqualTo("The <em>quick</em> brown <em>fox</em> jumps over the lazy dog");
    }

    @Test
    @DisplayName("Should prepend ellipsis for passages not at document start")
    void shouldPrependEllipsisForNonStartPassages() {
        final String content = "First sentence. Second sentence with keyword here.";

        // Passage starts at offset 16 (after "First sentence. ")
        final Passage passage = createPassage(16, content.length(), 1.0f);
        passage.addMatch(37, 44, new BytesRef("keyword"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        assertThat(result.getFirst().text())
                .startsWith("...")
                .contains("<em>keyword</em>");
    }

    @Test
    @DisplayName("Should NOT prepend ellipsis for passages starting at offset 0")
    void shouldNotPrependEllipsisForStartPassages() {
        final String content = "Keyword appears at the start of the document.";

        final Passage passage = createPassage(0, content.length(), 1.0f);
        passage.addMatch(0, 7, new BytesRef("keyword"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        assertThat(result.getFirst().text())
                .doesNotStartWith("...")
                .startsWith("<em>Keyword</em>");
    }

    @Test
    @DisplayName("Should preserve Lucene passage scores")
    void shouldPreserveLucenePassageScores() {
        final String content = "word1 word2 word3";

        final Passage p1 = createPassage(0, 5, 3.5f);
        p1.addMatch(0, 5, new BytesRef("word1"), 1);

        final Passage p2 = createPassage(6, 11, 1.2f);
        p2.addMatch(6, 11, new BytesRef("word2"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{p1, p2}, content);

        assertThat(result.get(0).score()).isEqualTo(3.5f);
        assertThat(result.get(1).score()).isEqualTo(1.2f);
    }

    @Test
    @DisplayName("Should preserve passage offsets")
    void shouldPreservePassageOffsets() {
        final String content = "Start of document. Middle part here. End of document.";

        final Passage passage = createPassage(19, 36, 1.0f);
        passage.addMatch(19, 25, new BytesRef("middle"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        assertThat(result.getFirst().startOffset()).isEqualTo(19);
        assertThat(result.getFirst().endOffset()).isEqualTo(36);
    }

    @Test
    @DisplayName("Should handle overlapping match offsets")
    void shouldHandleOverlappingMatchOffsets() {
        final String content = "testing tested tester";

        final Passage passage = createPassage(0, content.length(), 1.0f);
        // Two overlapping matches on the same region
        passage.addMatch(0, 7, new BytesRef("testing"), 1);
        passage.addMatch(0, 4, new BytesRef("test"), 2);  // overlaps with first match

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        // Should not crash on overlapping matches; the second overlap is skipped
        assertThat(result.getFirst().text())
                .contains("<em>testing</em>");
    }

    @Test
    @DisplayName("Should handle passage with no matches (fallback passage)")
    void shouldHandlePassageWithNoMatches() {
        final String content = "This is a passage with no matches at all.";

        final Passage passage = createPassage(0, content.length(), 0.0f);
        // No addMatch calls - simulates a fallback "no highlight" passage

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().text())
                .isEqualTo(content)
                .doesNotContain("<em>");
        assertThat(result.getFirst().numMatches()).isZero();
    }

    @Test
    @DisplayName("Should handle empty passages array")
    void shouldHandleEmptyPassagesArray() {
        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[0], "some content");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should track numMatches per passage")
    void shouldTrackNumMatchesPerPassage() {
        final String content = "alpha beta gamma delta epsilon";

        final Passage passage = createPassage(0, content.length(), 2.0f);
        passage.addMatch(0, 5, new BytesRef("alpha"), 1);
        passage.addMatch(6, 10, new BytesRef("beta"), 1);
        passage.addMatch(17, 22, new BytesRef("delta"), 1);

        @SuppressWarnings("unchecked")
        final List<IndividualPassageFormatter.FormattedPassage> result =
                (List<IndividualPassageFormatter.FormattedPassage>) formatter.format(
                        new Passage[]{passage}, content);

        assertThat(result.getFirst().numMatches()).isEqualTo(3);
        assertThat(result.getFirst().text())
                .contains("<em>alpha</em>")
                .contains("<em>beta</em>")
                .contains("<em>delta</em>")
                .doesNotContain("<em>gamma</em>");
    }

    /**
     * Helper to create a Lucene Passage with given offsets and score.
     */
    private static Passage createPassage(final int startOffset, final int endOffset, final float score) {
        final Passage passage = new Passage();
        passage.setStartOffset(startOffset);
        passage.setEndOffset(endOffset);
        passage.setScore(score);
        return passage;
    }
}
