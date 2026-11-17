package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SampleTableDataToolRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void autoRegistersSampleTableDataTool() {
        assertInstanceOf(SampleTableDataTool.class,
                toolRegistry.find("sample_table_data").orElseThrow());
        assertTrue(toolRegistry.listDefinitions().stream()
                .anyMatch(definition -> "sample_table_data".equals(definition.name())));
    }
}
