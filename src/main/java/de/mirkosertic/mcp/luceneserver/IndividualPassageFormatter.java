package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.search.uhighlight.PassageFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom passage formatter that returns individual passages as separate objects
 * instead of joining them into a single string (as {@link org.apache.lucene.search.uhighlight.DefaultPassageFormatter} does).
 *
 * <p>The {@link #format} method returns a {@code List<FormattedPassage>} so that
 * the caller can process each passage independently — computing per-passage
 * scores, matched terms, coverage, and position.</p>
 *
 * <p>This formatter is designed to be used together with
 * {@link LuceneIndexService.PassageAwareHighlighter} which exposes the
 * {@code highlightFieldsAsObjects} API that preserves the raw {@link Object}
 * return value rather than casting it to {@link String}.</p>
 */
class IndividualPassageFormatter extends PassageFormatter {

    /**
     * A single formatted passage with its Lucene-computed metadata.
     *
     * @param text        the passage text with matched terms wrapped in {@code <em>...</em>} tags
     * @param score       the BM25-based passage score assigned by Lucene's highlighter
     * @param numMatches  number of query-term matches within this passage
     * @param startOffset character offset of the passage start in the original content
     * @param endOffset   character offset of the passage end in the original content
     */
    record FormattedPassage(String text, float score, int numMatches, int startOffset, int endOffset) {
    }

    /**
     * Format each passage individually and return them as a list.
     *
     * @param passages the Lucene-detected passages (sorted by score, best first)
     * @param content  the full stored content of the document
     * @return a {@code List<FormattedPassage>} — one entry per passage
     */
    @Override
    public Object format(final org.apache.lucene.search.uhighlight.Passage[] passages, final String content) {
        final List<FormattedPassage> result = new ArrayList<>(passages.length);
        for (final org.apache.lucene.search.uhighlight.Passage passage : passages) {
            final String text = formatSinglePassage(passage, content);
            result.add(new FormattedPassage(
                    text,
                    passage.getScore(),
                    passage.getNumMatches(),
                    passage.getStartOffset(),
                    passage.getEndOffset()
            ));
        }
        return result;
    }

    /**
     * Format a single passage: insert {@code <em>} tags around matched terms
     * and prepend an ellipsis if the passage does not start at the beginning
     * of the document.
     */
    private String formatSinglePassage(final org.apache.lucene.search.uhighlight.Passage passage,
                                       final String content) {
        final StringBuilder sb = new StringBuilder();
        int pos = passage.getStartOffset();

        // Prepend ellipsis when the passage does not begin at the document start
        if (pos > 0) {
            sb.append("...");
        }

        for (int i = 0; i < passage.getNumMatches(); i++) {
            final int start = passage.getMatchStarts()[i];
            final int end = passage.getMatchEnds()[i];
            // Handle overlapping terms: only append if we haven't passed this point
            if (start > pos) {
                sb.append(content, pos, start);
            }
            if (end > pos) {
                sb.append("<em>");
                sb.append(content, Math.max(pos, start), end);
                sb.append("</em>");
                pos = end;
            }
        }

        // Append any remaining content after the last match within this passage
        if (passage.getEndOffset() > pos) {
            sb.append(content, pos, passage.getEndOffset());
        }

        return sb.toString();
    }
}
