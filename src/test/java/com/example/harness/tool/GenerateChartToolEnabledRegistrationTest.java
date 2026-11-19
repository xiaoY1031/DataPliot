package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {"datapilot.chart.enabled=true", "datapilot.python.executable="})
class GenerateChartToolEnabledRegistrationTest {
    @Autowired ToolRegistry toolRegistry;
    @Test void registersChartToolEvenWhenInterpreterNeedsConfiguration() {
        assertInstanceOf(GenerateChartTool.class, toolRegistry.find("generate_chart").orElseThrow());
    }
}
