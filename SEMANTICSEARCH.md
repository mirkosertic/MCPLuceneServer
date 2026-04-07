# MCP Lucene Server - Semantic Search

This document describes the semantic search architecture using ONNX-based transformer embeddings
and pure KNN vector search.

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

### The Solution: Semantic Search

The MCP Lucene Server supports pure KNN semantic search as a separate tool (`semanticSearch`):

1. **Embedding** — the query and document chunks are encoded into dense vectors using an ONNX transformer model
2. **KNN Search** — finds the nearest neighbor chunks in vector space using cosine similarity
3. **Configurable threshold** — a `similarityThreshold` parameter (default 0.70, range 0.0–1.0) filters out poor matches

Results are ordered by cosine similarity — the most semantically relevant documents appear first.
This approach is fully explainable: each result has a `cosineScore` showing exactly how similar it is to the query.

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

| Field         | Type                | Stored                 | Purpose                                      |
|---------------|---------------------|------------------------|----------------------------------------------|
| `_doc_type`   | StringField         | Yes                    | Value `"child"` — distinguishes from parent  |
| `file_path`   | StringField         | Yes                    | Links to the parent document                 |
| `chunk_index` | StoredField (int)   | Yes                    | Position of the chunk within the document    |
| `chunk_text`  | StoredField         | Yes                    | Approximate text representation of the chunk |
| `embedding`   | KnnFloatVectorField | No (vector index only) | L2-normalized embedding vector               |

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

### Fallback When Semantic Search Is Not Configured

When `VECTOR_MODEL` is not set or semantic tools (`semanticSearch`, `profileSemanticSearch`) are not
included in the active tool set, `onnxService == null`. In this case only
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

---

## 5. Scoring & Ranking

### Cosine Similarity

Results are ordered by cosine similarity between the query embedding and the best matching chunk embedding.
Since the vectors are L2-normalized, Lucene's DOT_PRODUCT score maps to cosine similarity as:

    cosine = 2 × score − 1

### Similarity Threshold (Configurable)

The minimum cosine similarity is a **tool parameter** (`similarityThreshold`, default **0.70**).
The LLM or tool user can adjust it per query to tune recall/precision:

- **Lower values** (e.g. 0.50): more results, broader semantic match
- **Higher values** (e.g. 0.85): fewer results, only closely matching documents
- **Default (0.70)**: balanced starting point for most use cases

The Lucene DOT_PRODUCT score threshold derived from the parameter:

    Lucene score threshold = (1 + similarityThreshold) / 2

Example with default: `(1 + 0.70) / 2 = 0.85` — chunks scoring below this are discarded before parent resolution.

### Result Ordering

Results are ordered by the best chunk cosine similarity per parent document (descending).
There is no BM25 component — purely semantic relevance determines the ranking.

### SemanticMatchInfo in the Response

Each result contains semantic match information:

| Field               | Description                                          |
|---------------------|------------------------------------------------------|
| `matchedChunkIndex` | Index of the best matching chunk within the document |
| `matchedChunkText`  | Approximate text of the matching chunk               |
| `cosineScore`       | Cosine similarity of the best matching chunk (0–1)   |

---

## 6. Activation & Configuration

### Activation

Semantic search tools are activated by including them in the tool list and setting `VECTOR_MODEL`:

```bash
# Include semantic tools and set the ONNX model
LUCENE_TOOLS_INCLUDE=search,semantic VECTOR_MODEL=e5-base java -jar mcpluceneserver.jar

# Or include all tools (semantic tools automatically activate when VECTOR_MODEL is set)
VECTOR_MODEL=e5-base java -jar mcpluceneserver.jar

# Docker container
docker run -e LUCENE_TOOLS_INCLUDE=search,semantic -e VECTOR_MODEL=e5-base \
           -e SPRING_PROFILES_ACTIVE=deployed mcpluceneserver
```

If semantic tools are requested but `VECTOR_MODEL` is not set, a warning is logged and semantic tools are silently excluded from the active tool set.

### Model Selection

```bash
# e5-base (default, recommended)
VECTOR_MODEL=e5-base java -jar mcpluceneserver.jar

# e5-large (higher quality, ~3x slower)
VECTOR_MODEL=e5-large java -jar mcpluceneserver.jar
```

### Configuration Parameters

| Parameter             | Default   | Description                                    |
|-----------------------|-----------|------------------------------------------------|
| `VECTOR_MODEL`        | (not set) | ONNX model (`e5-base` or `e5-large`); required for semantic tools |
| `LUCENE_TOOLS_INCLUDE`| `*`       | Include `semantic` or `semanticSearch` to enable |
| `similarityThreshold` | `0.70`    | Per-query threshold (tool parameter, not config) |

### Behavior on First Start With VECTOR_MODEL Enabled

When `VECTOR_MODEL` is set for the first time and an index without child documents already exists, a full re-crawl is required. The server detects this automatically:

1. Schema version: stored version ≠ current version → `schemaUpgradeRequired = true`
2. Or: no `embedding_dimension` in commit metadata → `schemaUpgradeRequired = true`

`LuceneserverApplication.init()` then automatically starts a full crawl.

---

## 7. Models

### Available Models

| Model      | Embedding Dim | Latency (M4 Pro, single) | Latency (batch 8) | Size    | Recommendation |
|------------|---------------|--------------------------|-------------------|---------|----------------|
| `e5-base`  | 768           | ~31 ms/doc               | ~5–8 ms/text      | ~280 MB | Default        |
| `e5-large` | 1024          | ~95 ms/doc               | ~15–20 ms/text    | ~580 MB | Higher quality |

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

When `VECTOR_MODEL` is enabled after indexing without it, existing parent documents have no
child documents. Documents without child documents will not appear in `semanticSearch` results —
only lexical search (`simpleSearch`, `extendedSearch`) will find them. A full re-crawl is
needed for complete semantic coverage.

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

### Memory Footprint

For planning index size:

| Model               | Bytes/Chunk | 1,000 docs (avg. 5 chunks) | 10,000 docs |
|---------------------|-------------|----------------------------|-------------|
| e5-base (768-dim)   | ~3 KB       | ~15 MB                     | ~150 MB     |
| e5-large (1024-dim) | ~4 KB       | ~20 MB                     | ~200 MB     |

Add to this the regular BM25 index (text, term vectors, facets).

### Hardware Acceleration

`ONNXService` automatically selects the best available execution provider:

| Platform              | Provider | Acceleration        |
|-----------------------|----------|---------------------|
| macOS / Apple Silicon | CoreML   | GPU + Neural Engine |
| Windows               | DirectML | DirectX GPU         |
| Linux                 | CUDA     | NVIDIA GPU          |
| Fallback              | CPU      | all platforms       |

If no accelerated provider is available, the system falls back to CPU without error.

---

Implementation details in:
- `ONNXService.java` — embedding, late chunking, batch processing
- `DocumentIndexer.java` — field definitions, `createChildDocuments()`
- `LuceneIndexService.java` — `indexDocument()`, `semanticSearch()`
- [ONNX.md](ONNX.md) — model export, optimization, quantization
