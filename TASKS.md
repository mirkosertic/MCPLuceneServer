# TASKS: Hybrid Search – Late Chunking + ONNX Vector Search

Entstanden aus einer Planungssession. Alle Design-Entscheidungen sind getroffen und dokumentiert.
Zur Implementierung den `implement-review-loop`-Agenten verwenden (gemäß CLAUDE.md).

---

## Hintergrund & Ziel

Die bestehende BM25-Volltextsuche soll mit einer **semantischen Vektorsuche** kombiniert werden.
Dokumente werden via **Late Chunking** in Chunks aufgeteilt, über den ONNX-Embedder (`multilingual-e5`)
in Vektoren umgewandelt und als **Child-Dokumente** (Lucene Block Join) zu den bestehenden Parent-Dokumenten
gespeichert. Bei der Suche läuft eine **hybride Anfrage**: Volltext auf Parents + kNN auf Children,
gefolgt von **Reciprocal Rank Fusion (RRF)**. Das Feature ist ein klares **Opt-in** via Profil.

**Was bereits existiert:**
- `ONNXService.java` – vollständig implementiert, inkl. Late Chunking, Batch-Processing, CoreML/CUDA-Support
- ONNX-Modelle in `src/main/resources/onnxmodels/` (e5-base 768 Dims, e5-large 1024 Dims)
- `onnxruntime` und `tokenizers` bereits in `pom.xml`

---

## Entschiedene Design-Entscheidungen

| Thema              | Entscheidung                                                             | Begründung                                                               |
|--------------------|--------------------------------------------------------------------------|--------------------------------------------------------------------------|
| Chunking           | **Late Chunking** (kontextualisierte Chunks als Child-Docs)              | Beste Qualität bei langen Docs, ONNXService bereits fertig               |
| Index-Struktur     | **Lucene Block Join** (Parent last)                                      | Atomare Updates, ein Index, native Lucene-Semantik                       |
| Score-Fusion       | **RRF** (`1/(60+rank)`)                                                  | Kein Score-Tuning nötig, robust, industry-standard                       |
| Cosine Cut-Off     | **0.70** default (konfigurierbar)                                        | Großzügig weil RRF Grenzfälle dämpft; Lucene-Score = `(1+0.70)/2 = 0.85` |
| Aktivierung        | **Profil `vectorsearch`** in `-Dspring.profiles.active=...,vectorsearch` | Analog zu bestehendem `deployed`-Profil; komma-separierte Liste          |
| Model-Default      | **e5-base** (768 Dims, ~31ms/Doc)                                        | Effizienter; e5-large (1024 Dims, ~95ms) via `-Dvector.model=e5-large`   |
| Chunk-Text         | **Gespeichert** (StoredField)                                            | Für Treffer-Reporting in Response                                        |
| Dimension-Mismatch | **Automatisch erkannt** → Full-Re-Crawl                                  | Analog zu SCHEMA_VERSION-Mechanismus                                     |

---

## Lucene Block Join – Kritische Hinweise

```
Block (IndexWriter.addDocuments()):
  [Child Doc 0]  { _doc_type="child", file_path=..., chunk_index=0, chunk_text=..., embedding=float[768] }
  [Child Doc 1]  { _doc_type="child", file_path=..., chunk_index=1, chunk_text=..., embedding=float[768] }
  ...
  [Parent Doc]   { _doc_type="parent", file_path=..., content=..., title=..., ... }  ← MUSS LETZTER SEIN
```

- `file_path` auf ALLEN Docs → atomares Löschen: `deleteDocuments(new TermQuery(new Term("file_path", path)))`
- Parent-Erkennung via `QueryBitSetProducer(new TermQuery(new Term("_doc_type", "parent")))`
- Lucene Score-Transformation: `lucene_score = (1 + cosine_sim) / 2`

## Hybride Suche (RRF-Ablauf)

```java
// 1. Text-Suche (unverändert)
TopDocs textResults = searcher.search(buildStemmedQuery(q), pageSize * 3);

// 2. Vector-Suche auf Child-Docs
float[] queryVec = onnxService.embed(q, "query: ");
TopDocs rawVector = searcher.search(new KnnFloatVectorQuery("embedding", queryVec, vectorK), vectorK);

// 3. Cosine Cut-Off (0.70 → lucene 0.85)
float luceneThreshold = (1f + cosineCutoff) / 2f;
ScoreDoc[] filtered = Arrays.stream(rawVector.scoreDocs)
    .filter(sd -> sd.score >= luceneThreshold).toArray(ScoreDoc[]::new);
// Child-IDs → Parent-IDs mappen

// 4. RRF Merge
Map<Integer, Float> rrf = new HashMap<>();
for (int r = 0; r < textResults.scoreDocs.length; r++)
    rrf.merge(textResults.scoreDocs[r].doc, 1f / (61 + r), Float::sum);
for (int r = 0; r < filtered.length; r++)
    rrf.merge(parentDocId(filtered[r].doc), 1f / (61 + r), Float::sum);

// 5. Nach RRF-Score sortieren → Top-N
```

---

## Implementierungs-Checkliste

### Schritt 1: pom.xml
- [x] `lucene-join` Dependency hinzufügen (Lucene 10.4.0, für `ToParentBlockJoinQuery`, `QueryBitSetProducer`)

### Schritt 2: ApplicationConfig.java
- [x] Profil-Property `-Dspring.profiles.active` als **komma-separierte Liste** parsen
  - `"deployed".equalsIgnoreCase(profile)` → `profiles.contains("deployed")`
  - Neues Flag: `vectorSearchEnabled = profiles.contains("vectorsearch")`
  - Getter `isVectorSearchEnabled()` hinzufügen
- [x] Bestehende `deployedMode`-Logik bleibt unverändert

### Schritt 3: LuceneserverApplication.java
- [x] Logging-Configurator-Aufruf auf Listen-Parsing umstellen (analog ApplicationConfig)
- [x] `ONNXService` nur instantiieren wenn `config.isVectorSearchEnabled()`
- [x] Model-Auswahl: `System.getProperty("vector.model", "e5-base")` → `/onnxmodels/{model}/`
- [ ] `ONNXService`-Konstruktor empfängt Modellpfad-Prefix als Parameter (TODO in Schritt 4)
- [x] Startup-Log: gewähltes Modell + detektierte Dimension wenn `vectorsearch` aktiv

### Schritt 4: ONNXService.java
- [x] Konstruktor parametrisieren: `ONNXService(String modelName)` statt hardcoded e5-large
  - Modellpfad: `/onnxmodels/{modelName}/model_quantized.onnx`
  - Tokenizer: `/onnxmodels/{modelName}/tokenizer.json`
- [x] Methode `getHiddenSize()` sicherstellen (für Dimension-Mismatch-Check)

### Schritt 5: DocumentIndexer.java (SCHEMA_VERSION 8 → 9)
- [x] `SCHEMA_VERSION` von 8 auf **9** bumpen
- [x] `_doc_type` StringField auf Parent-Docs: `"parent"`
- [x] Neue Methode `createChildDocuments(String filePath, List<float[]> embeddings, List<String> chunkTexts)`:
  - Jedes Child-Doc enthält:
    - `_doc_type` = `"child"` (StringField, stored)
    - `file_path` (StringField, stored) – für atomares Löschen
    - `chunk_index` (IntField/StoredField)
    - `chunk_text` (StoredField)
    - `embedding` (`KnnFloatVectorField`, `VectorSimilarityFunction.DOT_PRODUCT`)

### Schritt 6: LuceneIndexService.java
- [ ] `ONNXService`-Abhängigkeit als Constructor-Parameter (nullable wenn vectorsearch inaktiv)
- [ ] `QueryBitSetProducer parentFilter` als Feld initialisieren
- [ ] **Dimension-Mismatch-Check** in `init()`:
  - Commit-UserData-Key `embedding_dimension` lesen
  - Mit `onnxService.getHiddenSize()` vergleichen
  - Bei Mismatch: `schemaUpgradeRequired = true`
  - Bei erstem Indexieren: Dimension in Commit-UserData schreiben
- [ ] `indexDocument()` erweitern:
  - Wenn vectorsearch aktiv: `onnxService.embedWithLateChunking(content, "passage: ", batchSize)` aufrufen
  - `createChildDocuments()` aufrufen → Block zusammenstellen [children..., parent]
  - `IndexWriter.addDocuments(block)` statt `addDocument(parent)`
  - Wenn vectorsearch inaktiv: unverändertes `addDocument(parent)` (Fallback)
- [ ] `search()` erweitern – optionaler Hybrid-Pfad:
  - Text-Suche wie bisher (`buildStemmedQuery`)
  - Vector-Suche: `onnxService.embed(query, "query: ")` → `KnnFloatVectorQuery` → Cosine Cut-Off Filter → Child→Parent-Mapping
  - RRF Merge (siehe Code-Snippet oben)
  - Nach Parent-Hits: optionaler Post-Search Chunk-Lookup für `vectorMatchInfo`
  - Wenn vectorsearch inaktiv: bisheriger Pfad unverändert
- [ ] DrillSideways-Pfad testen mit Block-Join (bekannte Einschränkung)

### Schritt 7: SearchDocument.java / SearchResponse DTOs
- [ ] Neues `VectorMatchInfo`-Record/Klasse:
  ```java
  record VectorMatchInfo(boolean matchedViaVector, int matchedChunkIndex,
                         String matchedChunkText, float vectorScore) {}
  ```
- [ ] `vectorMatchInfo` (nullable) zu `SearchDocument` hinzufügen

### Schritt 8: Tests schreiben

#### VectorIndexingIntegrationTest.java (`@TempDir`, echtes Lucene-Index, gemockter ONNXService)
- [ ] Block-Indexierung: Parent + Children korrekt als Block gespeichert
- [ ] `_doc_type`-Feld auf Parent (`"parent"`) und Children (`"child"`) vorhanden
- [ ] `file_path` auf Parent UND Children gesetzt
- [ ] Löschen via `file_path` entfernt Parent + alle Children atomar
- [ ] Re-Indexierung (delete + addDocuments) funktioniert korrekt
- [ ] Dimension-Mismatch: gespeicherte Dim ≠ Modell-Dim → `schemaUpgradeRequired = true`

#### HybridSearchIntegrationTest.java (`@TempDir`, echter Index, gemockter ONNXService)
- [ ] Reine Text-Treffer landen im Ergebnis
- [ ] Reine Vector-Treffer (über Cut-Off) landen im Ergebnis
- [ ] Docs in beiden Rankings → höherer RRF-Score als Docs nur in einem Ranking
- [ ] Docs mit Vector-Score unter Cut-Off erscheinen nicht via Vector im Ergebnis
- [ ] `vectorMatchInfo` korrekt befüllt (matchedViaVector, chunkIndex, chunkText, vectorScore)
- [ ] Leere Vector-Ergebnisse (alle unter Cut-Off) → Fallback auf reine Textsuche
- [ ] `vectorSearchEnabled=false` → kein ONNX-Aufruf, bisherige Textsuche unverändert

#### RRFScoringTest.java (Unit-Test, kein Index)
- [ ] RRF-Formel: `score = 1/(60 + rank + 1)`
- [ ] Dokument in beiden Listen → Score = Summe beider Beiträge
- [ ] Dokument nur in einer Liste → nur ein Beitrag
- [ ] Rangreihenfolge: Docs in beiden Listen > Docs nur in einer

#### CosineThresholdTest.java (Unit-Test)
- [ ] Lucene-Score-Konvertierung: `(1 + 0.70) / 2 = 0.85`
- [ ] Docs über Threshold → passieren den Filter
- [ ] Docs unter Threshold → werden verworfen
- [ ] Grenzwert exakt auf Threshold: `>=` semantik

#### Regression
- [ ] `mvn test` – alle ~449 bestehenden Tests grün
- [ ] `SearchPrecisionRecallRegressionTest` – Baseline P/R unverändert bei `vectorsearch`-Profil inaktiv

### Schritt 9: VECTORSEARCH.md (neue Datei im Projekt-Root)
Struktur analog zu PIPELINE.md:
- [ ] **1. Overview** – Warum Hybrid Search? Grenzen reiner Volltextsuche, semantische Lücke
- [ ] **2. Konzept: Late Chunking** – Was ist Late Chunking, Unterschied zu naivem Chunking, Kontextualisierung durch Modell
- [ ] **3. Indexierungspipeline** – Parent-Doc (bestehend) + Child-Docs (Chunks + Embedding), Block-Join-Struktur, `_doc_type`-Feld, Dimension-Mismatch-Erkennung
- [ ] **4. Suchanfrage-Pipeline** – Query-Embedding, `KnnFloatVectorQuery`, Cosine Cut-Off, Child→Parent-Promotion, RRF-Merge
- [ ] **5. Scoring & Ranking** – RRF-Formel (`1/(60+rank)`), warum RRF statt Boost-Faktor, `vectorMatchInfo` im Response
- [ ] **6. Aktivierung & Konfiguration** – Profil `vectorsearch`, System Property `vector.model`, Cut-Off-Wert, k-Parameter
- [ ] **7. Modelle** – e5-base vs. e5-large (Dimensionen, Performance), Link auf [ONNX.md](ONNX.md)
- [ ] **8. Technische Hinweise** – Bekannte Einschränkungen (DrillSideways, Score-Skalen), Mixed-Index-Zustand bei Profil-Wechsel

### Schritt 10: README.md aktualisieren
- [ ] Neue Sektion **"Semantic / Hybrid Search"** mit Kurzübersicht + Link auf `VECTORSEARCH.md`
- [ ] Link auf `ONNX.md` mit Kurzbeschreibung (Modell-Export & Quantisierung)
- [ ] Konfigurations-Sektion: neue Profile (`vectorsearch`) und System Property (`vector.model`) dokumentieren

---

## Aktivierungsbeispiele

```bash
# Wie bisher (kein Vector Search)
-Dspring.profiles.active=deployed

# Deployed + Vector Search (e5-base, Default, 768 Dims)
-Dspring.profiles.active=deployed,vectorsearch

# Deployed + Vector Search mit e5-large (1024 Dims)
-Dspring.profiles.active=deployed,vectorsearch -Dvector.model=e5-large

# Entwicklung mit Vector Search (Console-Logging)
-Dspring.profiles.active=vectorsearch
```

---

## Bekannte Einschränkungen

1. **DrillSideways + Block Join**: Facettierung mit positiven Facet-Filtern und Block-Join-Queries muss sorgfältig getestet werden. Bei Problemen: Facetten im Vector-Pfad deaktivieren.
2. **Chunk-Reporting**: `KnnFloatVectorQuery` gibt nur Parent-Scores zurück – welcher Chunk gematcht hat, erfordert einen zweiten Such-Schritt (Post-Processing).
3. **Mixed-Index-Zustand**: Wenn das `vectorsearch`-Profil aktiviert wird nach einer Indexierung ohne Vector Search, haben bestehende Dokumente keine Child-Docs. Erst nach Full-Re-Crawl sind alle Docs mit Vektoren versehen.
4. **Modellwechsel** (768→1024 Dims): Dimension-Mismatch-Check triggert automatisch Full-Re-Crawl.

---

## Kritische Dateien

| Datei                                             | Änderung                                                      |
|---------------------------------------------------|---------------------------------------------------------------|
| `pom.xml`                                         | `lucene-join` Dependency                                      |
| `src/main/java/.../onnx/ONNXService.java`         | Konstruktor parametrisieren (bereits fertig, nur Refactoring) |
| `src/main/java/.../config/ApplicationConfig.java` | Profil-Liste-Parsing, `vectorSearchEnabled` Flag              |
| `src/main/java/.../LuceneserverApplication.java`  | ONNXService-Wiring, Profil-Parsing                            |
| `src/main/java/.../crawler/DocumentIndexer.java`  | SCHEMA_VERSION 9, Child-Doc-Methode, `_doc_type`              |
| `src/main/java/.../LuceneIndexService.java`       | Block-Indexierung, Hybrid-Search, RRF, Dimension-Check        |
| `src/main/java/.../mcp/dto/SearchDocument.java`   | `VectorMatchInfo` hinzufügen                                  |
| `README.md`                                       | Hybrid-Search-Sektion + Links                                 |
| `VECTORSEARCH.md`                                 | Neue Datei                                                    |

---

## Verifikation

```bash
# Build
mvn compile

# Alle Tests (bestehende dürfen nicht brechen)
mvn test

# Nur neue Vector-Tests
mvn test -Dtest="VectorIndexingIntegrationTest,HybridSearchIntegrationTest,RRFScoringTest,CosineThresholdTest"

# Regression-Baseline stabil halten
mvn test -Dtest=SearchPrecisionRecallRegressionTest
```
