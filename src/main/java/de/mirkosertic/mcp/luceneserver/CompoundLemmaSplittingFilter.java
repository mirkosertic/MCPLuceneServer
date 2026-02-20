package de.mirkosertic.mcp.luceneserver;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.AttributeSource;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Token filter that splits tokens containing {@code +} into separate sequential tokens.
 *
 * <p>This handles compound lemma forms produced by the German UD-GSD lemmatizer model,
 * where German contractions are lemmatized to multi-word forms:</p>
 * <ul>
 *   <li>{@code im} → {@code in+der} → tokens: {@code in}, {@code der}</li>
 *   <li>{@code zum} → {@code zu+der} → tokens: {@code zu}, {@code der}</li>
 *   <li>{@code beim} → {@code bei+der} → tokens: {@code bei}, {@code der}</li>
 * </ul>
 *
 * <p>Tokens without {@code +} pass through unchanged. Empty parts from leading,
 * trailing, or consecutive {@code +} characters are silently dropped.</p>
 *
 * <p>All split tokens share the same offset range as the original compound token,
 * since they represent different interpretations of the same source text position.</p>
 */
public final class CompoundLemmaSplittingFilter extends TokenFilter {

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final Deque<PendingToken> pendingParts = new ArrayDeque<>();

    private record PendingToken(String term, int startOffset, int endOffset) {}

    public CompoundLemmaSplittingFilter(final TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!pendingParts.isEmpty()) {
            final PendingToken pending = pendingParts.poll();
            clearAttributes();
            termAtt.setEmpty().append(pending.term);
            posIncAtt.setPositionIncrement(1);
            offsetAtt.setOffset(pending.startOffset, pending.endOffset);
            return true;
        }

        if (!input.incrementToken()) {
            return false;
        }

        final String term = termAtt.toString();
        if (term.indexOf('+') < 0) {
            return true;
        }

        // Capture offset before modifying the token
        final int startOffset = offsetAtt.startOffset();
        final int endOffset = offsetAtt.endOffset();

        final String[] parts = term.split("\\+");
        // Find first non-empty part to use as current token
        String firstPart = null;
        for (final String part : parts) {
            if (!part.isEmpty()) {
                if (firstPart == null) {
                    firstPart = part;
                } else {
                    pendingParts.add(new PendingToken(part, startOffset, endOffset));
                }
            }
        }

        if (firstPart != null) {
            termAtt.setEmpty().append(firstPart);
            // Offset is already set from the original token
        }
        // If all parts were empty (edge case: "+++"), the original token remains

        return true;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        pendingParts.clear();
    }
}
