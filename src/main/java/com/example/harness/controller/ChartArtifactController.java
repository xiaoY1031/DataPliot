package com.example.harness.controller;

import com.example.harness.tool.ChartArtifactStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/** 只读返回已生成且未过期的 PNG 图表，不提供目录枚举。 */
@RestController
@RequestMapping("/api/artifacts/charts")
@ConditionalOnProperty(prefix = "datapilot.chart", name = "enabled", havingValue = "true")
public class ChartArtifactController {

    private final ChartArtifactStore artifactStore;

    public ChartArtifactController(ChartArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> get(@PathVariable String artifactId) {
        return artifactStore.read(artifactId)
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .contentLength(content.bytes().length)
                        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + content.artifact().id() + ".png\"")
                        .body(content.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
