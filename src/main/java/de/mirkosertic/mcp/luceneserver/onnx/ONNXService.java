package de.mirkosertic.mcp.luceneserver.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.CoreMLFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ONNXService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ONNXService.class);

    // Maximum token length of the model (multilingual-e5-large: 512)
    private static final int MODEL_MAX_TOKENS = 512;

    // Maximum character length of a content string (application limit)
    // ~512 Tokens * ~4 chars/token * safety factor → ~8000 chars per macro chunk
    public static final int MAX_CONTENT_CHARS = 100_000;

    // Size of a macro chunk in tokens (slightly below MODEL_MAX_TOKENS for overhead)
    private static final int MACRO_CHUNK_TOKENS = 480;

    // Overlap between macro chunks in tokens (for context continuity)
    private static final int MACRO_CHUNK_OVERLAP_TOKENS = 32;

    // Target size of final chunks in tokens
    private static final int TARGET_CHUNK_TOKENS = 128;

    public static final int DEFAULT_BATCH_SIZE = 8;

    public static final String QUERY_PREFIX = "query: ";
    public static final String PASSAGE_PREFIX = "passage: ";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int hiddenSize;
    private final Set<String> inputNames;

    public ONNXService() throws Exception {
        env = OrtEnvironment.getEnvironment();

        final OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

        // Optimization: use all CPU cores
        opts.setInterOpNumThreads(Runtime.getRuntime().availableProcessors());
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());

        // Enable graph optimization
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        registerBestAvailableProvider(opts);

        opts.setMemoryPatternOptimization(true);

        final String modelResource = "/onnxmodels/e5-large/model_quantized.onnx";
        try (final var stream = ONNXService.class
                .getResourceAsStream(modelResource)) {
            if (stream == null) {
                throw new RuntimeException("Model not found in classpath: " + modelResource);
            }
            logger.info("Loading ONNX Model from classpath: {}", modelResource);
            final byte[] modelBytes = stream.readAllBytes();
            final long start = System.currentTimeMillis();
            session = env.createSession(modelBytes, opts);
            logger.info("ONNX Model loaded in {} ms", System.currentTimeMillis() - start);
        }

        inputNames = session.getInputNames();
        logger.info("Model inputs: {}", this.inputNames);

        // Load tokenizer
        final String tokenizerResource = "/onnxmodels/e5-large/tokenizer.json";
        try (final var tokenizerConfig = ONNXService.class.getResourceAsStream(tokenizerResource)) {
            if (tokenizerConfig == null) {
                throw new RuntimeException("Tokenizer not found in classpath: " + tokenizerResource);
            }
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerConfig, Map.of("maxLength", "512", "padding", "false", "truncation", "true"));
        }

        // Read hidden size from the model
        final var outputInfo = session.getOutputInfo();
        final var shape = ((NodeInfo) outputInfo.values().toArray()[0]).getInfo();
        // last_hidden_state has shape [batch, seq, hidden]
        // We read the dimension dynamically from the first tensor output
        hiddenSize = detectHiddenSize();

        logger.info("Model loaded. Hidden size: {}", hiddenSize);
    }

    private void registerBestAvailableProvider(final OrtSession.SessionOptions opts) {
        // Priority: CUDA → DirectML (Windows) → CoreML (Apple) → CPU
        // ONNX Runtime tries each provider and falls back to CPU
        // automatically if not available.

        final String os = System.getProperty("os.name").toLowerCase();
        final String arch = System.getProperty("os.arch").toLowerCase();

        try {
            if (os.contains("mac") || arch.contains("aarch64")) {
                // Apple Silicon / macOS → CoreML (uses GPU + Neural Engine)
                opts.addCoreML(EnumSet.of(
                        CoreMLFlags.CREATE_MLPROGRAM
                ));
                logger.info("CoreML Execution Provider enabled (Apple Silicon)");
            } else if (os.contains("win")) {
                // Windows → DirectML (uses DirectX GPU)
                opts.addDirectML(0);
                logger.info("DirectML Execution Provider enabled (Windows GPU)");
            } else {
                // Linux / other → try CUDA
                opts.addCUDA(0);
                logger.info("CUDA Execution Provider enabled");
            }
        } catch (final OrtException e) {
            // Provider not available → silently fall back to CPU
            logger.warn("Hardware acceleration not available, using CPU: {}", e.getMessage());
        }
    }

    private int detectHiddenSize() throws OrtException {
        final Encoding dummy = tokenizer.encode("test", true, false);
        final long[] ids  = dummy.getIds();
        final long[] mask = dummy.getAttentionMask();
        final long[] typeIds = dummy.getTypeIds();
        final long[] shape = {1, ids.length};

        // What inputs does the model actually have?
        final Set<String> inputNames = session.getInputNames();
        logger.info("Model inputs: {}", inputNames);

        final Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try (final OnnxTensor tIds  = OnnxTensor.createTensor(env, LongBuffer.wrap(ids),     shape);
             final OnnxTensor tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask),    shape);
             final OnnxTensor tType = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape)) {

            inputs.put("input_ids", tIds);
            if (inputNames.contains("attention_mask")) {
                inputs.put("attention_mask", tMask);
            }
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids", tType);
            }

            try (final OrtSession.Result result = session.run(inputs)) {
                final float[][][] hidden = (float[][][]) result.get(0).getValue();
                return hidden[0][0].length;
            }
        }
    }

    /**
     * Creates an L2-normalized embedding for a single text.
     * Returns float[hiddenSize] (e.g. float[1024] for multilingual-e5-large).
     */
    public float[] embed(final String text, final String prefix) throws Exception {
        final String prefixedText = prefix + text;

        final Encoding encoding = tokenizer.encode(prefixedText, true, false);

        final long[] inputIds      = encoding.getIds();
        final long[] attentionMask = encoding.getAttentionMask();
        final long[] tokenTypeIds  = encoding.getTypeIds();

        final long[] tensorShape = {1, inputIds.length};

        final Map<String, OnnxTensor> inputs = new LinkedHashMap<>();

        try (final OnnxTensor tIds  = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds),      tensorShape);
             final OnnxTensor tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), tensorShape);
             final OnnxTensor tType = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds),  tensorShape)) {

            inputs.put("input_ids", tIds);
            if (inputNames.contains("attention_mask")) {
                inputs.put("attention_mask", tMask);
            }
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids", tType);
            }

            try (final OrtSession.Result result = session.run(inputs)) {
                final float[][][] hidden = (float[][][]) result.get(0).getValue();
                final float[][] tokenEmbeddings = hidden[0]; // [seqLen][hiddenSize]

                final float[] pooled = meanPool(tokenEmbeddings, attentionMask);
                return l2normalize(pooled);
            }
        }
    }

    private float[] meanPool(final float[][] tokenEmbeddings, final long[] attentionMask) {
        final float[] result = new float[hiddenSize];
        int count = 0;
        for (int t = 0; t < tokenEmbeddings.length; t++) {
            if (attentionMask[t] == 1) {
                for (int d = 0; d < hiddenSize; d++) {
                    result[d] += tokenEmbeddings[t][d];
                }
                count++;
            }
        }
        if (count > 0) {
            for (int d = 0; d < hiddenSize; d++) result[d] /= count;
        }
        return result;
    }

    private float[] l2normalize(final float[] v) {
        double norm = 0.0;
        for (final float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm > 1e-12) {
            for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        }
        return v;
    }

    // Late Chunking implementation

    /**
     * Processes a list of documents with Late Chunking and batching.
     * Documents are internally split into batches of the configured size.
     *
     * @param contents List of document texts
     * @param prefix   "passage: " for indexing, "query: " for search queries
     * @param batchSize the batch size, can be passed as DEFAULT_BATCH_SIZE (8)
     * @return Per document a list of chunk embeddings
     */
    public List<List<float[]>> embedBatch(final List<String> contents,
                                          final String prefix,
                                          final int batchSize) throws OrtException {
        // Result list in document order
        final List<List<float[]>> results = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) results.add(null);

        // Split documents into batches and process
        for (int batchStart = 0; batchStart < contents.size(); batchStart += batchSize) {
            final int batchEnd = Math.min(batchStart + batchSize, contents.size());
            final List<String> batch = contents.subList(batchStart, batchEnd);

            final List<List<float[]>> batchResults = processBatch(batch, prefix);

            for (int i = 0; i < batchResults.size(); i++) {
                results.set(batchStart + i, batchResults.get(i));
            }
        }
        return results;
    }

    /**
     * Single document with Late Chunking — delegates internally to batch logic.
     */
    public List<float[]> embedWithLateChunking(final String content,
                                               final String prefix,
                                               final int batchSize) throws OrtException {
        if (content == null || content.isBlank()) return Collections.emptyList();
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "Content exceeds limit of " + MAX_CONTENT_CHARS
                            + " characters (current: " + content.length() + ")");
        }
        return embedBatch(List.of(content), prefix, batchSize).getFirst();
    }

    /**
     * Processes a single batch of documents.
     * Short documents (≤ MODEL_MAX_TOKENS) are padded together and processed
     * in a single ONNX forward pass.
     * Long documents (> MODEL_MAX_TOKENS) are handled individually via Long Late Chunking,
     * as their macro chunks have different lengths.
     */
    private List<List<float[]>> processBatch(final List<String> contents,
                                             final String prefix) throws OrtException {
        // Tokenize all documents
        final List<Encoding> encodings = new ArrayList<>(contents.size());
        for (final String content : contents) {
            final String prefixed = prefix + content;
            encodings.add(tokenizer.encode(prefixed, true, false));
        }

        // Separate short and long documents
        final List<Integer> shortIdx = new ArrayList<>();
        final List<Integer> longIdx  = new ArrayList<>();
        for (int i = 0; i < encodings.size(); i++) {
            if (encodings.get(i).getIds().length <= MODEL_MAX_TOKENS) {
                shortIdx.add(i);
            } else {
                longIdx.add(i);
            }
        }

        final List<List<float[]>> results = new ArrayList<>(Collections.nCopies(contents.size(), null));

        // Short documents: shared batch forward pass
        if (!shortIdx.isEmpty()) {
            final List<Encoding> shortEncodings = new ArrayList<>();
            for (final int i : shortIdx) shortEncodings.add(encodings.get(i));

            final List<List<float[]>> shortResults = processShortBatch(shortEncodings);
            for (int j = 0; j < shortIdx.size(); j++) {
                results.set(shortIdx.get(j), shortResults.get(j));
            }
        }

        // Long documents: individually via Long Late Chunking
        for (final int i : longIdx) {
            results.set(i, longLateChunking(encodings.get(i)));
        }

        return results;
    }

    /**
     * Batch forward pass for short documents (≤ MODEL_MAX_TOKENS).
     * <p>
     * All sequences are padded to the length of the longest sequence in the batch
     * (padding token = 0, attention mask = 0 for padding positions).
     * The ONNX tensor has shape [batchSize, maxSeqLen].
     */
    private List<List<float[]>> processShortBatch(final List<Encoding> encodings) throws OrtException {
        final int n          = encodings.size();
        final int maxSeqLen  = encodings.stream()
                .mapToInt(e -> e.getIds().length)
                .max()
                .orElse(1);

        // Prepare flat arrays for the batch tensor (row-major)
        final long[] batchIds      = new long[n * maxSeqLen];
        final long[] batchMask     = new long[n * maxSeqLen];
        final long[] batchTypeIds  = new long[n * maxSeqLen];

        // Remember sequence lengths for later pooling
        final int[] seqLengths = new int[n];

        for (int i = 0; i < n; i++) {
            final long[] ids     = encodings.get(i).getIds();
            final long[] mask    = encodings.get(i).getAttentionMask();
            final long[] typeIds = encodings.get(i).getTypeIds();
            seqLengths[i]  = ids.length;

            final int offset = i * maxSeqLen;
            System.arraycopy(ids,     0, batchIds,     offset, ids.length);
            System.arraycopy(mask,    0, batchMask,    offset, mask.length);
            System.arraycopy(typeIds, 0, batchTypeIds, offset, typeIds.length);
            // Rest remains 0 (padding: input_id=0, attention_mask=0)
        }

        final long[] shape = {n, maxSeqLen};

        // Single forward pass for the entire batch
        final float[][][] tokenEmbeddings = runBatchForwardPass(
                batchIds, batchMask, batchTypeIds, shape, n, maxSeqLen);

        // Per document: compute chunk spans and apply mean pooling
        final List<List<float[]>> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final long[] docMask = Arrays.copyOfRange(batchMask, i * maxSeqLen,
                    i * maxSeqLen + seqLengths[i]);
            final List<int[]> spans = computeTokenSpans(seqLengths[i]);
            results.add(poolChunks(tokenEmbeddings[i], spans, docMask));
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Long Late Chunking (for documents > MODEL_MAX_TOKENS)
    // -------------------------------------------------------------------------

    private List<float[]> longLateChunking(final Encoding fullEncoding) throws OrtException {
        final long[] allIds   = fullEncoding.getIds();
        final int totalTokens = allIds.length;

        final List<float[]> allEmbeddings = new ArrayList<>();
        int macroStart = 0;

        while (macroStart < totalTokens) {
            final int macroEnd   = Math.min(macroStart + MACRO_CHUNK_TOKENS, totalTokens);
            final long[] macroIds  = Arrays.copyOfRange(allIds, macroStart, macroEnd);
            final long[] macroMask = new long[macroIds.length];
            final long[] macroType = new long[macroIds.length];
            Arrays.fill(macroMask, 1L);

            final long[] shape = {1, macroIds.length};
            final float[][][] hidden = runBatchForwardPass(
                    macroIds, macroMask, macroType, shape, 1, macroIds.length);

            final List<int[]> spans = computeTokenSpans(macroIds.length);

            final boolean isFirstMacro = (macroStart == 0);
            final int overlapTokens    = isFirstMacro ? 0 : MACRO_CHUNK_OVERLAP_TOKENS;
            int skipChunks       = 0;
            for (final int[] span : spans) {
                if (span[0] < overlapTokens) skipChunks++;
                else break;
            }

            final List<float[]> macroEmbeddings = poolChunks(hidden[0], spans, macroMask);
            for (int i = skipChunks; i < macroEmbeddings.size(); i++) {
                allEmbeddings.add(macroEmbeddings.get(i));
            }

            final int nextStart = macroStart + MACRO_CHUNK_TOKENS - MACRO_CHUNK_OVERLAP_TOKENS;
            if (nextStart <= macroStart) break;
            macroStart = nextStart;
        }
        return allEmbeddings;
    }

    // -------------------------------------------------------------------------
    // ONNX Forward Pass
    // -------------------------------------------------------------------------

    /**
     * Executes a batch forward pass.
     * Input shape:  [batchSize, seqLen]
     * Output shape: [batchSize, seqLen, hiddenSize]
     */
    private float[][][] runBatchForwardPass(final long[] inputIds,
                                            final long[] attentionMask,
                                            final long[] tokenTypeIds,
                                            final long[] shape,
                                            final int batchSize,
                                            final int seqLen) throws OrtException {
        try (final OnnxTensor tIds  = OnnxTensor.createTensor(env,
                LongBuffer.wrap(inputIds),     shape);
             final OnnxTensor tMask = OnnxTensor.createTensor(env,
                     LongBuffer.wrap(attentionMask), shape);
             final OnnxTensor tType = OnnxTensor.createTensor(env,
                     LongBuffer.wrap(tokenTypeIds),  shape)) {

            final Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", tIds);
            if (inputNames.contains("attention_mask"))  inputs.put("attention_mask",  tMask);
            if (inputNames.contains("token_type_ids"))  inputs.put("token_type_ids",  tType);

            try (final OrtSession.Result result = session.run(inputs)) {
                // last_hidden_state: [batchSize, seqLen, hiddenSize]
                final float[][][] raw = (float[][][]) result.get(0).getValue();

                // Safety reshape if ONNX output shape differs from batchSize/seqLen
                if (raw.length == batchSize) return raw;

                // Restructure flat array
                final float[][][] reshaped = new float[batchSize][seqLen][hiddenSize];
                for (int b = 0; b < batchSize; b++)
                    System.arraycopy(raw[b], 0, reshaped[b], 0, seqLen);
                return reshaped;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pooling and helper methods
    // -------------------------------------------------------------------------

    private List<int[]> computeTokenSpans(final int totalTokens) {
        final List<int[]> spans = new ArrayList<>();
        int start = 1;             // Index 0 = [CLS]
        final int end   = totalTokens - 1; // last = [SEP]
        while (start < end) {
            final int chunkEnd = Math.min(start + TARGET_CHUNK_TOKENS, end);
            spans.add(new int[]{start, chunkEnd});
            start = chunkEnd;
        }
        return spans;
    }

    private List<float[]> poolChunks(final float[][] tokenEmbeddings,
                                     final List<int[]> spans,
                                     final long[] attentionMask) {
        final List<float[]> result = new ArrayList<>(spans.size());
        for (final int[] span : spans) {
            final int start = span[0];
            final int end   = Math.min(span[1], tokenEmbeddings.length);
            if (start >= end) continue;

            final float[] pooled = new float[hiddenSize];
            int count = 0;
            for (int t = start; t < end; t++) {
                if (t < attentionMask.length && attentionMask[t] == 1L) {
                    for (int d = 0; d < hiddenSize; d++) {
                        pooled[d] += tokenEmbeddings[t][d];
                    }
                    count++;
                }
            }
            if (count > 0) {
                for (int d = 0; d < hiddenSize; d++) pooled[d] /= count;
            }
            result.add(l2normalize(pooled));
        }
        return result;
    }

    @Override
    public void close() throws OrtException {
        session.close();
        env.close();
        tokenizer.close();
    }
}
