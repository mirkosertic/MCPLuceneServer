package de.mirkosertic.mcp.luceneserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for Reciprocal Rank Fusion (RRF) scoring logic used in hybrid search.
 *
 * <p>RRF formula: {@code score(rank) = 1 / (60 + rank + 1)}
 * (0-based rank, constant k = 60, as used in {@link LuceneIndexService#mergeWithVectorResults})</p>
 */
@DisplayName("RRF scoring logic tests")
class RRFScoringTest {

    private static final float DELTA = 1e-7f;

    /** RRF score for a single list entry at 0-based {@code rank}. */
    private static float rrfScore(final int rank) {
        return 1f / (60f + rank + 1f);
    }

    // ========== Formula correctness ===========

    @Test
    @DisplayName("RRF score for rank 0 equals 1/61")
    void rrfScoreForRank0() {
        final float expected = 1f / 61f;
        assertThat(rrfScore(0)).isEqualTo(expected, offset(DELTA));
    }

    @Test
    @DisplayName("RRF score decreases as rank increases")
    void rrfScoreDecreases() {
        assertThat(rrfScore(0)).isGreaterThan(rrfScore(1));
        assertThat(rrfScore(1)).isGreaterThan(rrfScore(10));
        assertThat(rrfScore(10)).isGreaterThan(rrfScore(100));
    }

    @Test
    @DisplayName("RRF score is always positive")
    void rrfScoreAlwaysPositive() {
        for (int rank = 0; rank < 200; rank++) {
            assertThat(rrfScore(rank)).isGreaterThan(0f);
        }
    }

    // ========== Merge logic ===========

    /**
     * Minimal merge helper that replicates the RRF logic from
     * {@link LuceneIndexService#mergeWithVectorResults}.
     *
     * @param textDocIds   ordered list of text-result doc IDs (best first)
     * @param vectorDocIds ordered list of vector-result doc IDs (best first)
     * @param pageSize     maximum results to return
     * @return list of [docId, rrfScore] entries sorted by score descending
     */
    private static List<Map.Entry<Integer, Float>> merge(
            final List<Integer> textDocIds,
            final List<Integer> vectorDocIds,
            final int pageSize) {

        final Map<Integer, Float> scores = new HashMap<>();

        for (int rank = 0; rank < textDocIds.size(); rank++) {
            final int docId = textDocIds.get(rank);
            scores.merge(docId, rrfScore(rank), Float::sum);
        }

        for (int rank = 0; rank < vectorDocIds.size(); rank++) {
            final int docId = vectorDocIds.get(rank);
            scores.merge(docId, rrfScore(rank), Float::sum);
        }

        final List<Map.Entry<Integer, Float>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<Integer, Float>comparingByValue(Comparator.reverseOrder()));
        return sorted.subList(0, Math.min(pageSize, sorted.size()));
    }

    @Test
    @DisplayName("Document appearing in both lists has combined (higher) RRF score")
    void docInBothListsHasCombinedScore() {
        // docId=1 is rank 0 in both text and vector lists
        final float expectedScore = rrfScore(0) + rrfScore(0); // both at rank 0

        final List<Map.Entry<Integer, Float>> result = merge(
                List.of(1),
                List.of(1),
                10);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getKey()).isEqualTo(1);
        assertThat(result.getFirst().getValue()).isEqualTo(expectedScore, offset(DELTA));
    }

    @Test
    @DisplayName("Document only in text list gets text-only contribution")
    void docOnlyInTextListGetsTextScore() {
        // docId=1 in text only, docId=2 in vector only
        final List<Map.Entry<Integer, Float>> result = merge(
                List.of(1),
                List.of(2),
                10);

        assertThat(result).hasSize(2);

        final float scoreDoc1 = result.stream()
                .filter(e -> e.getKey() == 1).findFirst()
                .map(Map.Entry::getValue).orElseThrow();
        final float scoreDoc2 = result.stream()
                .filter(e -> e.getKey() == 2).findFirst()
                .map(Map.Entry::getValue).orElseThrow();

        assertThat(scoreDoc1).isEqualTo(rrfScore(0), offset(DELTA));
        assertThat(scoreDoc2).isEqualTo(rrfScore(0), offset(DELTA));
    }

    @Test
    @DisplayName("Document in both lists ranks higher than documents in only one list")
    void docInBothListsRanksHighest() {
        // docId=42 is in both lists at rank 0 each → score = 2/61
        // docId=7 is only in text list at rank 1   → score = 1/62
        // docId=9 is only in vector list at rank 1  → score = 1/62
        final List<Map.Entry<Integer, Float>> result = merge(
                List.of(42, 7),
                List.of(42, 9),
                10);

        assertThat(result).isNotEmpty();
        final int topDocId = result.getFirst().getKey();
        assertThat(topDocId).isEqualTo(42);
    }

    @Test
    @DisplayName("Scores from both lists sum correctly for shared document")
    void sharedDocumentScoreSumsCorrectly() {
        // docId=5 rank 2 in text, rank 3 in vector
        final float expected = rrfScore(2) + rrfScore(3);

        final List<Map.Entry<Integer, Float>> result = merge(
                List.of(0, 1, 5),
                List.of(0, 1, 2, 5),
                10);

        final float actual = result.stream()
                .filter(e -> e.getKey() == 5).findFirst()
                .map(Map.Entry::getValue).orElseThrow();

        assertThat(actual).isEqualTo(expected, offset(DELTA));
    }

    @Test
    @DisplayName("pageSize limits number of returned results")
    void pageSizeLimitsResults() {
        final List<Integer> docs = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final List<Map.Entry<Integer, Float>> result = merge(docs, List.of(), 3);
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("Empty lists return empty result")
    void emptyListsReturnEmpty() {
        final List<Map.Entry<Integer, Float>> result = merge(List.of(), List.of(), 10);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Results are sorted in descending score order")
    void resultsSortedByScoreDescending() {
        // Two docs only in text list: rank 0 > rank 1
        final List<Map.Entry<Integer, Float>> result = merge(
                List.of(10, 20),
                List.of(),
                10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getValue())
                .isGreaterThanOrEqualTo(result.get(1).getValue());
    }
}
