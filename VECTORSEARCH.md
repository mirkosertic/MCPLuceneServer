# MCP Lucene Server - Hybrid Vector Search

This document describes the hybrid search architecture combining BM25 text search with dense
vector search (semantic search) using ONNX-based transformer embeddings.

For general feature overview and user-facing documentation, see [README.md](README.md).
For analyzer pipeline details, see [PIPELINE.md](PIPELINE.md).
For ONNX model export and quantization, see [ONNX.md](ONNX.md).

---

## 1. Overview

### The Limits of Pure BM25 Search

BM25 is an excellent lexical retrieval algorithm: it finds documents containing the searched
words and ranks them by frequency and document length. But it has a fundamental gap — the
**lexical gap**:

- A document containing "Vereinbarung" will not be found when searching for "Vertrag"
- An English document with "agreement" remains invisible during a German search for "Vertrag"
- Synonyms, paraphrases, and cross-lingual expressions are fundamentally never matched

The sentence "Der Lieferant verpflichtet sich zur pünktlichen Lieferung" is semantically
related to "Vertrag" — but does not contain the word.

### The Semantic Gap

```
Query: "Vertrag"

BM25 finds:   "Vertrag", "Vertrages", "Vertragsklausel" (via lemmatization)
BM25 MISSES:  "Vereinbarung", "Agreement", "Abkommen", "MOU", "Rahmenvertrag"
              (when the term "Vertrag" does not appear in the document)

Embedding search finds all of them — because the model understands the semantic
proximity of these terms.
```

### The Solution: Hybrid Search

The MCP Lucene Server combines both approaches:

1. **BM25 Search** (lexical): Exact matches, wildcards, lemmatization, transliteration
2. **Dense Vector Search** (semantic): Cosine similarity between query and document embeddings
3. **RRF Fusion** (Reciprocal Rank Fusion): Both ranked lists are merged

The result: documents appear in search results even when the searched terms do not appear
verbatim in the document.

---

## 2. Late Chunking

### The Problem with Naive Chunking

Long documents do not fit into the context window of a transformer model (512 tokens).
The naive approach: split the document into chunks and embed each chunk independently.

**Problem**: Every chunk loses its document context. A chunk "The plaintiff..." on page 5
of a contract no longer "knows" that it is a purchase agreement between Company A and
Company B. Embedding quality suffers.

### Late Chunking

Late Chunking solves this problem by changing the order of operations:

```
Naive Chunking:
  Document → Chunks → [Embed Chunk 1] [Embed Chunk 2] [Embed Chunk 3]
  (each chunk without document context)

Late Chunking:
  Document → Tokenize → Forward Pass (entire document / macro-chunk) → Token Embeddings
           → Extract Chunk Spans → Mean Pooling per Span → Chunk Embeddings
  (each chunk embedding is pooled from contextualized token representations)
```

The key difference: token embeddings are influenced by the entire surrounding context
(self-attention over all tokens). Only after this are they aggregated into chunk embeddings.

### Implementation in ONNXService

`ONNXService.embedWithLateChunking()` implements this procedure and returns `List<float[]>` —
one embedding vector per chunk:

```
Constants:
  MODEL_MAX_TOKENS       = 512   — maximum input length of the model
  MACRO_CHUNK_TOKENS     = 480   — size of a macro-chunk (with buffer for [CLS]/[SEP])
  MACRO_CHUNK_OVERLAP    = 32    — overlap between macro-chunks (context continuity)
  TARGET_CHUNK_TOKENS    = 128   — target size of final chunk spans
```

**Short documents** (≤ 512 tokens): A single forward pass; chunk spans are extracted directly
from the token embeddings. Multiple short documents are padded and processed in a batch.

**Long documents** (> 512 tokens): Long Late Chunking — the document is split into macro-chunks
with overlap. One forward pass per macro-chunk; overlapping chunks at the beginning of each
macro-chunk are discarded.

---

## 3. Indexing Pipeline

### Block Join Structure in the Lucene Index

For each document, when the vector search profile is active, multiple Lucene documents are
written as a **block** — children first, parent last:

```
IndexWriter.addDocuments([
  Child Doc 0: { _doc_type="child", file_path, chunk_index=0, chunk_text, embedding[768] }
  Child Doc 1: { _doc_type="child", file_path, chunk_index=1, chunk_text, embedding[768] }
  Child Doc 2: { _doc_type="child", file_path, chunk_index=2, chunk_text, embedding[768] }
  ...
  Parent Doc:  { _doc_type="parent", file_path, content, title, language, ... }  <-- MUST BE LAST
])
```

This structure is a Lucene requirement for Block Join queries: the parent must always be the
last document in the block.

### Child Document Fields

| Field | Type | Stored | Purpose |
|-------|------|--------|---------|
| `_doc_type` | StringField | Yes | Value `"child"` — distinguishes from parent |
| `file_path` | StringField | Yes | Links to the parent document |
| `chunk_index` | StoredField (int) | Yes | Position of the chunk within the document |
| `chunk_text` | StoredField | Yes | Approximate text representation of the chunk |
| `embedding` | KnnFloatVectorField | No (vector index only) | L2-normalized embedding vector |

The `embedding` vector uses `VectorSimilarityFunction.DOT_PRODUCT`. Since the embeddings are
L2-normalized, the dot product is equivalent to cosine similarity.

### Atomic Deletion

`file_path` is present on **all** documents — parent and all child documents. This enables
atomic deletion of the entire block:

```java
indexWriter.deleteDocuments(new Term("file_path", filePath.toString()));
indexWriter.addDocuments(block);  // New block with updated embeddings
```

### Schema Version

SCHEMA_VERSION = **9** (introduced with vector search). Stored in Lucene commit metadata.
On version mismatch at startup: automatic full re-crawl.

The embedding dimension is also stored in commit metadata (`embedding_dimension`). A switch
between models (768 → 1024 or vice versa) is automatically detected as a mismatch and
triggers a full re-crawl.

### Fallback Without Vector Search Profile

When the `vectorsearch` profile is not active, `onnxService == null`. In this case, only
`addDocument(parentDoc)` is called — no embedding, no child documents, zero additional overhead.

---

## 4. Query Pipeline

### Step 1: Query Embedding

For semantic search, the query is converted to a vector:

```java
float[] queryVector = onnxService.embed(queryString, "query: ");
```

The `"query: "` prefix is essential. The E5 models use **asymmetric retrieval**:
- Documents at indexing time: `"passage: " + text`
- Search queries: `"query: " + queryString`

The different prefixes optimize similarity measurement between short queries and long document
passages. Without prefixes, retrieval quality degrades significantly.

### Step 2: KNN Vector Query

```java
KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery("embedding", queryVector, k=50);
TopDocs vectorTopDocs = searcher.search(knnQuery, 50);
```

The KNN query retrieves the 50 nearest neighbors in vector space — on child documents.

### Step 3: Cosine Threshold

Not all KNN results are relevant. A threshold filters poor matches:

```
Cosine Cut-Off:  0.70
Lucene Score:    (1 + 0.70) / 2 = 0.85

(DOT_PRODUCT on normalized vectors: Lucene returns (1 + cosine) / 2 as the score)
```

Child documents with `score < 0.85` are discarded.

### Step 4: Child → Parent Mapping

The KNN query hits child documents (chunks). For search results, the corresponding parent
documents are needed:

```java
// For each child document: find parent via file_path
Query parentQuery = new BooleanQuery.Builder()
    .add(new TermQuery(new Term("file_path", filePath)), MUST)
    .add(new TermQuery(new Term("_doc_type", "parent")), MUST)
    .build();
TopDocs parentHits = searcher.search(parentQuery, 1);
```

Only the best child match (highest vector score) is kept per parent.

### Step 5: RRF Fusion

The BM25 ranked list and the vector ranked list are merged via RRF (see Section 5).

### Fallback on Error

If vector search throws an exception (model timeout, OOM, etc.), the system automatically
falls back to text-only results:

```java
} catch (final Exception e) {
    logger.warn("Vector search failed, falling back to text-only: {}", e.getMessage());
    return new VectorMergeResult(textDocs, Map.of());
}
```

---

## 5. Scoring & Ranking (RRF)

### Why Not a Simple Boost Factor?

BM25 scores and cosine scores exist in completely different value ranges:
- BM25: typically 1–20 (depending on IDF, term frequency, document length)
- Cosine: 0–1

An additive or multiplicative boost would let BM25 dominate and render semantic matches
invisible. **RRF solves this problem** by using only *ranks*, not scores.

### RRF Formula

```
score(doc) = 1 / (60 + rank_text + 1)  +  1 / (60 + rank_vector + 1)
```

The constant k=60 is the established standard from the RRF literature (Cormack et al., 2009).

**Example** with 10 text results and 5 vector hits:

```
Document A: text_rank=0, vector_rank=0  → 1/61 + 1/61 = 0.0328  (both lists: rank 1)
Document B: text_rank=1, vector_rank=3  → 1/62 + 1/64 = 0.0317  (both lists)
Document C: text_rank=2, no vector hit  → 1/63 = 0.0159           (text list only)
Document D: no text hit, vector_rank=1  → 1/62 = 0.0161           (vector list only!)
```

Document D — a purely semantic match with no exact text overlap — appears **above** Document C
in the final ranking.

### Properties of RRF Ranking

- Documents in both lists rise to the top (combined score)
- Documents only in the vector list (semantic match without text match) still appear
- Marginal borderline matches (just above the cosine threshold) rank low because their
  vector rank is high
- Score scaling differences between BM25 and cosine are irrelevant

### VectorMatchInfo in the Response

Each search result optionally contains a `vectorMatchInfo` structure:

| Field | Description |
|-------|-------------|
| `matchedViaVector` | `true` if this document was found via vector search |
| `matchedChunkIndex` | Index of the best matching chunk within the document |
| `matchedChunkText` | Approximate text of the matching chunk |
| `vectorScore` | Lucene DOT_PRODUCT score of the best chunk |

---

## 6. Activation & Configuration

### Profile Activation

```bash
# With vector search (recommended for new installations)
java -Dspring.profiles.active=deployed,vectorsearch -jar mcpluceneserver.jar

# Without vector search (default, no overhead)
java -Dspring.profiles.active=deployed -jar mcpluceneserver.jar
```

The `deployed` and `vectorsearch` profiles are independent and can be freely combined.
The comma-separated format is standard for this application.

### Model Selection

```bash
# e5-base (default, recommended)
java -Dspring.profiles.active=deployed,vectorsearch \
     -Dvector.model=e5-base \
     -jar mcpluceneserver.jar

# e5-large (higher quality, ~3x slower)
java -Dspring.profiles.active=deployed,vectorsearch \
     -Dvector.model=e5-large \
     -jar mcpluceneserver.jar
```

### Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `spring.profiles.active` includes `vectorsearch` | off | Enables vector indexing and search |
| `-Dvector.model` | `e5-base` | ONNX model (`e5-base` or `e5-large`) |

### Behavior on First Start With Profile Enabled

When `vectorsearch` is activated for the first time and an index without child documents
already exists, a full re-crawl is required. The server detects this automatically:

1. Schema version: stored version (e.g. 8) ≠ current version (9) → `schemaUpgradeRequired = true`
2. Or: no `embedding_dimension` in commit metadata → `schemaUpgradeRequired = true`

`LuceneserverApplication.init()` then automatically starts a full crawl.

---

## 7. Models

### Available Models

| Model | Embedding Dim | Latency (M4 Pro, single) | Latency (batch 8) | Size | Recommendation |
|-------|--------------|--------------------------|-------------------|------|----------------|
| `e5-base` | 768 | ~31 ms/doc | ~5–8 ms/text | ~280 MB | Default |
| `e5-large` | 1024 | ~95 ms/doc | ~15–20 ms/text | ~580 MB | Higher quality |

Both models are based on `intfloat/multilingual-e5-base` and `intfloat/multilingual-e5-large`
from HuggingFace and natively support **German and English** (as well as 100+ other languages).

### Asymmetric Retrieval

```
Indexing (chunks):  "passage: " + chunk text
Search (queries):   "query: "   + query string
```

This prefix convention is a deliberate design of the E5 models. It allows the model to
optimally compare short queries with long document passages.

### Switching Models

A switch between `e5-base` (768 dimensions) and `e5-large` (1024 dimensions) is automatically
detected at startup:

```
Stored embedding_dimension: 768
Current embedding_dimension: 1024
→ Mismatch detected → schemaUpgradeRequired = true → full re-crawl
```

No manual intervention needed — the re-crawl starts automatically on the next startup.

### Model Export and Quantization

The models are stored as quantized ONNX files (`model_quantized.onnx`) in
`src/main/resources/onnxmodels/`. Instructions for export, optimization, and quantization:
[ONNX.md](ONNX.md).

**Important when exporting**: `--task feature-extraction` is mandatory. This exports
`last_hidden_state` (all token embeddings) instead of a pooled sentence embedding — Late
Chunking requires the raw token level.

---

## 8. Technical Notes & Limitations

### Block Join Constraint

**The parent document MUST be the last document in the `addDocuments()` block.**
This is a hard requirement of Lucene's Block Join mechanism. Incorrect block construction
leads to silent failures during KNN retrieval.

```java
// Correct: children first, parent last
List<Document> block = new ArrayList<>();
block.addAll(children);   // child documents first
block.add(facetedParent); // parent always last
indexWriter.addDocuments(block);
```

### DrillSideways Compatibility

Facet filters via DrillSideways work correctly with the Block Join structure (tested with
523+ tests). Facets are computed on parent documents; child documents do not contribute
to facets.

### Mixed-Index State

When the `vectorsearch` profile is activated after indexing without it, existing parent
documents have no child documents. These documents are found only by BM25 search. A full
re-crawl is needed for complete vector coverage.

Documents newly indexed or updated after enabling the profile immediately receive their
chunk embeddings.

### Chunk Text Approximation

The `chunk_text` values in child documents are **character-proportional approximations**,
not exact token boundaries:

```java
// Split into equal character ranges (not token boundaries)
int chunkSize = content.length() / numChunks;
```

This is sufficient for display in `matchedChunkText`, but does not exactly reflect the
text processed by the model. Exact token boundaries reside in the model's tokenizer.

### Score Scales and RRF

BM25 scores (1–20) and cosine scores (0–1) are not directly comparable. RRF avoids this
problem entirely by using only ranks. The final RRF score of a document typically falls
in the range 0.01–0.04 and has no intuitive meaning beyond the ranking order.

### Memory Footprint

For planning index size:

| Model | Bytes/Chunk | 1,000 docs (avg. 5 chunks) | 10,000 docs |
|-------|-------------|----------------------------|-------------|
| e5-base (768-dim) | ~3 KB | ~15 MB | ~150 MB |
| e5-large (1024-dim) | ~4 KB | ~20 MB | ~200 MB |

Add to this the regular BM25 index (text, term vectors, facets).

### Hardware Acceleration

`ONNXService` automatically selects the best available execution provider:

| Platform | Provider | Acceleration |
|----------|----------|--------------|
| macOS / Apple Silicon | CoreML | GPU + Neural Engine |
| Windows | DirectML | DirectX GPU |
| Linux | CUDA | NVIDIA GPU |
| Fallback | CPU | all platforms |

If no accelerated provider is available, the system falls back to CPU without error.

---

Implementation details in:
- `ONNXService.java` — embedding, late chunking, batch processing
- `DocumentIndexer.java` — field definitions, `createChildDocuments()`
- `LuceneIndexService.java` — `indexDocument()`, `mergeWithVectorResults()`, RRF fusion
- [ONNX.md](ONNX.md) — model export, optimization, quantization
