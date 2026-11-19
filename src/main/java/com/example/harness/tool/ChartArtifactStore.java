package com.example.harness.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 将已验证 PNG 保存为不可枚举的临时 artifact，并按时间和数量清理。 */
@Component
@ConditionalOnProperty(prefix = "datapilot.chart", name = "enabled", havingValue = "true")
public class ChartArtifactStore {

    private final ChartGenerationProperties properties;
    private final Path root;
    private final ConcurrentMap<String, ChartArtifact> artifacts = new ConcurrentHashMap<>();

    public ChartArtifactStore(ChartGenerationProperties properties) {
        this.properties = properties;
        this.root = resolveRoot(properties.getOutputDirectory());
        cleanup();
    }

    public synchronized ChartArtifact save(byte[] png, int width, int height)
            throws ArtifactStoreException {
        validateConfiguration();
        if (png == null || png.length == 0 || png.length > properties.getMaxImageBytes()) {
            throw new ArtifactStoreException("CHART_OUTPUT_TOO_LARGE", "图表文件为空或超过大小限制。");
        }
        cleanup();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ArtifactStoreException(
                    "CHART_ARTIFACT_WRITE_FAILED", "无法创建图表 artifact 目录。");
        }
        String id = UUID.randomUUID().toString();
        Path target = root.resolve(id + ".png");
        Path temporary = root.resolve(id + ".tmp");
        try {
            Files.write(temporary, png);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Instant now = Instant.now();
            ChartArtifact artifact = new ChartArtifact(
                    id, target, width, height, png.length, now,
                    now.plus(Duration.ofHours(properties.getRetentionHours())));
            artifacts.put(id, artifact);
            enforceMaxArtifacts();
            return artifact;
        } catch (IOException e) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw new ArtifactStoreException("CHART_ARTIFACT_WRITE_FAILED", "图表 artifact 保存失败。");
        }
    }

    public Optional<ChartArtifactContent> read(String id) {
        if (!validArtifactId(id)) {
            return Optional.empty();
        }
        cleanup();
        ChartArtifact artifact = artifacts.get(id);
        if (artifact == null || artifact.expiresAt().isBefore(Instant.now())) {
            remove(id, artifact);
            return Optional.empty();
        }
        try {
            long size = Files.size(artifact.path());
            if (size <= 0 || size > properties.getMaxImageBytes()) {
                remove(id, artifact);
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(artifact.path());
            if (bytes.length > properties.getMaxImageBytes()) {
                remove(id, artifact);
                return Optional.empty();
            }
            return Optional.of(new ChartArtifactContent(artifact, bytes));
        } catch (IOException e) {
            remove(id, artifact);
            return Optional.empty();
        }
    }

    synchronized void cleanup() {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant now = Instant.now();
        artifacts.forEach((id, artifact) -> {
            if (artifact.expiresAt().isBefore(now) || !Files.isRegularFile(artifact.path())) {
                remove(id, artifact);
            }
        });
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(this::managedArtifactFile)
                    .forEach(path -> {
                String name = path.getFileName().toString();
                boolean referenced = artifacts.values().stream().anyMatch(a -> a.path().equals(path));
                try {
                    Instant modified = Files.getLastModifiedTime(path).toInstant();
                    if (!referenced && modified.plus(Duration.ofHours(properties.getRetentionHours())).isBefore(now)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                }
                    });
        } catch (IOException ignored) {
        }
        enforceMaxArtifacts();
    }

    private void enforceMaxArtifacts() {
        if (properties.getMaxArtifacts() <= 0) {
            return;
        }
        artifacts.values().stream()
                .sorted(Comparator.comparing(ChartArtifact::createdAt))
                .limit(Math.max(0, artifacts.size() - properties.getMaxArtifacts()))
                .toList()
                .forEach(artifact -> remove(artifact.id(), artifact));
    }

    private void validateConfiguration() throws ArtifactStoreException {
        if (properties.getRetentionHours() <= 0 || properties.getMaxArtifacts() <= 0) {
            throw new ArtifactStoreException(
                    "CHART_CONFIGURATION_INVALID", "图表保留时间和最大 artifact 数量必须大于 0。");
        }
    }

    private void remove(String id, ChartArtifact artifact) {
        if (artifact != null) {
            artifacts.remove(id, artifact);
            deleteQuietly(artifact.path());
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null || !path.normalize().getParent().equals(root)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private Path resolveRoot(String configured) {
        Path value = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "datapilot", "chart-artifacts")
                : Path.of(configured);
        return value.toAbsolutePath().normalize();
    }

    private boolean validArtifactId(String id) {
        if (id == null) {
            return false;
        }
        try {
            return UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean managedArtifactFile(Path path) {
        String name = path.getFileName().toString();
        if (!(name.endsWith(".png") || name.endsWith(".tmp"))) {
            return false;
        }
        return validArtifactId(name.substring(0, name.length() - 4));
    }

    public record ChartArtifact(
            String id, Path path, int width, int height, long sizeBytes,
            Instant createdAt, Instant expiresAt) {
    }

    public record ChartArtifactContent(ChartArtifact artifact, byte[] bytes) {
        public ChartArtifactContent { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static class ArtifactStoreException extends Exception {
        private final String errorCode;
        public ArtifactStoreException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }
}
