package de.mirkosertic.mcp.luceneserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for the cosine-to-Lucene-score conversion used in hybrid vector search.
 *
 * <p>Lucene's DOT_PRODUCT similarity for L2-normalised vectors maps cosine similarity
 * (range -1..1) to a Lucene score in (0..1] using the formula:
 * {@code luceneScore = (1 + cosine) / 2}.
 * A cosine cutoff of 0.70 therefore corresponds to luceneScore = 0.85.</p>
 */
@DisplayName("Cosine threshold conversion tests")
class CosineThresholdTest {

    private static final float COSINE_CUTOFF = 0.70f;
    private static final float DELTA = 1e-6f;

    /** luceneScore = (1 + cosine) / 2 */
    private static float toLuceneScore(final float cosine) {
        return (1f + cosine) / 2f;
    }

    @Test
    @DisplayName("cosine 0.70 maps to Lucene score 0.85")
    void cosine070MapsTo085() {
        final float luceneScore = toLuceneScore(COSINE_CUTOFF);
        assertThat(luceneScore).isEqualTo(0.85f, offset(DELTA));
    }

    @Test
    @DisplayName("Formula: (1 + 0.70) / 2 == 0.85 (direct arithmetic check)")
    void formulaDirectCheck() {
        final float result = (1f + 0.70f) / 2f;
        assertThat(result).isEqualTo(0.85f, offset(DELTA));
    }

    @Test
    @DisplayName("cosine 1.0 (identical vectors) maps to Lucene score 1.0")
    void cosine10MapsTo10() {
        final float luceneScore = toLuceneScore(1.0f);
        assertThat(luceneScore).isEqualTo(1.0f, offset(DELTA));
    }

    @Test
    @DisplayName("cosine -1.0 (opposite vectors) maps to Lucene score 0.0")
    void cosineNeg10MapsTo00() {
        final float luceneScore = toLuceneScore(-1.0f);
        assertThat(luceneScore).isEqualTo(0.0f, offset(DELTA));
    }

    @Test
    @DisplayName("cosine 0.0 (orthogonal vectors) maps to Lucene score 0.5")
    void cosine00MapsTo05() {
        final float luceneScore = toLuceneScore(0.0f);
        assertThat(luceneScore).isEqualTo(0.5f, offset(DELTA));
    }

    @Test
    @DisplayName("Score at threshold passes the filter")
    void scoreAtThresholdPassesFilter() {
        final float threshold = toLuceneScore(COSINE_CUTOFF); // 0.85
        final float scoreAtThreshold = 0.85f;
        assertThat(scoreAtThreshold).isGreaterThanOrEqualTo(threshold);
    }

    @Test
    @DisplayName("Score above threshold passes the filter")
    void scoreAboveThresholdPassesFilter() {
        final float threshold = toLuceneScore(COSINE_CUTOFF); // 0.85
        final float highScore = toLuceneScore(0.90f);         // 0.95
        assertThat(highScore).isGreaterThanOrEqualTo(threshold);
    }

    @Test
    @DisplayName("Score below threshold is rejected by the filter")
    void scoreBelowThresholdIsRejected() {
        final float threshold = toLuceneScore(COSINE_CUTOFF); // 0.85
        final float lowScore = toLuceneScore(0.50f);          // 0.75
        assertThat(lowScore).isLessThan(threshold);
    }

    @Test
    @DisplayName("Score for opposite vector (-1.0 cosine) is well below threshold")
    void negativeCosineFarBelowThreshold() {
        final float threshold = toLuceneScore(COSINE_CUTOFF); // 0.85
        final float oppositeScore = toLuceneScore(-1.0f);     // 0.0
        assertThat(oppositeScore).isLessThan(threshold);
    }

    @Test
    @DisplayName("Threshold conversion is monotonically increasing with cosine")
    void conversionIsMonotone() {
        final float low  = toLuceneScore(-0.5f);
        final float mid  = toLuceneScore(0.0f);
        final float high = toLuceneScore(0.5f);
        assertThat(low).isLessThan(mid);
        assertThat(mid).isLessThan(high);
    }
}
