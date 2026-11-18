package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SearchSchemaToolRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void autoRegistersSearchSchemaTool() {
        assertInstanceOf(
                SearchSchemaTool.class,
                toolRegistry.find("search_schema").orElseThrow());
        assertTrue(toolRegistry.listDefinitions().stream()
                .anyMatch(definition -> "search_schema".equals(definition.name())));
    }
}
