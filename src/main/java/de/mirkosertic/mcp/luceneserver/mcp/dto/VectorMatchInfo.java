package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

/**
 * Information about a vector (semantic) match for a search result document.
 *
 * <p>Present only when hybrid (vector + text) search is active and the document
 * was retrieved or boosted by the KNN vector query.  The fields describe which
 * chunk of the document triggered the match and how similar it was to the query
 * embedding.</p>
 */
public record VectorMatchInfo(
        @Description("True when this document was matched (or boosted) via the KNN vector search path")
        boolean matchedViaVector,

        @Description("Zero-based index of the chunk inside this document that had the highest vector similarity to the query")
        int matchedChunkIndex,

        @Description("Text content of the matching chunk (may be null if the chunk text was not stored)")
        String matchedChunkText,

        @Description("Raw vector similarity score for the matching chunk as returned by Lucene's DOT_PRODUCT scorer (range 0.0–1.0)")
        float vectorScore
) {

    /**
     * Factory method for a confirmed vector match.
     *
     * @param chunkIndex  zero-based index of the best-matching chunk
     * @param chunkText   stored text of that chunk (may be null)
     * @param vectorScore raw Lucene dot-product similarity score
     * @return a populated {@code VectorMatchInfo}
     */
    public static VectorMatchInfo matched(final int chunkIndex, final String chunkText, final float vectorScore) {
        return new VectorMatchInfo(true, chunkIndex, chunkText, vectorScore);
    }

    /**
     * Factory method used when the vector score is known but chunk details are not available.
     *
     * @param vectorScore raw Lucene dot-product similarity score
     * @return a {@code VectorMatchInfo} with no chunk detail
     */
    public static VectorMatchInfo matchedWithScore(final float vectorScore) {
        return new VectorMatchInfo(true, -1, null, vectorScore);
    }
}
