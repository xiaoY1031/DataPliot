package com.example.harness.controller;

import com.example.harness.tool.ChartArtifactStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChartArtifactControllerTest {

    @Test
    void servesPngWithSafeHeadersAndReturnsNotFoundForUnknownArtifact() throws Exception {
        ChartArtifactStore store = mock(ChartArtifactStore.class);
        String id = "123e4567-e89b-12d3-a456-426614174000";
        byte[] png = new byte[]{1, 2, 3};
        Instant now = Instant.now();
        ChartArtifactStore.ChartArtifact artifact = new ChartArtifactStore.ChartArtifact(
                id, Path.of("chart.png"), 1200, 720, png.length, now, now.plusSeconds(3600));
        when(store.read(id)).thenReturn(Optional.of(
                new ChartArtifactStore.ChartArtifactContent(artifact, png)));
        when(store.read("missing")).thenReturn(Optional.empty());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChartArtifactController(store))
                .build();

        mockMvc.perform(get("/api/artifacts/charts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + id + ".png\""));

        mockMvc.perform(get("/api/artifacts/charts/missing"))
                .andExpect(status().isNotFound());
    }
}
