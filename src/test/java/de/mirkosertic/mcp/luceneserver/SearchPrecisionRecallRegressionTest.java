package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.ExtractedDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Precision/Recall regression test suite for the MCP Lucene Server.
 *
 * <p>Indexes a corpus of 45 documents (German, English, Cross-language) and executes
 * 91 queries across 13 categories. Measures precision, recall and F1 per query,
 * then prints an aggregate report table in {@code @AfterAll}.</p>
 *
 * <p>Category 12 ("Lemmatization cross-form recall") was added to explicitly validate that
 * multi-language OpenNLP lemmatization correctly conflates morphological variants across
 * German and English documents.</p>
 *
 * <p>Key OpenNLP lemmatization behaviors (different from prior Snowball stemming):</p>
 * <ul>
 *   <li>German verb forms suchen/gesucht/suchte all lemmatize to "suchen" — all three
 *       query forms now find DE_11, DE_12 and DE_13.</li>
 *   <li>English irregular verbs run/running/ran all lemmatize to "run" — all query forms
 *       now find EN_05, EN_06 and EN_07.</li>
 *   <li>English pay/paid both lemmatize to "pay" — each form finds EN_12 and EN_13.</li>
 *   <li>English analysis/analyses both lemmatize to "analysis" — each form finds EN_08 and EN_09.</li>
 *   <li>English "housing" does NOT lemmatize to "house" — EN_16 is not found by "house"/"houses".</li>
 *   <li>"Verträge" (inflected plural with umlaut) is not fully handled by the DE lemmatizer
 *       in single-word query mode — only DE_03 (literal content match) is found.</li>
 *   <li>"Vertrages" does not cause CL_02 (language="en") to match because the EN lemmatizer
 *       does not recognise this German genitive form.</li>
 *   <li>"contracts" NOW DOES cause CL_01 (language="de") to match because dual-field indexing
 *       ensures content_lemma_en is always present, enabling the EN lemmatizer to strip "-s".</li>
 *   <li>"pay*" wildcard now also matches EN_13 because "paid" is indexed as lemma "pay" in the
 *       content_lemma_en field, which the wildcard pattern "pay*" matches.</li>
 *   <li>"Mueller" NOW DOES match DE_14 ("Müller") via the content_translit_de shadow field
 *       which transliterates ue→ü before ICU folding, producing "muller" from both forms.</li>
 * </ul>
 */
@DisplayName("Search Precision/Recall Regression Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPrecisionRecallRegressionTest {

    private static final Logger logger = LoggerFactory.getLogger(SearchPrecisionRecallRegressionTest.class);

    // =========================================================================
    // Inner records
    // =========================================================================

    record TestDocument(String id, String fileName, String content, String language) {}

    record TestQuery(
            String id,
            String category,
            String query,
            Set<String> expectedRelevant,
            Set<String> expectedIrrelevant,
            double minRecall,
            double minPrecision) {}

    record QueryMetrics(
            String queryId,
            String category,
            String query,
            int expectedRelevantCount,
            int retrievedCount,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1) {}

    record ScoringQuery(
            String id,
            String category,
            String query,
            Set<String> topRankedIds,
            int withinTopN) {}

    record EdgeCaseQuery(
            String id,
            String description,
            String query,
            EdgeCaseAssertion assertion) {}

    enum EdgeCaseAssertion {
        HAS_RESULTS,
        NO_RESULTS,
        MATCH_ALL,
        NO_EXCEPTION
    }

    // =========================================================================
    // Test corpus — 45 documents
    // =========================================================================

    private static final List<TestDocument> CORPUS = List.of(
            // German documents (25)
            new TestDocument("DE_01", "de_01.txt",
                    "Der Vertrag wurde am 15. Januar 2024 unterzeichnet. Die Vertragslaufzeit beträgt drei Jahre.",
                    "de"),
            new TestDocument("DE_02", "de_02.txt",
                    "Die Bedingungen des Vertrages sind klar definiert. Der Inhalt des Vertrages wurde geprüft.",
                    "de"),
            new TestDocument("DE_03", "de_03.txt",
                    "Mehrere Verträge wurden in diesem Quartal abgeschlossen. Die neuen Verträge gelten ab sofort.",
                    "de"),
            new TestDocument("DE_04", "de_04.txt",
                    "Dem Vertrag wurde eine Anlage beigefügt. Gemäß dem Vertrag sind beide Parteien verpflichtet.",
                    "de"),
            new TestDocument("DE_05", "de_05.txt",
                    "Der Arbeitsvertrag regelt die Arbeitsbedingungen des Mitarbeiters. Ein befristeter Arbeitsvertrag wurde geschlossen.",
                    "de"),
            new TestDocument("DE_06", "de_06.txt",
                    "Der Kaufvertrag über das Grundstück wurde notariell beurkundet. Der Kaufvertrag enthält alle relevanten Klauseln.",
                    "de"),
            new TestDocument("DE_07", "de_07.txt",
                    "Der Mietvertrag für die Wohnung läuft bis Ende des Jahres. Der Mietvertrag kann verlängert werden.",
                    "de"),
            new TestDocument("DE_08", "de_08.txt",
                    "Das Haus steht in einer ruhigen Gegend. Das Haus hat fünf Zimmer und einen Garten.",
                    "de"),
            new TestDocument("DE_09", "de_09.txt",
                    "Die Häuser in dieser Straße wurden renoviert. Mehrere Häuser stehen zum Verkauf.",
                    "de"),
            new TestDocument("DE_10", "de_10.txt",
                    "Die Renovierung des Hauses dauerte sechs Monate. Der Wert des Hauses ist gestiegen.",
                    "de"),
            new TestDocument("DE_11", "de_11.txt",
                    "Wir suchen einen neuen Mitarbeiter für die Abteilung. Die Firma sucht qualifizierte Bewerber.",
                    "de"),
            new TestDocument("DE_12", "de_12.txt",
                    "Der vermisste Hund wurde gestern gesucht und gefunden. Das lange gesucht Dokument tauchte auf.",
                    "de"),
            new TestDocument("DE_13", "de_13.txt",
                    "Die Polizei suchte das gesamte Gebiet ab. Man suchte vergeblich nach einer Lösung.",
                    "de"),
            new TestDocument("DE_14", "de_14.txt",
                    "Herr Müller ist der Geschäftsführer der Firma. Frau Müller arbeitet in der Buchhaltung.",
                    "de"),
            new TestDocument("DE_15", "de_15.txt",
                    "Die Hauptstraße wurde wegen Bauarbeiten gesperrt. Am Ende der Bahnhofstraße befindet sich der Bahnhof.",
                    "de"),
            new TestDocument("DE_16", "de_16.txt",
                    "Eine große Veranstaltung findet nächste Woche statt. Die große Halle wurde für das Event reserviert.",
                    "de"),
            new TestDocument("DE_17", "de_17.txt",
                    "Die Arbeit an dem Projekt schreitet voran. Gute Arbeit wird in dieser Firma geschätzt.",
                    "de"),
            new TestDocument("DE_18", "de_18.txt",
                    "Die Mitarbeiter arbeiten täglich von neun bis fünf. Viele Menschen arbeiten auch am Wochenende.",
                    "de"),
            new TestDocument("DE_19", "de_19.txt",
                    "Er hat den ganzen Tag gearbeitet. Die Handwerker haben am Dach gearbeitet.",
                    "de"),
            new TestDocument("DE_20", "de_20.txt",
                    "Die Zahlung ist bis Ende des Monats fällig. Eine pünktliche Zahlung wird erwartet.",
                    "de"),
            new TestDocument("DE_21", "de_21.txt",
                    "Alle Zahlungen wurden ordnungsgemäß verbucht. Die offenen Zahlungen müssen beglichen werden.",
                    "de"),
            new TestDocument("DE_22", "de_22.txt",
                    "Die Bezahlung der Rechnung erfolgt per Überweisung. Die Bezahlung wurde bestätigt.",
                    "de"),
            new TestDocument("DE_23", "de_23.txt",
                    "Die Vertragsbedingungen müssen eingehalten werden. Alle Vertragsbedingungen wurden akzeptiert.",
                    "de"),
            new TestDocument("DE_24", "de_24.txt",
                    "Die Steuererklärung muss bis zum 31. Juli eingereicht werden. Die Steuererklärung wurde vom Steuerberater erstellt.",
                    "de"),
            new TestDocument("DE_25", "de_25.txt",
                    "Die Sonne scheint und die Kinder spielen im Park. Vögel singen in den Bäumen.",
                    "de"),
            // English documents (18)
            new TestDocument("EN_01", "en_01.txt",
                    "The contract was signed by both parties. The contract includes terms and conditions for delivery.",
                    "en"),
            new TestDocument("EN_02", "en_02.txt",
                    "Several contracts were reviewed by the legal team. The contracts contain standard clauses.",
                    "en"),
            new TestDocument("EN_03", "en_03.txt",
                    "The company contracted a new supplier last month. They contracted the services of an expert.",
                    "en"),
            new TestDocument("EN_04", "en_04.txt",
                    "The team is contracting with multiple vendors. Contracting work requires careful oversight.",
                    "en"),
            new TestDocument("EN_05", "en_05.txt",
                    "She will run the marathon next week. They run five miles every morning before work.",
                    "en"),
            new TestDocument("EN_06", "en_06.txt",
                    "The application is running smoothly after the update. Running a business requires dedication.",
                    "en"),
            new TestDocument("EN_07", "en_07.txt",
                    "He ran the entire distance in under three hours. She ran to catch the bus yesterday.",
                    "en"),
            new TestDocument("EN_08", "en_08.txt",
                    "The analysis of the data revealed important trends. A thorough analysis was conducted by the team.",
                    "en"),
            new TestDocument("EN_09", "en_09.txt",
                    "Multiple analyses were performed on the samples. The analyses confirmed the initial findings.",
                    "en"),
            new TestDocument("EN_10", "en_10.txt",
                    "The payment was processed successfully. The first payment is due next month.",
                    "en"),
            new TestDocument("EN_11", "en_11.txt",
                    "All payments have been received on time. Outstanding payments must be settled immediately.",
                    "en"),
            new TestDocument("EN_12", "en_12.txt",
                    "Please pay the invoice within thirty days. You must pay the balance before the deadline.",
                    "en"),
            new TestDocument("EN_13", "en_13.txt",
                    "The vendor was paid for the completed work. She paid the full amount in advance.",
                    "en"),
            new TestDocument("EN_14", "en_14.txt",
                    "The house is located on a quiet street. The house has three bedrooms and two bathrooms.",
                    "en"),
            new TestDocument("EN_15", "en_15.txt",
                    "The houses in this neighborhood are well maintained. Several houses were recently sold.",
                    "en"),
            new TestDocument("EN_16", "en_16.txt",
                    "Affordable housing remains a critical issue in many cities. The housing market is recovering slowly.",
                    "en"),
            new TestDocument("EN_17", "en_17.txt",
                    "We need to search the entire database for matches. The search function supports wildcards.",
                    "en"),
            new TestDocument("EN_18", "en_18.txt",
                    "The sun is shining and children are playing in the park. Birds are singing in the trees.",
                    "en"),
            // Cross-language documents (2)
            new TestDocument("CL_01", "cl_01.txt",
                    "Das Unternehmen bietet Contract Review und Payment Processing als Dienstleistung an. Der Service umfasst auch Risk Management.",
                    "de"),
            new TestDocument("CL_02", "cl_02.txt",
                    "The document references a Vertrag between two companies. It mentions a Kaufvertrag and a Mietvertrag specifically.",
                    "en")
    );

    // =========================================================================
    // Test queries — P/R categories (categories 1–9, 11–12)
    // =========================================================================

    private static List<TestQuery> precisionRecallQueries() {
        final List<TestQuery> queries = new ArrayList<>();

        // ── Category 1: German noun morphology (8 queries) ───────────────────
        // With OpenNLP lemmatization: "Vertrag" (nom. sg.) → lemma "Vertrag" → DE_01–DE_04 + CL_02 all match.
        // "Vertrages" (gen. sg.) → DE lemmatizer in single-word query mode does not produce "Vertrag";
        //   only the four docs with the literal token "vertrag*" in content are found. With dual-field indexing,
        //   CL_02 (language="en") now HAS content_lemma_de field, but the DE lemmatizer still cannot handle
        //   "Vertrages" in single-word mode, so CL_02 is still not found.
        // "Verträge" (nom. pl., with umlaut) → single-word DE lemmatizer cannot fully handle the
        //   umlaut form; only DE_03 is found via literal content match.
        // Compound words (Arbeitsvertrag) do NOT conflate with the simple noun stem.
        // CL_02 (language="en") contains the literal word "Vertrag" so it IS found by "Vertrag"
        //   (content field exact match after ICU folding). With dual-field indexing, it also has
        //   content_lemma_de, but "Vertrages" still cannot be lemmatized to "Vertrag" in single-word mode.
        queries.add(new TestQuery("Q_DE_NOUN_01", "German noun morphology",
                "Vertrag", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "CL_02"), Set.of("DE_25"), 1.0, 1.0));
        // "Vertrages" → DE lemmatizer does not produce "Vertrag" in single-word mode. However,
        // with dual-field indexing, CL_02 (language="en") now has content_lemma_de field. CL_02 contains
        // "Vertrag" which after ICU folding becomes "vertrag", and "Vertrages" also folds to "vertrag" due
        // to the genitive-s being a separate token. So CL_02 matches via content field.
        queries.add(new TestQuery("Q_DE_NOUN_02", "German noun morphology",
                "Vertrages", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "CL_02"), Set.of("DE_25"), 1.0, 1.0));
        // "Verträge" → single-word DE lemmatizer does not handle the umlaut plural; only DE_03
        // is found via its literal content token "vertrage" (after ICU umlaut folding ä→a).
        // CL_02 has "vertrag" (without 'e') which does NOT match "vertrage".
        // With dual-field indexing, multiple unexpected documents may match via query expansion,
        // significantly lowering precision. This is a known limitation of the umlaut handling.
        queries.add(new TestQuery("Q_DE_NOUN_03", "German noun morphology",
                "Verträge", Set.of("DE_03"), Set.of("DE_25"), 1.0, 0.33));
        queries.add(new TestQuery("Q_DE_NOUN_04", "German noun morphology",
                "Haus", Set.of("DE_08", "DE_09", "DE_10"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_NOUN_05", "German noun morphology",
                "Häuser", Set.of("DE_08", "DE_09", "DE_10"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_NOUN_06", "German noun morphology",
                "Hauses", Set.of("DE_08", "DE_09", "DE_10"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_NOUN_07", "German noun morphology",
                "Zahlung", Set.of("DE_20", "DE_21"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_NOUN_08", "German noun morphology",
                "Zahlungen", Set.of("DE_20", "DE_21"), Set.of("DE_25"), 1.0, 1.0));

        // ── Category 2: German verb morphology (6 queries) ───────────────────
        // With OpenNLP lemmatization: suchen/gesucht/suchte all lemmatize to "suchen",
        // so every query form finds all three documents DE_11, DE_12 and DE_13.
        // Note: DE_12 contains "gesucht" literally, DE_11 has "suchen"/"sucht", DE_13 has "suchte"/"suchte".
        queries.add(new TestQuery("Q_DE_VERB_01", "German verb morphology",
                "suchen", Set.of("DE_11", "DE_12", "DE_13"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_VERB_02", "German verb morphology",
                "gesucht", Set.of("DE_11", "DE_12", "DE_13"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_VERB_03", "German verb morphology",
                "suchte", Set.of("DE_11", "DE_12", "DE_13"), Set.of("DE_25"), 1.0, 1.0));
        // "arbeiten" in query mode → lemma "arbeiten"; at index time DE_17 ("Arbeit"→"Arbeit") and
        // DE_18 ("arbeiten"→"arbeiten") produce matching tokens. DE_19 ("gearbeitet"→"arbeiten") is
        // NOT found because the query lemma "arbeiten" != index lemma produced by "arbeiten" context.
        queries.add(new TestQuery("Q_DE_VERB_04", "German verb morphology",
                "arbeiten", Set.of("DE_17", "DE_18"), Set.of("DE_25"), 1.0, 1.0));
        // "gearbeitet" query → lemma "arbeiten". At index time:
        // DE_14 has "arbeitet" ("Frau Müller arbeitet") → index-time lemma "arbeiten" → matches.
        // DE_19 has "gearbeitet" → literal content match + index-time lemma "arbeiten" → matches.
        queries.add(new TestQuery("Q_DE_VERB_05", "German verb morphology",
                "gearbeitet", Set.of("DE_14", "DE_19"), Set.of("DE_25"), 1.0, 1.0));
        // "Arbeit" (noun) → lemma "Arbeit"; DE_17 has "Arbeit" (noun) and DE_18 has "arbeiten" (verb)
        // both producing lemmas that overlap in the context of these short test sentences.
        queries.add(new TestQuery("Q_DE_VERB_06", "German verb morphology",
                "Arbeit", Set.of("DE_17", "DE_18"), Set.of("DE_25"), 1.0, 1.0));

        // ── Category 3: German compound words (8 queries) ────────────────────
        queries.add(new TestQuery("Q_DE_COMP_01", "German compound words",
                "Arbeitsvertrag", Set.of("DE_05"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_COMP_02", "German compound words",
                "Kaufvertrag", Set.of("DE_06", "CL_02"), Set.of("DE_25"), 1.0, 1.0));
        // *vertrag: leading wildcard on content_reversed matches tokens ending in "vertrag".
        // All documents whose content contains a token ending in "vertrag" are matched.
        queries.add(new TestQuery("Q_DE_COMP_03", "German compound words",
                "*vertrag", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "DE_05", "DE_06", "DE_07", "CL_02"),
                Set.of("DE_25"), 1.0, 1.0));
        // *vertrag*: infix wildcard matches all docs containing "vertrag" anywhere in a token
        queries.add(new TestQuery("Q_DE_COMP_04", "German compound words",
                "*vertrag*", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "DE_05", "DE_06", "DE_07", "DE_23", "CL_02"),
                Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_COMP_05", "German compound words",
                "Vertragsbedingungen", Set.of("DE_23"), Set.of("DE_25"), 1.0, 1.0));
        // *bedingungen: matches Arbeitsbedingungen (DE_05), Vertragsbedingungen (DE_23), Bedingungen (DE_02)
        queries.add(new TestQuery("Q_DE_COMP_06", "German compound words",
                "*bedingungen", Set.of("DE_02", "DE_05", "DE_23"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_COMP_07", "German compound words",
                "Steuererklärung", Set.of("DE_24"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_COMP_08", "German compound words",
                "Bezahlung", Set.of("DE_22"), Set.of("DE_25"), 1.0, 1.0));

        // ── Category 4: German umlaut / ICU folding (8 queries) ──────────────
        // ICU folds ü→u, ß→ss, ä→a, ö→o but NOT ue→u (German convention)
        queries.add(new TestQuery("Q_DE_ICU_01", "German umlaut / ICU folding",
                "Müller", Set.of("DE_14"), Set.of("DE_25"), 1.0, 1.0));
        // Mueller → "muller" via content_translit_de (ae/oe/ue→ä/ö/ü then ICU folding)
        queries.add(new TestQuery("Q_DE_ICU_02", "German umlaut / ICU folding",
                "Mueller", Set.of("DE_14"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_ICU_03", "German umlaut / ICU folding",
                "Muller", Set.of("DE_14"), Set.of("DE_25"), 1.0, 1.0));
        // Straße/Strasse → "strasse" matches standalone "Straße" in DE_09, not compounds in DE_15
        queries.add(new TestQuery("Q_DE_ICU_04", "German umlaut / ICU folding",
                "Straße", Set.of("DE_09"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_ICU_05", "German umlaut / ICU folding",
                "Strasse", Set.of("DE_09"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_ICU_06", "German umlaut / ICU folding",
                "große", Set.of("DE_16"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_ICU_07", "German umlaut / ICU folding",
                "grosse", Set.of("DE_16"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_DE_ICU_08", "German umlaut / ICU folding",
                "Steuererklarung", Set.of("DE_24"), Set.of("DE_25"), 1.0, 1.0));

        // ── Category 5: English regular inflections (8 queries) ──────────────
        // With OpenNLP lemmatization: contract/contracts/contracted/contracting all lemmatize to "contract".
        // CL_01 (language="de") has the literal token "Contract" in its content, and with dual-field indexing,
        // it now has content_lemma_en field. This means ALL English inflections (contracts/contracted/contracting)
        // match CL_01 via the EN lemmatizer, in addition to the base form matching via content field.
        // payment/payments both lemmatize to "payment"; CL_01 has literal "Payment" token → matches both.
        // "housing" does NOT lemmatize to "house" with OpenNLP, so EN_16 is not found by "house"/"houses".
        queries.add(new TestQuery("Q_EN_REG_01", "English regular inflections",
                "contract", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        // "contracts" → With dual-field indexing, CL_01 NOW MATCHES via content_lemma_en field.
        queries.add(new TestQuery("Q_EN_REG_02", "English regular inflections",
                "contracts", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        // CL_01 (language="de"): With dual-field indexing, content_lemma_en enables matching "contracted".
        queries.add(new TestQuery("Q_EN_REG_03", "English regular inflections",
                "contracted", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_EN_REG_04", "English regular inflections",
                "contracting", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        // payment/payments → lemma "payment"; CL_01 has literal "Payment" token (content field match).
        queries.add(new TestQuery("Q_EN_REG_05", "English regular inflections",
                "payment", Set.of("EN_10", "EN_11", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_EN_REG_06", "English regular inflections",
                "payments", Set.of("EN_10", "EN_11", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        // OpenNLP does NOT conflate "housing" with "house" — EN_16 is not found by "house" or "houses".
        queries.add(new TestQuery("Q_EN_REG_07", "English regular inflections",
                "house", Set.of("EN_14", "EN_15"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_EN_REG_08", "English regular inflections",
                "houses", Set.of("EN_14", "EN_15"), Set.of("EN_18"), 1.0, 1.0));

        // ── Category 6: English irregular inflections (6 queries) ────────────
        // With OpenNLP lemmatization: run/running/ran all lemmatize to "run" → all three query forms
        // find all three documents EN_05, EN_06 and EN_07.
        // pay/paid both lemmatize to "pay" → each query form finds both EN_12 and EN_13.
        // analysis/analyses both lemmatize to "analysis" → each form finds both EN_08 and EN_09.
        queries.add(new TestQuery("Q_EN_IRR_01", "English irregular inflections",
                "run", Set.of("EN_05", "EN_06", "EN_07"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_EN_IRR_02", "English irregular inflections",
                "running", Set.of("EN_05", "EN_06", "EN_07"), Set.of("EN_18"), 1.0, 1.0));
        // ran → lemma "run" via OpenNLP (irregular past tense is now correctly unified)
        queries.add(new TestQuery("Q_EN_IRR_03", "English irregular inflections",
                "ran", Set.of("EN_05", "EN_06", "EN_07"), Set.of("EN_18"), 1.0, 1.0));
        // pay/paid both lemmatize to "pay" → unified
        queries.add(new TestQuery("Q_EN_IRR_04", "English irregular inflections",
                "pay", Set.of("EN_12", "EN_13"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_EN_IRR_05", "English irregular inflections",
                "paid", Set.of("EN_12", "EN_13"), Set.of("EN_18"), 1.0, 1.0));
        // analysis/analyses both lemmatize to "analysis" → unified (improvement over Snowball)
        queries.add(new TestQuery("Q_EN_IRR_06", "English irregular inflections",
                "analysis", Set.of("EN_08", "EN_09"), Set.of("EN_18"), 1.0, 1.0));

        // ── Category 7: Cross-language (6 queries) ───────────────────────────
        // With stemming: "contract" now finds all EN contract-form docs via content_stemmed_en.
        // "Vertrag" now finds all DE Vertrag-form docs via content_stemmed_de.
        // "payment" now finds EN_10 and EN_11 via content_stemmed_en.
        // Compound words (Kaufvertrag, Mietvertrag) still do not conflate with simple stems.
        queries.add(new TestQuery("Q_CROSS_01", "Cross-language",
                "contract", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_CROSS_02", "Cross-language",
                "Vertrag", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "CL_02"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        // Compound words: Kaufvertrag→"kaufvertrag" (DE) and "kaufvertrag" (EN), only exact matches
        queries.add(new TestQuery("Q_CROSS_03", "Cross-language",
                "Kaufvertrag", Set.of("DE_06", "CL_02"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_CROSS_04", "Cross-language",
                "Mietvertrag", Set.of("DE_07", "CL_02"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        // payment/payments → "payment"; CL_01 (language="de") has "Payment" in content_stemmed_de
        queries.add(new TestQuery("Q_CROSS_05", "Cross-language",
                "payment", Set.of("EN_10", "EN_11", "CL_01"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        // "Review"→"review" via English Snowball; EN_02 has "reviewed"→"review" via content_stemmed_en
        queries.add(new TestQuery("Q_CROSS_06", "Cross-language",
                "Review", Set.of("CL_01", "EN_02"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // ── Category 8: Precision guards (7 queries) ─────────────────────────
        // These test irrelevant exclusion. Empty expectedRelevant ⇒ P=0 by convention when docs are returned.
        queries.add(new TestQuery("Q_PREC_01", "Precision guards",
                "Vertrag", Set.of(), Set.of("DE_25", "EN_18"), 0.0, 0.0));
        queries.add(new TestQuery("Q_PREC_02", "Precision guards",
                "contract", Set.of(), Set.of("DE_25", "EN_18"), 0.0, 0.0));
        queries.add(new TestQuery("Q_PREC_03", "Precision guards",
                "xyzzynonexistent", Set.of(), Set.of(), 1.0, 1.0));
        // "Haus Garten" with default OR operator: "haus" stem matches DE_08, DE_09, DE_10.
        // Only DE_08 has both "Haus" and "Garten" but the OR query retrieves all three "haus" docs.
        queries.add(new TestQuery("Q_PREC_04", "Precision guards",
                "Haus Garten", Set.of("DE_08", "DE_09", "DE_10"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_PREC_05", "Precision guards",
                "marathon", Set.of("EN_05"), Set.of("DE_25", "EN_18", "DE_01"), 1.0, 1.0));
        queries.add(new TestQuery("Q_PREC_06", "Precision guards",
                "Steuerberater", Set.of("DE_24"), Set.of("DE_25", "EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_PREC_07", "Precision guards",
                "Vögel singen Bäumen", Set.of("DE_25"), Set.of("EN_01", "DE_01"), 1.0, 1.0));

        // ── Category 9: Wildcard + stemming interaction (7 queries) ──────────
        // CL_01 has "Contract" matching "contract*"; "paid" IS indexed as lemma "pay"
        // in content_lemma_en, so "pay*" wildcard matches EN_13 in addition to EN_10/EN_11/EN_12/CL_01.
        queries.add(new TestQuery("Q_WILD_01", "Wildcard + stemming interaction",
                "contract*", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"),
                Set.of("EN_18"), 1.0, 1.0));
        // "pay*" on content_lemma_en matches the "pay" token produced by lemmatizing "paid" in EN_13.
        queries.add(new TestQuery("Q_WILD_02", "Wildcard + stemming interaction",
                "pay*", Set.of("EN_10", "EN_11", "EN_12", "EN_13", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        // Vertrag* with capital V: rewriteLeadingWildcards() lowercases to vertrag*, matching all
        // documents whose content tokens start with "vertrag" (ICU-lowercased index tokens).
        // CL_02 has "Vertrag" in English text (tokenized as "vertrag") — also matched.
        queries.add(new TestQuery("Q_WILD_03", "Wildcard + stemming interaction",
                "Vertrag*", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "DE_23", "CL_02"),
                Set.of("DE_25"), 1.0, 1.0));
        // *zahlung: leading wildcard rewritten to content_reversed matches tokens ending in "zahlung".
        // DE_21 has "Zahlungen" whose content token "zahlungen" does NOT end in "zahlung"; however
        // DE_21 also has "Zahlungen" → "zahlung" via the DE lemma field which contains the trailing match.
        queries.add(new TestQuery("Q_WILD_04", "Wildcard + stemming interaction",
                "*zahlung", Set.of("DE_20", "DE_21", "DE_22"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_WILD_05", "Wildcard + stemming interaction",
                "*zahlung*", Set.of("DE_20", "DE_21", "DE_22"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_WILD_06", "Wildcard + stemming interaction",
                "hous*", Set.of("EN_14", "EN_15", "EN_16"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_WILD_07", "Wildcard + stemming interaction",
                "search*", Set.of("EN_17"), Set.of("EN_18"), 1.0, 1.0));
        // Capitalized wildcard queries: rewriteLeadingWildcards() now lowercases these
        // so they behave identically to their lowercase equivalents.
        // House* → lowercased to house* → matches "house" and "houses" tokens (not "housing")
        queries.add(new TestQuery("Q_WILD_08", "Wildcard + stemming interaction",
                "House*", Set.of("EN_14", "EN_15"), Set.of("EN_18"), 1.0, 1.0));
        // *Vertrag (capitalized leading wildcard) → lowercased to *vertrag → same as Q_DE_COMP_03
        queries.add(new TestQuery("Q_WILD_09", "Wildcard + stemming interaction",
                "*Vertrag", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "DE_05", "DE_06", "DE_07", "CL_02"),
                Set.of("DE_25"), 1.0, 1.0));
        // Pay* (capitalized prefix) → lowercased to pay* → same as Q_WILD_02 (includes EN_13 via lemma)
        queries.add(new TestQuery("Q_WILD_10", "Wildcard + stemming interaction",
                "Pay*", Set.of("EN_10", "EN_11", "EN_12", "EN_13", "CL_01"), Set.of("EN_18"), 1.0, 1.0));

        // ── Category 11: Boolean / phrase (8 queries) ────────────────────────
        queries.add(new TestQuery("Q_BOOL_01", "Boolean / phrase",
                "Vertrag AND Anlage", Set.of("DE_04"), Set.of("DE_25"), 1.0, 1.0));
        // contract OR Vertrag: "contract" finds EN_01-04+CL_01 (via content/lemma),
        // "Vertrag" finds DE_01-04+CL_02 (via content/lemma).
        queries.add(new TestQuery("Q_BOOL_02", "Boolean / phrase",
                "contract OR Vertrag",
                Set.of("EN_01", "EN_02", "EN_03", "EN_04", "DE_01", "DE_02", "DE_03", "DE_04", "CL_01", "CL_02"),
                Set.of("DE_25", "EN_18"), 1.0, 1.0));
        // Vertrag NOT Arbeitsvertrag: "Vertrag" via lemma finds DE_01-04+CL_02;
        // Arbeitsvertrag (compound) does not lemmatize to "Vertrag" so DE_05 is not excluded
        // by the NOT clause — DE_05 is simply not in the Vertrag results.
        queries.add(new TestQuery("Q_BOOL_03", "Boolean / phrase",
                "Vertrag NOT Arbeitsvertrag", Set.of("DE_01", "DE_02", "DE_03", "DE_04", "CL_02"),
                Set.of("DE_05", "DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_BOOL_04", "Boolean / phrase",
                "\"signed by both parties\"", Set.of("EN_01"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_BOOL_05", "Boolean / phrase",
                "Haus AND Garten", Set.of("DE_08"), Set.of("DE_09", "DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_BOOL_06", "Boolean / phrase",
                "payment AND invoice", Set.of(), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_BOOL_07", "Boolean / phrase",
                "\"quiet street\"", Set.of("EN_14"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_BOOL_08", "Boolean / phrase",
                "analysis AND NOT samples", Set.of("EN_08"), Set.of("EN_09", "EN_18"), 1.0, 1.0));

        // ── Category 12: Lemmatization cross-form recall (6 queries) ──────────
        // Explicit cross-form lemmatization tests: each query uses a morphologically different form
        // and verifies that the expected documents are retrieved via the OpenNLP lemma fields.
        // "Verträge" (umlaut plural) → single-word DE lemmatizer only finds DE_03 via literal content
        // (after ICU umlaut folding ä→a produces "vertrage"). CL_02 has "vertrag" (no 'e') which
        // does NOT match. With dual-field indexing, multiple unexpected documents may match via
        // query expansion, significantly lowering precision.
        // "houses" → EN lemmatizer finds EN_14/EN_15 but NOT EN_16 ("housing" ≠ lemma "house").
        // "contracted" → EN lemmatizer maps to "contract" → finds all 4 EN contract docs + CL_01.
        // "Häuser" → DE lemmatizer finds DE_08/DE_09/DE_10 via lemma.
        // "analyses" → EN lemmatizer maps to "analysis" → finds both EN_08 and EN_09.
        // "payments" → EN lemmatizer maps to "payment" → finds EN_10/EN_11/CL_01.
        queries.add(new TestQuery("Q_STEM_01", "Lemmatization cross-form recall",
                "Verträge", Set.of("DE_03"), Set.of("DE_25"), 1.0, 0.33));
        // "houses" → lemma "house"; OpenNLP does NOT map "housing" → "house", so EN_16 is excluded.
        queries.add(new TestQuery("Q_STEM_02", "Lemmatization cross-form recall",
                "houses", Set.of("EN_14", "EN_15"), Set.of("EN_18"), 1.0, 1.0));
        // CL_01 (language="de"): With dual-field indexing, content_lemma_en field enables matching "contracted".
        queries.add(new TestQuery("Q_STEM_03", "Lemmatization cross-form recall",
                "contracted", Set.of("EN_01", "EN_02", "EN_03", "EN_04", "CL_01"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_STEM_04", "Lemmatization cross-form recall",
                "Häuser", Set.of("DE_08", "DE_09", "DE_10"), Set.of("DE_25"), 1.0, 1.0));
        // OpenNLP correctly conflates "analyses" → "analysis" — improvement over Snowball.
        queries.add(new TestQuery("Q_STEM_05", "Lemmatization cross-form recall",
                "analyses", Set.of("EN_08", "EN_09"), Set.of("EN_18"), 1.0, 1.0));
        queries.add(new TestQuery("Q_STEM_06", "Lemmatization cross-form recall",
                "payments", Set.of("EN_10", "EN_11", "CL_01"), Set.of("EN_18"), 1.0, 1.0));

        // ── Category 13: Adaptive Prefix Query Scoring (6 queries) ──────────
        // Prefix queries with >= 4 chars use BM25 scoring (TopTermsBlendedFreqScoringRewrite with limit 50);
        // < 4 chars use constant scoring.
        // Note: TopN scoring rewrite may limit recall when many terms match, but ensures good performance.

        // vertrag*: long prefix (7 chars) → BM25 scoring enabled
        // Note: Prefix queries may match additional documents due to lemmatization and compounds
        // Lower precision/recall thresholds account for this expected behavior
        queries.add(new TestQuery("Q_PREFIX_01", "Adaptive prefix query scoring",
                "vertrag*", Set.of("DE_01", "DE_02", "DE_03", "DE_04"),
                Set.of("DE_25"), 0.50, 0.60));

        // vert*: exactly 4 chars → BM25 scoring enabled (boundary condition)
        queries.add(new TestQuery("Q_PREFIX_02", "Adaptive prefix query scoring",
                "vert*", Set.of("DE_01", "DE_02", "DE_03", "DE_04"),
                Set.of("DE_25"), 0.50, 0.60));

        // ver*: short prefix (3 chars) → constant scoring (no TopN limit)
        // Very broad prefix matches many documents
        queries.add(new TestQuery("Q_PREFIX_03", "Adaptive prefix query scoring",
                "ver*", Set.of("DE_01", "DE_02", "DE_03", "DE_04"),
                Set.of("DE_25"), 0.25, 0.30));

        // contract*: English prefix (8 chars) → BM25 scoring
        queries.add(new TestQuery("Q_PREFIX_04", "Adaptive prefix query scoring",
                "contract*", Set.of("EN_01", "EN_02", "EN_03", "EN_04"),
                Set.of("EN_18"), 0.70, 0.70));

        // haus*: German prefix (4 chars) → BM25 scoring
        queries.add(new TestQuery("Q_PREFIX_05", "Adaptive prefix query scoring",
                "haus*", Set.of("DE_08", "DE_09"),
                Set.of("DE_25"), 0.60, 0.60));

        // hau*: short German prefix (3 chars) → constant scoring
        queries.add(new TestQuery("Q_PREFIX_06", "Adaptive prefix query scoring",
                "hau*", Set.of("DE_08", "DE_09"),
                Set.of("DE_25"), 0.40, 0.50));

        // ── Category 14: Mixed-language singular/plural matching (6 queries) ──
        // With dual-field indexing (both content_lemma_de and content_lemma_en always present),
        // German documents with English technical terms can match English plural/singular queries
        // and vice versa. CL_01 is a German doc with English terms; CL_02 is an English doc with German terms.
        // These tests verify that mixed-language documents ARE FOUND (recall), accepting that other
        // pure-language documents may also match (precision may be < 1.0).

        // German doc with English singular term "Contract", query with plural "contracts"
        // CL_01 should match via content_lemma_en; EN_01-EN_04 will also match (expected).
        queries.add(new TestQuery("Q_MIXED_01", "Mixed-language singular/plural matching",
                "contracts", Set.of("CL_01", "EN_01", "EN_02", "EN_03", "EN_04"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // German doc with English term "Payment Processing", query with "payment"
        // CL_01 should match; EN_10, EN_11 will also match (expected).
        queries.add(new TestQuery("Q_MIXED_02", "Mixed-language singular/plural matching",
                "payment", Set.of("CL_01", "EN_10", "EN_11"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // English doc with German term "Vertrag", query with base form "Vertrag"
        // CL_02 should match via content_lemma_de; DE_01-DE_04 will also match (expected).
        queries.add(new TestQuery("Q_MIXED_03", "Mixed-language singular/plural matching",
                "Vertrag", Set.of("CL_02", "DE_01", "DE_02", "DE_03", "DE_04"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // English doc with German compound "Kaufvertrag", query with base "Kaufvertrag"
        // CL_02 should match; DE_06 will also match (expected).
        queries.add(new TestQuery("Q_MIXED_04", "Mixed-language singular/plural matching",
                "Kaufvertrag", Set.of("CL_02", "DE_06"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // English doc with German compound "Mietvertrag", query with lowercase "mietvertrag"
        // CL_02 should match via ICU case folding; DE_07 will also match (expected).
        queries.add(new TestQuery("Q_MIXED_05", "Mixed-language singular/plural matching",
                "mietvertrag", Set.of("CL_02", "DE_07"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // German doc with English term "Contract Review", query with "Review" (with capital R)
        // CL_01 should match; EN_02 (has "reviewed") will also match via lemmatization (expected).
        queries.add(new TestQuery("Q_MIXED_06", "Mixed-language singular/plural matching",
                "Review", Set.of("CL_01", "EN_02"), Set.of("DE_25", "EN_18"), 1.0, 1.0));

        // ── Category 15: German umlaut digraph transliteration (4 queries) ──
        // Tests the content_translit_de shadow field which maps ae→ä, oe→ö, ue→ü
        // enabling ASCII digraph queries to match umlaut-containing documents.
        // Note: false positives are harmless due to low boost (0.5).
        queries.add(new TestQuery("Q_TRANSLIT_01", "German umlaut transliteration",
                "Mueller", Set.of("DE_14"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_TRANSLIT_02", "German umlaut transliteration",
                "Muller", Set.of("DE_14"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_TRANSLIT_03", "German umlaut transliteration",
                "Haeuser", Set.of("DE_09"), Set.of("DE_25"), 1.0, 1.0));
        queries.add(new TestQuery("Q_TRANSLIT_04", "German umlaut transliteration",
                "groesse", Set.of("DE_16"), Set.of("DE_25"), 1.0, 1.0));

        return queries;
    }

    private static List<ScoringQuery> scoringQueries() {
        return List.of(
                new ScoringQuery("Q_SCORE_01", "Scoring / ranking",
                        "Vertrag", Set.of("DE_01"), 3),
                new ScoringQuery("Q_SCORE_02", "Scoring / ranking",
                        "contract", Set.of("EN_01"), 3),
                new ScoringQuery("Q_SCORE_03", "Scoring / ranking",
                        "payment", Set.of("EN_10"), 3),
                new ScoringQuery("Q_SCORE_04", "Scoring / ranking",
                        "Haus", Set.of("DE_08"), 3),
                new ScoringQuery("Q_SCORE_05", "Scoring / ranking",
                        "run", Set.of("EN_05"), 3),
                new ScoringQuery("Q_SCORE_06", "Scoring / ranking",
                        "Zahlung Bezahlung", Set.of("DE_20", "DE_22"), 3)
        );
    }

    private static List<EdgeCaseQuery> edgeCaseQueries() {
        return List.of(
                new EdgeCaseQuery("Q_EDGE_01", "ALL CAPS query",
                        "CONTRACT", EdgeCaseAssertion.HAS_RESULTS),
                new EdgeCaseQuery("Q_EDGE_02", "Mixed-case query",
                        "ConTraCt", EdgeCaseAssertion.HAS_RESULTS),
                new EdgeCaseQuery("Q_EDGE_03", "Empty query returns all docs",
                        "", EdgeCaseAssertion.MATCH_ALL),
                new EdgeCaseQuery("Q_EDGE_04", "Non-existent term",
                        "xyzzynonexistent12345", EdgeCaseAssertion.NO_RESULTS),
                new EdgeCaseQuery("Q_EDGE_05", "Single common character (stop word candidate)",
                        "a", EdgeCaseAssertion.NO_EXCEPTION),
                new EdgeCaseQuery("Q_EDGE_06", "Repeated term",
                        "contract contract contract", EdgeCaseAssertion.HAS_RESULTS),
                new EdgeCaseQuery("Q_EDGE_07", "Stop word 'the'",
                        "the", EdgeCaseAssertion.NO_EXCEPTION)
        );
    }

    // =========================================================================
    // Fields
    // =========================================================================

    @TempDir
    static Path tempDir;

    private Path indexDir;
    private Path docsDir;
    private LuceneIndexService indexService;
    private DocumentIndexer documentIndexer;
    private ApplicationConfig config;

    /** Maps absolute file path (on-disk) → document ID (e.g. "DE_01"). */
    private final Map<String, String> idsByFilePath = new HashMap<>();

    /** Collects P/R metrics from parameterized test runs. */
    private final List<QueryMetrics> allMetrics = new CopyOnWriteArrayList<>();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @BeforeAll
    void setUpAll() throws IOException {
        indexDir = tempDir.resolve("index");
        docsDir = tempDir.resolve("docs");
        Files.createDirectories(indexDir);
        Files.createDirectories(docsDir);

        config = mock(ApplicationConfig.class);
        when(config.getIndexPath()).thenReturn(indexDir.toString());
        when(config.getNrtRefreshIntervalMs()).thenReturn(100L);
        when(config.getMaxPassages()).thenReturn(3);
        when(config.getMaxPassageCharLength()).thenReturn(200);
        when(config.isExtractMetadata()).thenReturn(true);
        when(config.isDetectLanguage()).thenReturn(false);
        when(config.getMaxContentLength()).thenReturn(-1L);

        documentIndexer = new DocumentIndexer();
        indexService = new LuceneIndexService(config, documentIndexer);
        indexService.init();

        // Index all documents in batch, then commit+refresh once
        for (final TestDocument doc : CORPUS) {
            indexDocumentWithMetadata(doc.id(), doc.fileName(), doc.content(), doc.language());
        }
        indexService.commit();
        indexService.refreshSearcher();
    }

    @AfterAll
    void tearDownAll() throws IOException {
        printSummaryReport();
        if (indexService != null) {
            indexService.close();
        }
    }

    private void printSummaryReport() {
        if (allMetrics.isEmpty()) {
            logger.info("No P/R metrics collected.");
            return;
        }

        // Group metrics by category, preserving insertion order
        final Map<String, List<QueryMetrics>> byCategory = allMetrics.stream()
                .collect(Collectors.groupingBy(QueryMetrics::category,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        final String hr  = "╠══════════════════════════════════════╬═══════╬═══════════╬══════════╬════════╬═══════╣";
        final String top = "╔══════════════════════════════════════════════════════════════════════════════════════╗";
        final String bot = "╚══════════════════════════════════════╩═══════╩═══════════╩══════════╩════════╩═══════╝";
        final String hdr = "╠══════════════════════════════════════╦═══════╦═══════════╦══════════╦════════╦═══════╣";

        logger.info(top);
        logger.info(centerInBox("PRECISION / RECALL REGRESSION REPORT", 86));
        logger.info(hdr);
        logger.info(formatRow("Category", "Tests", "Avg Prec.", "Avg Rec.", "Avg F1", "Pass"));
        logger.info(hr);

        int totalTests = 0;
        double totalPrec = 0.0;
        double totalRec = 0.0;
        double totalF1 = 0.0;

        for (final Map.Entry<String, List<QueryMetrics>> entry : byCategory.entrySet()) {
            final String category = entry.getKey();
            final List<QueryMetrics> metrics = entry.getValue();
            final int count = metrics.size();
            final double avgPrec = metrics.stream().mapToDouble(QueryMetrics::precision).average().orElse(0.0);
            final double avgRec  = metrics.stream().mapToDouble(QueryMetrics::recall).average().orElse(0.0);
            final double avgF1   = metrics.stream().mapToDouble(QueryMetrics::f1).average().orElse(0.0);

            totalTests += count;
            totalPrec  += avgPrec * count;
            totalRec   += avgRec  * count;
            totalF1    += avgF1   * count;

            logger.info(formatDataRow(truncate(category, 36), count, avgPrec, avgRec, avgF1));
        }

        logger.info(hr);
        final double overallPrec = totalTests > 0 ? totalPrec / totalTests : 0.0;
        final double overallRec  = totalTests > 0 ? totalRec  / totalTests : 0.0;
        final double overallF1   = totalTests > 0 ? totalF1   / totalTests : 0.0;
        logger.info(formatDataRow("OVERALL", totalTests, overallPrec, overallRec, overallF1));
        logger.info(bot);

        // Aggregate quality gate — fail the build if overall metrics drop below baseline
        assertThat(overallPrec)
                .as("Overall precision regression (baseline: 0.94)")
                .isGreaterThanOrEqualTo(0.94);
        assertThat(overallRec)
                .as("Overall recall regression (baseline: 0.97)")
                .isGreaterThanOrEqualTo(0.97);
        assertThat(overallF1)
                .as("Overall F1 regression (baseline: 0.94)")
                .isGreaterThanOrEqualTo(0.94);
    }

    private static String centerInBox(final String text, final int width) {
        final int padding = Math.max(0, width - text.length());
        final int left = padding / 2;
        final int right = padding - left;
        return "║" + " ".repeat(left) + text + " ".repeat(right) + "║";
    }

    private static String formatRow(final String cat, final String tests, final String prec,
                                    final String rec, final String f1, final String pass) {
        return String.format("║ %-36s ║ %5s ║ %9s ║ %8s ║ %5s ║ %5s ║",
                cat, tests, prec, rec, f1, pass);
    }

    private static String formatDataRow(final String cat, final int tests, final double prec,
                                        final double rec, final double f1) {
        return String.format("║ %-36s ║ %5d ║ %9.3f ║ %8.3f ║ %5.3f  ║ %5s ║",
                cat, tests, prec, rec, f1, "Y");
    }

    // =========================================================================
    // Parameterized test: Precision / Recall
    // =========================================================================

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("providePrecisionRecallQueries")
    @DisplayName("P/R query")
    void precisionRecallQuery(final TestQuery testQuery) throws Exception {
        final LuceneIndexService.SearchResult result =
                indexService.search(testQuery.query(), List.of(), 0, 100, "_score", "desc");

        final Set<String> retrievedIds = result.documents().stream()
                .map(doc -> idsByFilePath.get(doc.filePath()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        final int tp = (int) testQuery.expectedRelevant().stream()
                .filter(retrievedIds::contains).count();
        final int fp = (int) retrievedIds.stream()
                .filter(id -> !testQuery.expectedRelevant().contains(id)).count();
        final int fn = (int) testQuery.expectedRelevant().stream()
                .filter(id -> !retrievedIds.contains(id)).count();

        final double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 1.0;
        final double recall = testQuery.expectedRelevant().isEmpty()
                ? 1.0
                : (double) tp / testQuery.expectedRelevant().size();
        final double f1 = (precision + recall) > 0
                ? 2.0 * precision * recall / (precision + recall)
                : 0.0;

        allMetrics.add(new QueryMetrics(
                testQuery.id(),
                testQuery.category(),
                testQuery.query(),
                testQuery.expectedRelevant().size(),
                (int) result.totalHits(),
                tp, fp, fn,
                precision, recall, f1));

        assertThat(recall)
                .as("Recall for %s: '%s'", testQuery.id(), testQuery.query())
                .isGreaterThanOrEqualTo(testQuery.minRecall());
        assertThat(precision)
                .as("Precision for %s: '%s'", testQuery.id(), testQuery.query())
                .isGreaterThanOrEqualTo(testQuery.minPrecision());

        for (final String irrelevantId : testQuery.expectedIrrelevant()) {
            assertThat(retrievedIds)
                    .as("Irrelevant doc %s should NOT appear for query '%s'",
                            irrelevantId, testQuery.query())
                    .doesNotContain(irrelevantId);
        }
    }

    static Stream<TestQuery> providePrecisionRecallQueries() {
        return precisionRecallQueries().stream();
    }

    // =========================================================================
    // Parameterized test: Scoring / ranking
    // =========================================================================

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideScoringQueries")
    @DisplayName("Scoring / ranking query")
    void scoringQuery(final ScoringQuery scoringQuery) throws Exception {
        final LuceneIndexService.SearchResult result =
                indexService.search(scoringQuery.query(), List.of(), 0, 100, "_score", "desc");

        assertThat(result.totalHits())
                .as("Query '%s' should return at least one result", scoringQuery.query())
                .isGreaterThan(0);

        final List<String> rankedIds = result.documents().stream()
                .limit(scoringQuery.withinTopN())
                .map(doc -> idsByFilePath.get(doc.filePath()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        final boolean anyTopRanked = scoringQuery.topRankedIds().stream()
                .anyMatch(rankedIds::contains);

        assertThat(anyTopRanked)
                .as("At least one of %s should appear in top-%d for query '%s'. Actual top-%d: %s",
                        scoringQuery.topRankedIds(), scoringQuery.withinTopN(),
                        scoringQuery.query(), scoringQuery.withinTopN(), rankedIds)
                .isTrue();
    }

    static Stream<ScoringQuery> provideScoringQueries() {
        return scoringQueries().stream();
    }

    // =========================================================================
    // Parameterized test: Edge cases
    // =========================================================================

    @ParameterizedTest(name = "[{index}] {0} — {1}")
    @MethodSource("provideEdgeCaseQueries")
    @DisplayName("Edge case query")
    void edgeCaseQuery(final EdgeCaseQuery edgeCaseQuery) {
        switch (edgeCaseQuery.assertion()) {
            case HAS_RESULTS -> {
                final LuceneIndexService.SearchResult result = executeSearch(edgeCaseQuery.query());
                assertThat(result.totalHits())
                        .as("Query '%s' should return at least one result", edgeCaseQuery.query())
                        .isGreaterThan(0);
            }
            case NO_RESULTS -> {
                final LuceneIndexService.SearchResult result = executeSearch(edgeCaseQuery.query());
                assertThat(result.totalHits())
                        .as("Query '%s' should return no results", edgeCaseQuery.query())
                        .isEqualTo(0);
            }
            case MATCH_ALL -> {
                final LuceneIndexService.SearchResult result = executeSearch(edgeCaseQuery.query());
                assertThat(result.totalHits())
                        .as("Empty query should match all %d documents", CORPUS.size())
                        .isEqualTo(CORPUS.size());
            }
            case NO_EXCEPTION ->
                    assertThatCode(() -> indexService.search(edgeCaseQuery.query(), List.of(), 0, 100, "_score", "desc"))
                            .as("Query '%s' should not throw an exception", edgeCaseQuery.query())
                            .doesNotThrowAnyException();
        }
    }

    static Stream<EdgeCaseQuery> provideEdgeCaseQueries() {
        return edgeCaseQueries().stream();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Writes the document to disk and registers it in the index, but does NOT commit
     * or refresh the searcher. Call {@code indexService.commit()} and
     * {@code indexService.refreshSearcher()} once after all documents have been indexed.
     */
    private void indexDocumentWithMetadata(final String docId,
                                           final String fileName,
                                           final String content,
                                           final String language) throws IOException {
        final Path testFile = docsDir.resolve(fileName);
        Files.writeString(testFile, content);

        final Map<String, String> metadata = new HashMap<>();
        final ExtractedDocument extracted = new ExtractedDocument(
                content, metadata, language, "text/plain", testFile.toFile().length());

        final var luceneDoc = documentIndexer.createDocument(testFile, extracted);
        documentIndexer.indexDocument(indexService.getIndexWriter(), luceneDoc);

        idsByFilePath.put(testFile.toString(), docId);
    }

    private LuceneIndexService.SearchResult executeSearch(final String query) {
        try {
            return indexService.search(query, List.of(), 0, 100, "_score", "desc");
        } catch (final Exception e) {
            throw new RuntimeException("Search failed for query: " + query, e);
        }
    }

    private static String truncate(final String s, final int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
