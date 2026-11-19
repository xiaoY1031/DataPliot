package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartArtifactStoreTest {

    private final Path temporaryDirectory = Path.of(
            "target", "chart-artifact-test-" + UUID.randomUUID()).toAbsolutePath();

    @AfterEach
    void cleanup() throws Exception {
        if (!Files.exists(temporaryDirectory)) {
            return;
        }
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void savesAndReadsArtifactByOpaqueId() throws Exception {
        ChartGenerationProperties properties = new ChartGenerationProperties();
        properties.setOutputDirectory(temporaryDirectory.toString());
        ChartArtifactStore store = new ChartArtifactStore(properties);
        byte[] bytes = new byte[]{1, 2, 3};

        ChartArtifactStore.ChartArtifact artifact = store.save(bytes, 100, 50);

        assertTrue(artifact.id().matches("[0-9a-f-]{36}"));
        assertArrayEquals(bytes, store.read(artifact.id()).orElseThrow().bytes());
        assertTrue(store.read("../secret").isEmpty());
    }

    @Test
    void cleanupNeverDeletesUnrelatedFilesFromConfiguredDirectory() throws Exception {
        Files.createDirectories(temporaryDirectory);
        Path unrelated = temporaryDirectory.resolve("business-report.txt");
        Files.writeString(unrelated, "keep");
        ChartGenerationProperties properties = new ChartGenerationProperties();
        properties.setOutputDirectory(temporaryDirectory.toString());
        properties.setRetentionHours(1);

        ChartArtifactStore store = new ChartArtifactStore(properties);
        store.cleanup();

        assertTrue(Files.exists(unrelated));
    }
}
