package de.mirkosertic.mcp.luceneserver;

import opennlp.tools.lemmatizer.LemmatizerModel;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachedNLPLemmatizerOpTest {

    private NLPLemmatizerOp delegate;
    private LemmatizerModel model;
    private LemmatizerCacheStats stats;
    private CachedNLPLemmatizerOp cachedLemmatizer;

    @BeforeEach
    void setUp() throws IOException {
        // Load English lemmatizer model from classpath
        final InputStream modelStream = getClass().getResourceAsStream("/opennlp-en-ud-ewt-lemmas-1.3-2.5.4.bin");
        assertThat(modelStream).isNotNull();

        model = new LemmatizerModel(modelStream);
        delegate = new NLPLemmatizerOp(null, model);
        stats = new LemmatizerCacheStats();
        cachedLemmatizer = new CachedNLPLemmatizerOp(delegate, model, stats, CachedNLPLemmatizerOp.createSharedCache(200_000, stats));
    }

    @Test
    void testCacheMissOnFirstAccess() {
        final String[] tokens = {"running"};
        final String[] tags = {"VBG"};

        final String[] lemmas = cachedLemmatizer.lemmatize(tokens, tags);

        assertThat(lemmas).hasSize(1);
        assertThat(lemmas[0]).isEqualTo("run"); // "running" -> "run"

        assertThat(stats.getTotalRequests()).isEqualTo(1);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getCacheHits()).isEqualTo(0);
        assertThat(stats.getCurrentSize()).isEqualTo(1);
    }

    @Test
    void testCacheHitOnSecondAccess() {
        final String[] tokens = {"running", "running"};
        final String[] tags = {"VBG", "VBG"};

        // First call: both tokens miss cache, entire sentence is lemmatized and cached
        final String[] lemmas1 = cachedLemmatizer.lemmatize(tokens, tags);
        assertThat(lemmas1).hasSize(2);
        assertThat(lemmas1[0]).isEqualTo("run");
        assertThat(lemmas1[1]).isEqualTo("run");
        assertThat(stats.getCacheMisses()).isEqualTo(2); // Both tokens missed

        // Second call with same tokens: both should hit cache
        final String[] lemmas2 = cachedLemmatizer.lemmatize(tokens, tags);
        assertThat(lemmas2).hasSize(2);
        assertThat(lemmas2[0]).isEqualTo("run");
        assertThat(lemmas2[1]).isEqualTo("run");

        assertThat(stats.getTotalRequests()).isEqualTo(4); // 2 from first call + 2 from second call
        assertThat(stats.getCacheMisses()).isEqualTo(2); // Only first call missed
        assertThat(stats.getCacheHits()).isEqualTo(2); // Second call hit both
        assertThat(stats.getHitRate()).isEqualTo(50.0);
    }

    @Test
    void testCorrectLemmatization() {
        final String[] tokens = {"running", "ran", "runs", "go", "went", "going"};
        final String[] tags = {"VBG", "VBD", "VBZ", "VB", "VBD", "VBG"};

        final String[] lemmas = cachedLemmatizer.lemmatize(tokens, tags);

        assertThat(lemmas).hasSize(6);
        assertThat(lemmas[0]).isEqualTo("run");
        assertThat(lemmas[1]).isEqualTo("run");
        assertThat(lemmas[2]).isEqualTo("run");
        assertThat(lemmas[3]).isEqualTo("go");
        assertThat(lemmas[4]).isEqualTo("go");
        assertThat(lemmas[5]).isEqualTo("go");
    }

    @Test
    void testDifferentPosTagsCreateDifferentCacheKeys() {
        // "contract" as noun vs verb should produce different lemmas
        final String[] tokens1 = {"contract"};
        final String[] tags1 = {"NN"}; // Noun

        final String[] tokens2 = {"contract"};
        final String[] tags2 = {"VB"}; // Verb

        final String[] lemmas1 = cachedLemmatizer.lemmatize(tokens1, tags1);
        final String[] lemmas2 = cachedLemmatizer.lemmatize(tokens2, tags2);

        // Both should be cache misses since (token, POS) pairs differ
        assertThat(stats.getCacheMisses()).isEqualTo(2);
        assertThat(stats.getCacheHits()).isEqualTo(0);
        assertThat(stats.getCurrentSize()).isEqualTo(2); // Two different cache entries
    }

    @Test
    void testNullTokensThrowsException() {
        final String[] tags = {"NN"};

        assertThatThrownBy(() -> cachedLemmatizer.lemmatize(null, tags))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tokens and tags must not be null");
    }

    @Test
    void testNullTagsThrowsException() {
        final String[] tokens = {"test"};

        assertThatThrownBy(() -> cachedLemmatizer.lemmatize(tokens, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tokens and tags must not be null");
    }

    @Test
    void testMismatchedArrayLengthsThrowsException() {
        final String[] tokens = {"test", "word"};
        final String[] tags = {"NN"};

        assertThatThrownBy(() -> cachedLemmatizer.lemmatize(tokens, tags))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokens and tags must have the same length");
    }

    @Test
    void testThreadSafety() throws InterruptedException, IOException {
        // Create a fresh stats and lemmatizer for this test to avoid state leakage
        final LemmatizerCacheStats threadTestStats = new LemmatizerCacheStats();
        final CachedNLPLemmatizerOp threadTestLemmatizer = new CachedNLPLemmatizerOp(delegate, model, threadTestStats, CachedNLPLemmatizerOp.createSharedCache(200_000, threadTestStats));

        final int threadCount = 10;
        final int operationsPerThread = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);

        // Same tokens/tags repeated across threads to test cache concurrency
        final String[] tokens = {"running", "ran", "runs"};
        final String[] tags = {"VBG", "VBD", "VBZ"};
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        final String[] lemmas = threadTestLemmatizer.lemmatize(tokens, tags);
                        if (lemmas.length != 3) {
                            failure.set(new AssertionError("Expected 3 lemmas but got " + lemmas.length));
                            return;
                        }
                        // Don't assert inside threads - just track the failure
                        if (!lemmas[0].equals("run") || !lemmas[1].equals("run") || !lemmas[2].equals("run")) {
                            failure.set(new AssertionError("Lemmatization failed: " + java.util.Arrays.toString(lemmas)));
                            return;
                        }
                    }
                } catch (final Throwable t) {
                    failure.set(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        final boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertThat(completed).as("All threads should complete within timeout").isTrue();

        executor.shutdown();
        final boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(terminated).as("Executor should terminate").isTrue();

        // Check if any thread failed
        if (failure.get() != null) {
            throw new AssertionError("Thread failed during test", failure.get());
        }

        // After all threads complete, verify metrics
        final long totalRequests = threadTestStats.getTotalRequests();
        final long expectedRequests = (long) threadCount * operationsPerThread * tokens.length;

        // Log for debugging
        if (totalRequests != expectedRequests) {
            System.err.println("Expected " + expectedRequests + " requests but got " + totalRequests);
            System.err.println("Stats: " + threadTestStats.getMetrics());
        }

        assertThat(totalRequests).isEqualTo(expectedRequests);

        // Hit rate should be very high (close to 100%) since same tokens repeated
        assertThat(threadTestStats.getHitRate()).isGreaterThan(90.0);

        // Only 3 unique (token, POS) pairs
        assertThat(threadTestStats.getCurrentSize()).isEqualTo(3);
    }

    @Test
    void testCacheEvictionTracking() {
        // Since we can't easily control Caffeine's eviction timing in a test,
        // we'll just verify that the eviction counter can be incremented
        stats.recordEviction();
        stats.recordEviction();
        stats.recordEviction();

        assertThat(stats.getEvictions()).isEqualTo(3);
    }

    @Test
    void testEmptyArrays() {
        final String[] tokens = {};
        final String[] tags = {};

        final String[] lemmas = cachedLemmatizer.lemmatize(tokens, tags);

        assertThat(lemmas).isEmpty();
        assertThat(stats.getTotalRequests()).isEqualTo(0); // No tokens processed
    }

    @Test
    void testSingleTokenMultipleTimes() {
        final String[] tokens = {"test"};
        final String[] tags = {"NN"};

        // First call - cache miss
        cachedLemmatizer.lemmatize(tokens, tags);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getCacheHits()).isEqualTo(0);

        // Second call - cache hit
        cachedLemmatizer.lemmatize(tokens, tags);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getCacheHits()).isEqualTo(1);

        // Third call - cache hit
        cachedLemmatizer.lemmatize(tokens, tags);
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getCacheHits()).isEqualTo(2);

        assertThat(stats.getHitRate()).isEqualTo(200.0 / 3.0);
    }

    @Test
    void testCaseInsensitiveCachingForCommonWords() {
        // First: "running" (lowercase)
        final String[] tokens1 = {"running"};
        final String[] tags1 = {"VBG"};
        cachedLemmatizer.lemmatize(tokens1, tags1);

        // Second: "Running" (capitalized) - should hit cache
        final String[] tokens2 = {"Running"};
        final String[] tags2 = {"VBG"};
        cachedLemmatizer.lemmatize(tokens2, tags2);

        // Should have 1 miss (first) + 1 hit (second)
        assertThat(stats.getCacheMisses()).isEqualTo(1);
        assertThat(stats.getCacheHits()).isEqualTo(1);
        assertThat(stats.getCurrentSize()).isEqualTo(1); // Only one cache entry for both
    }

    @Test
    void testCaseSensitiveCachingForProperNouns() {
        // "Berlin" (proper noun) and "berlin" (lowercase) should be separate cache entries
        final String[] tokens1 = {"Berlin"};
        final String[] tags1 = {"NNP"};
        cachedLemmatizer.lemmatize(tokens1, tags1);

        final String[] tokens2 = {"berlin"};
        final String[] tags2 = {"NNP"};
        cachedLemmatizer.lemmatize(tokens2, tags2);

        // Both should miss cache (different keys)
        assertThat(stats.getCacheMisses()).isEqualTo(2);
        assertThat(stats.getCacheHits()).isEqualTo(0);
        assertThat(stats.getCurrentSize()).isEqualTo(2); // Two different cache entries
    }
}
