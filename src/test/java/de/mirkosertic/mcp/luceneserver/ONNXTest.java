package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.onnx.ONNXService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

public class ONNXTest {

    private static GenericContainer<?> onnxContainer;

    @BeforeAll
    static void setupOnnxModels() throws IOException {
        final Path targetDir = Paths.get("target/onnx-temp").toAbsolutePath();
        final Path cachedModel = targetDir.resolve("e5-base/model_quantized.onnx");

        if (!Files.exists(cachedModel)) {
            Files.createDirectories(targetDir);

            onnxContainer = new GenericContainer<>("mirkosertic42/mcpluceneserver_onnx:main")
                    .withFileSystemBind(targetDir.toString(), "/onnxmodels_dst", BindMode.READ_WRITE)
                    .withCreateContainerCmdModifier(cmd -> cmd
                            .withEntrypoint("sh", "-c")
                            .withCmd("cp -R /onnxmodels/* /onnxmodels_dst && echo COPY_COMPLETE && tail -f /dev/null"))
                    .waitingFor(Wait.forLogMessage(".*COPY_COMPLETE.*", 1).withStartupTimeout(Duration.ofMinutes(10)));

            onnxContainer.start();
            onnxContainer.stop();
        }

        System.setProperty("onnx.models.path", targetDir.toString());
    }

    @AfterAll
    static void tearDown() {
        if (onnxContainer != null && onnxContainer.isRunning()) {
            onnxContainer.stop();
        }
    }

    @Test
    void testEncode() throws Exception {
        try (final ONNXService svc = new ONNXService("e5-base")) {

            final String testSatz = "Mirko entwickelt ein LEGO Powered Up Hub auf ESP32-Basis.";
            final long start = System.currentTimeMillis();
            final float[] embedding = svc.embed(testSatz, ONNXService.QUERY_PREFIX);
            final long elapsed = System.currentTimeMillis() - start;

            System.out.println("\nText:      \"" + testSatz + "\"");
            System.out.println("Dimensionen: " + embedding.length);
            System.out.printf("Latenz:     %d ms%n", elapsed);
            System.out.printf("Norm:       %.6f  (sollte ~1.0 sein)%n",
                    norm(embedding));
            System.out.print("Erste 8 Werte: [");
            for (int i = 0; i < 8; i++) {
                System.out.printf("%.4f%s", embedding[i], i < 7 ? ", " : "");
            }
            System.out.println("]");

            // Ähnlichkeitstest: zwei ähnliche Sätze
            final float[] e1 = svc.embed("Der ESP32 ist ein Mikrocontroller mit WLAN.", ONNXService.QUERY_PREFIX);
            final float[] e2 = svc.embed("WLAN-fähiger Mikrocontroller für IoT-Projekte.", ONNXService.PASSAGE_PREFIX);
            final float[] e3 = svc.embed("Das Wetter in München ist heute sonnig.", ONNXService.PASSAGE_PREFIX);
            System.out.printf("%nCosine-Ähnlichkeit (ähnlich): %.4f%n", cosine(e1, e2));
            System.out.printf("Cosine-Ähnlichkeit (unähnlich): %.4f%n", cosine(e1, e3));

            // Load test
            final int max = 100;
            System.out.println("Load test...");
            final long startLoad = System.currentTimeMillis();
            for (int i = 0; i < max; i++) {
                svc.embed(testSatz, ONNXService.QUERY_PREFIX);
            }
            final long duration = System.currentTimeMillis() - startLoad;
            System.out.printf("Laufzeit: %d ms / %d Embeddings / %f avg ms%n", duration, max, (duration) / (double) max);
        }
    }

    @Test
    void testLateChunking() {
        try (final ONNXService svc = new ONNXService("e5-base")) {

            final String testInhalt = "Mirko entwickelt ein LEGO Powered Up Hub auf ESP32-Basis.";
            final long start = System.currentTimeMillis();
            final List<float[]> embedding = svc.embedWithLateChunking(testInhalt, ONNXService.QUERY_PREFIX, ONNXService.DEFAULT_BATCH_SIZE);
            final long elapsed = System.currentTimeMillis() - start;

            System.out.println("\nText:      \"" + testInhalt + "\"");

            System.out.printf("Latenz:     %d ms%n", elapsed);
            System.out.println("Anzahl der Chunks : " + embedding.size());

        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

    }

    private static double norm(final float[] v) {
        double sum = 0;
        for (final float x : v) sum += (double) x * x;
        return Math.sqrt(sum);
    }

    private static float cosine(final float[] a, final float[] b) {
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // nach L2-Normalisierung = direkt Cosine
    }
}
