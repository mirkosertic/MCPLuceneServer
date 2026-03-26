# MCP Lucene Server - Hybrid Vector Search

This document describes the hybrid search architecture combining BM25 text search with dense
vector search (semantic search) using ONNX-based transformer embeddings.

For general feature overview and user-facing documentation, see [README.md](README.md).
For analyzer pipeline details, see [PIPELINE.md](PIPELINE.md).
For ONNX model export and quantization, see [ONNX.md](ONNX.md).

---

## 1. Overview

### Die Grenzen reiner BM25-Suche

BM25 ist ein exzellenter lexikalischer Retrieval-Algorithmus: er findet Dokumente, die die
gesuchten Wörter enthalten, und bewertet sie nach Häufigkeit und Dokumentlänge. Aber er hat
eine fundamentale Lücke — die **lexikalische Lücke**:

- Ein Dokument, das "Vereinbarung" enthält, wird bei der Suche nach "Vertrag" nicht gefunden
- Ein englisches Dokument mit "agreement" bleibt bei einer deutschen Suche nach "Vertrag" unsichtbar
- Synonyme, Paraphrasen und cross-linguale Ausdrücke werden grundsätzlich nicht gematch

Der Satz "Der Lieferant verpflichtet sich zur pünktlichen Lieferung" ist semantisch verwandt
mit "Vertrag" — enthält das Wort aber nicht.

### Die semantische Lücke

```
Suchanfrage: "Vertrag"

BM25 findet:  "Vertrag", "Vertrages", "Vertragsklausel" (via Lemmatisierung)
BM25 MISS:    "Vereinbarung", "Agreement", "Abkommen", "MOU", "Rahmenvertrag"
              (wenn der Begriff "Vertrag" nicht im Dokument vorkommt)

Embedding-Suche findet alle — weil das Modell die semantische Nähe der Begriffe kennt.
```

### Die Lösung: Hybrid Search

Der MCP Lucene Server kombiniert beide Ansätze:

1. **BM25-Suche** (lexikalisch): Exakte Treffer, Wildcards, Lemmatisierung, Transliteration
2. **Dense Vector Search** (semantisch): Cosinus-Ähnlichkeit zwischen Query- und Dokument-Embeddings
3. **RRF-Fusion** (Reciprocal Rank Fusion): Beide Ranglisten werden fusioniert

Das Ergebnis: Dokumente erscheinen in den Suchergebnissen, auch wenn die gesuchten Begriffe
nicht wörtlich im Dokument vorkommen.

---

## 2. Konzept: Late Chunking

### Das Problem mit naivem Chunking

Lange Dokumente passen nicht in das Kontextfenster eines Transformer-Modells (512 Tokens).
Der naive Ansatz: Dokument in Chunks aufteilen, jeden Chunk unabhängig einbetten.

**Problem**: Jeder Chunk verliert seinen Dokumentkontext. Ein Chunk "Der Kläger..." in Seite 5
eines Vertrags "weiß" nicht mehr, dass es sich um einen Kaufvertrag zwischen Firma A und Firma B
handelt. Die Embedding-Qualität leidet.

### Late Chunking

Late Chunking löst dieses Problem durch eine andere Reihenfolge der Operationen:

```
Naives Chunking:
  Dokument → Chunks → [Embed Chunk 1] [Embed Chunk 2] [Embed Chunk 3]
  (jeder Chunk ohne Dokumentkontext)

Late Chunking:
  Dokument → Tokenize → Forward Pass (gesamtes Dokument / Macro-Chunk) → Token-Embeddings
            → Chunk-Spans extrahieren → Mean Pooling pro Span → Chunk-Embeddings
  (jedes Chunk-Embedding ist aus kontextualisierten Token-Repräsentationen gepoolt)
```

Der entscheidende Unterschied: Die Token-Embeddings werden durch den gesamten umgebenden
Kontext beeinflusst (Self-Attention über alle Tokens). Erst danach werden sie zu
Chunk-Embeddings zusammengefasst.

### Implementierung in ONNXService

`ONNXService.embedWithLateChunking()` implementiert dieses Verfahren und gibt `List<float[]>`
zurück — ein Embedding-Vektor pro Chunk:

```
Konstanten:
  MODEL_MAX_TOKENS       = 512   — maximale Eingabelänge des Modells
  MACRO_CHUNK_TOKENS     = 480   — Größe eines Macro-Chunks (mit Puffer für [CLS]/[SEP])
  MACRO_CHUNK_OVERLAP    = 32    — Überlapp zwischen Macro-Chunks (Kontextkontinuität)
  TARGET_CHUNK_TOKENS    = 128   — Zielgröße der finalen Chunk-Spans
```

**Kurze Dokumente** (≤ 512 Tokens): Ein einziger Forward Pass, Chunk-Spans werden direkt
aus den Token-Embeddings extrahiert. Mehrere kurze Dokumente werden gepaddet und im Batch
verarbeitet.

**Lange Dokumente** (> 512 Tokens): Long Late Chunking — das Dokument wird in Macro-Chunks
mit Überlapp aufgeteilt. Pro Macro-Chunk ein Forward Pass; die überlappenden Chunks am
Anfang jedes Macro-Chunks werden verworfen.

---

## 3. Indexierungspipeline

### Block-Join-Struktur im Lucene-Index

Für jedes Dokument werden bei aktiviertem Vector-Search-Profil mehrere Lucene-Dokumente
als **Block** geschrieben — Kinder zuerst, Eltern zuletzt:

```
IndexWriter.addDocuments([
  Child Doc 0: { _doc_type="child", file_path, chunk_index=0, chunk_text, embedding[768] }
  Child Doc 1: { _doc_type="child", file_path, chunk_index=1, chunk_text, embedding[768] }
  Child Doc 2: { _doc_type="child", file_path, chunk_index=2, chunk_text, embedding[768] }
  ...
  Parent Doc:  { _doc_type="parent", file_path, content, title, language, ...  }  <-- MUSS LETZTER SEIN
])
```

Diese Struktur ist eine Lucene-Anforderung für Block-Join-Queries: der Parent muss immer
das letzte Dokument im Block sein.

### Felder der Child-Dokumente

| Feld | Typ | Gespeichert | Zweck |
|------|-----|-------------|-------|
| `_doc_type` | StringField | Ja | Wert `"child"` — Unterscheidung vom Parent |
| `file_path` | StringField | Ja | Verbindung zum Parent-Dokument |
| `chunk_index` | StoredField (int) | Ja | Position des Chunks im Dokument |
| `chunk_text` | StoredField | Ja | Ungefähre Textdarstellung des Chunks |
| `embedding` | KnnFloatVectorField | Nein (nur Vektorindex) | L2-normalisierter Embedding-Vektor |

Der `embedding`-Vektor verwendet `VectorSimilarityFunction.DOT_PRODUCT`. Da die Embeddings
L2-normalisiert sind, entspricht das Dot Product der Cosinus-Ähnlichkeit.

### Atomares Löschen

`file_path` ist auf **allen** Dokumenten vorhanden — Parent und alle Child-Dokumente.
Das ermöglicht atomares Löschen des gesamten Blocks:

```java
indexWriter.deleteDocuments(new Term("file_path", filePath.toString()));
indexWriter.addDocuments(block);  // Neuer Block mit aktualisierten Embeddings
```

### Schema-Version

SCHEMA_VERSION = **9** (eingeführt mit Vector Search). Wird in den Lucene-Commit-Metadaten
gespeichert. Bei Versionsmismatch beim Start: automatischer vollständiger Re-Crawl.

Zusätzlich wird die Embedding-Dimension im Commit-Metadata gespeichert (`embedding_dimension`).
Ein Wechsel zwischen Modellen (768 → 1024 oder umgekehrt) wird automatisch als Mismatch
erkannt und löst einen vollständigen Re-Crawl aus.

### Fallback ohne Vector-Search-Profil

Wenn das `vectorsearch`-Profil nicht aktiv ist, wird `onnxService == null`. In diesem Fall
wird ausschließlich `addDocument(parentDoc)` aufgerufen — kein Embedding, keine Child-Dokumente,
null zusätzlicher Overhead.

---

## 4. Suchanfrage-Pipeline

### Schritt 1: Query-Embedding

Für die semantische Suche wird die Suchanfrage in einen Vektor umgewandelt:

```java
float[] queryVector = onnxService.embed(queryString, "query: ");
```

Das Präfix `"query: "` ist entscheidend. Die E5-Modelle verwenden **asymmetrisches Retrieval**:
- Dokumente beim Indexieren: `"passage: " + text`
- Suchanfragen: `"query: " + queryString`

Die unterschiedlichen Präfixe optimieren die Ähnlichkeitsmessung zwischen kurzen Anfragen
und langen Dokumentpassagen. Ohne Präfix sinkt die Retrieval-Qualität deutlich.

### Schritt 2: KNN-Vektor-Query

```java
KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery("embedding", queryVector, k=50);
TopDocs vectorTopDocs = searcher.search(knnQuery, 50);
```

Die KNN-Query sucht die 50 nächsten Nachbarn im Vektorraum — auf Child-Dokumenten.

### Schritt 3: Cosinus-Schwellenwert

Nicht alle KNN-Treffer sind relevant. Ein Schwellenwert filtert schlechte Übereinstimmungen:

```
Cosine Cut-Off:  0.70
Lucene-Score:    (1 + 0.70) / 2 = 0.85

(DOT_PRODUCT bei normierten Vektoren: Lucene gibt (1 + cosine) / 2 als Score zurück)
```

Child-Dokumente mit `score < 0.85` werden verworfen.

### Schritt 4: Child → Parent Mapping

Die KNN-Query trifft Child-Dokumente (Chunks). Für die Suchergebnisse werden die
entsprechenden Parent-Dokumente benötigt:

```java
// Für jedes Child-Dokument: Parent über file_path finden
Query parentQuery = new BooleanQuery.Builder()
    .add(new TermQuery(new Term("file_path", filePath)), MUST)
    .add(new TermQuery(new Term("_doc_type", "parent")), MUST)
    .build();
TopDocs parentHits = searcher.search(parentQuery, 1);
```

Pro Parent wird nur der beste Child-Treffer (höchster Vektor-Score) behalten.

### Schritt 5: RRF-Fusion

Die BM25-Rangliste und die Vektor-Rangliste werden per RRF zusammengeführt (siehe Abschnitt 5).

### Fallback bei Fehler

Wenn die Vektorsuche eine Exception wirft (Modell-Timeout, OOM etc.), fällt das System
automatisch auf reine Textsuche zurück:

```java
} catch (final Exception e) {
    logger.warn("Vector search failed, falling back to text-only: {}", e.getMessage());
    return new VectorMergeResult(textDocs, Map.of());
}
```

---

## 5. Scoring & Ranking (RRF)

### Warum kein einfacher Boost-Faktor?

BM25-Scores und Cosinus-Scores liegen in völlig verschiedenen Wertebereichen:
- BM25: typisch 1–20 (abhängig von IDF, Termfrequenz, Dokumentlänge)
- Cosinus: 0–1

Ein additiver oder multiplikativer Boost würde BM25 dominieren lassen und die semantischen
Treffer unsichtbar machen. **RRF löst dieses Problem** durch Verwendung nur der *Ränge*,
nicht der Scores.

### RRF-Formel

```
score(doc) = 1 / (60 + rank_text + 1)  +  1 / (60 + rank_vector + 1)
```

Der Konstante k=60 ist der etablierte Standard aus der RRF-Literatur (Cormack et al., 2009).

**Beispiel** mit 10 Textergebnissen und 5 Vektortreffer:

```
Dokument A: text_rank=0, vector_rank=0  → 1/61 + 1/61 = 0.0328  (beide Listen: Platz 1)
Dokument B: text_rank=1, vector_rank=3  → 1/62 + 1/64 = 0.0317  (beide Listen)
Dokument C: text_rank=2, kein Vektortreffer → 1/63 = 0.0159      (nur Textliste)
Dokument D: kein Texttreffer, vector_rank=1 → 1/62 = 0.0161      (nur Vektorliste!)
```

Dokument D — ein rein semantischer Treffer ohne exakte Textübereinstimmung — erscheint
**vor** Dokument C im finalen Ranking.

### Eigenschaften des RRF-Rankings

- Dokumente in beiden Listen steigen nach oben (kombinierter Score)
- Dokumente nur in der Vektorliste (semantischer Match ohne Textmatch) erscheinen trotzdem
- Schlechte Grenztreffer (gerade noch über dem Cosinus-Schwellenwert) ranken niedrig,
  weil ihr Vektor-Rank hoch ist
- Score-Skalierungsunterschiede zwischen BM25 und Cosinus spielen keine Rolle

### VectorMatchInfo in der Response

Jedes Suchergebnis enthält optional eine `vectorMatchInfo`-Struktur:

| Feld | Beschreibung |
|------|-------------|
| `matchedViaVector` | `true` wenn dieses Dokument via Vektorsuche gefunden wurde |
| `matchedChunkIndex` | Index des besten Match-Chunks im Dokument |
| `matchedChunkText` | Ungefährer Text des Match-Chunks |
| `vectorScore` | Lucene DOT_PRODUCT Score des besten Chunks |

---

## 6. Aktivierung & Konfiguration

### Profil-Aktivierung

```bash
# Mit Vector Search (empfohlen für neue Installationen)
java -Dspring.profiles.active=deployed,vectorsearch -jar mcpluceneserver.jar

# Ohne Vector Search (Standard, kein Overhead)
java -Dspring.profiles.active=deployed -jar mcpluceneserver.jar
```

Die Profile `deployed` und `vectorsearch` sind unabhängig voneinander und können
frei kombiniert werden. Das Komma-getrennte Format ist Spring-Standard.

### Modell-Auswahl

```bash
# e5-base (Standard, empfohlen)
java -Dspring.profiles.active=deployed,vectorsearch \
     -Dvector.model=e5-base \
     -jar mcpluceneserver.jar

# e5-large (höhere Qualität, ~3x langsamer)
java -Dspring.profiles.active=deployed,vectorsearch \
     -Dvector.model=e5-large \
     -jar mcpluceneserver.jar
```

### Konfigurationsparameter

| Parameter | Standard | Beschreibung |
|-----------|----------|-------------|
| `spring.profiles.active` enthält `vectorsearch` | aus | Aktiviert Vektor-Indexierung und -Suche |
| `-Dvector.model` | `e5-base` | ONNX-Modell (`e5-base` oder `e5-large`) |

### Verhalten bei Erststart mit aktiviertem Profil

Wenn `vectorsearch` zum ersten Mal aktiviert wird und bereits ein Index ohne Child-Dokumente
existiert, ist ein vollständiger Re-Crawl erforderlich. Der Server erkennt automatisch:

1. Schema-Version: gespeicherte Version (z.B. 8) ≠ aktuelle Version (9) → `schemaUpgradeRequired = true`
2. Oder: kein `embedding_dimension` in Commit-Metadaten → `schemaUpgradeRequired = true`

`LuceneserverApplication.init()` startet daraufhin automatisch einen vollständigen Crawl.

---

## 7. Modelle

### Verfügbare Modelle

| Modell | Embedding-Dim | Latenz (M4 Pro, single) | Latenz (batch 8) | Größe | Empfehlung |
|--------|--------------|------------------------|------------------|-------|------------|
| `e5-base` | 768 | ~31 ms/Dok | ~5–8 ms/Text | ~280 MB | Standard |
| `e5-large` | 1024 | ~95 ms/Dok | ~15–20 ms/Text | ~580 MB | Höhere Qualität |

Beide Modelle basieren auf `intfloat/multilingual-e5-base` bzw. `intfloat/multilingual-e5-large`
von HuggingFace und unterstützen **Deutsch und Englisch nativ** (sowie weitere 100+ Sprachen).

### Asymmetrisches Retrieval

```
Indexierung (Chunks):  "passage: " + Chunk-Text
Suche (Anfragen):      "query: "   + Suchanfrage
```

Diese Präfix-Konvention ist ein bewusstes Design der E5-Modelle. Sie ermöglicht es dem
Modell, kurze Anfragen optimal mit langen Dokumentpassagen zu vergleichen.

### Modellwechsel

Ein Wechsel zwischen `e5-base` (768 Dimensionen) und `e5-large` (1024 Dimensionen) wird
beim Start automatisch erkannt:

```
Gespeicherte embedding_dimension: 768
Aktuelle embedding_dimension:     1024
→ Mismatch erkannt → schemaUpgradeRequired = true → vollständiger Re-Crawl
```

Kein manueller Eingriff nötig — der Re-Crawl startet automatisch beim nächsten Start.

### Modell-Export und Quantisierung

Die Modelle liegen als quantisierte ONNX-Dateien (`model_quantized.onnx`) in
`src/main/resources/onnxmodels/`. Anleitung zum Export, zur Optimierung und zur
Quantisierung: [ONNX.md](ONNX.md).

**Wichtig beim Export**: `--task feature-extraction` ist zwingend erforderlich.
Damit wird `last_hidden_state` (alle Token-Embeddings) exportiert statt eines gepoolten
Sentence-Embeddings — Late Chunking benötigt die rohen Token-Ebene.

---

## 8. Technische Hinweise & Einschränkungen

### Block-Join-Constraint

**Der Parent-Dokument MUSS das letzte Dokument im `addDocuments()`-Block sein.**
Das ist eine harte Anforderung von Lucene's Block-Join-Mechanismus. Ein falscher Aufbau
führt zu stillen Fehlern beim KNN-Retrieval.

```java
// Korrekt: Kinder zuerst, Parent zuletzt
List<Document> block = new ArrayList<>();
block.addAll(children);   // Child-Dokumente zuerst
block.add(facetedParent); // Parent immer als letztes
indexWriter.addDocuments(block);
```

### DrillSideways-Kompatibilität

Facet-Filter über DrillSideways funktionieren korrekt mit der Block-Join-Struktur (getestet
mit 523+ Tests). Die Facetten werden auf Parent-Dokumenten berechnet; Child-Dokumente tragen
nicht zu Facetten bei.

### Mixed-Index-Zustand

Wenn das `vectorsearch`-Profil nach einer Indexierung ohne es aktiviert wird, haben die
bestehenden Eltern-Dokumente keine Child-Dokumente. Diese Dokumente werden nur von der
BM25-Suche gefunden. Für vollständige Vektorabdeckung ist ein Re-Crawl nötig.

Dokumente, die nach Aktivierung des Profils neu indexiert oder aktualisiert werden, erhalten
sofort ihre Chunk-Embeddings.

### Chunk-Text-Näherung

Die `chunk_text`-Werte in Child-Dokumenten sind **zeichenproportionale Näherungen**,
keine exakten Token-Grenzen:

```java
// Aufteilen in gleich große Zeichenbereiche (nicht Token-Grenzen)
int chunkSize = content.length() / numChunks;
```

Das ist für die Anzeige in `matchedChunkText` ausreichend, zeigt aber nicht exakt den
vom Modell verarbeiteten Text. Die genauen Token-Grenzen liegen im Tokenizer des Modells.

### Score-Skalen und RRF

BM25-Scores (1–20) und Cosinus-Scores (0–1) sind nicht direkt vergleichbar. RRF vermeidet
dieses Problem vollständig durch ausschließliche Verwendung von Rängen. Der finale RRF-Score
eines Dokuments liegt typischerweise im Bereich 0.01–0.04 und hat keine intuitive Bedeutung
außer der Ranking-Reihenfolge.

### Speicherverbrauch

Für eine Planung der Indexgröße:

| Modell | Bytes/Chunk | 1.000 Docs (avg. 5 Chunks) | 10.000 Docs |
|--------|-------------|--------------------------|-------------|
| e5-base (768-dim) | ~3 KB | ~15 MB | ~150 MB |
| e5-large (1024-dim) | ~4 KB | ~20 MB | ~200 MB |

Hinzu kommt der reguläre BM25-Index (Texte, Termvektoren, Facetten).

### Hardware-Beschleunigung

`ONNXService` wählt automatisch den besten verfügbaren Execution Provider:

| Plattform | Provider | Beschleunigung |
|-----------|----------|----------------|
| macOS / Apple Silicon | CoreML | GPU + Neural Engine |
| Windows | DirectML | DirectX GPU |
| Linux | CUDA | NVIDIA GPU |
| Fallback | CPU | alle Plattformen |

Bei nicht verfügbarem Provider fällt das System ohne Fehler auf CPU zurück.

---

Implementierungsdetails in:
- `ONNXService.java` — Embedding, Late Chunking, Batch-Verarbeitung
- `DocumentIndexer.java` — Felddefinitionen, `createChildDocuments()`
- `LuceneIndexService.java` — `indexDocument()`, `mergeWithVectorResults()`, RRF-Fusion
- [ONNX.md](ONNX.md) — Modell-Export, Optimierung, Quantisierung
