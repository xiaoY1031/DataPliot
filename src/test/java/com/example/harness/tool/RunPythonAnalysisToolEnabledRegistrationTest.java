package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "datapilot.python.enabled=true",
        "datapilot.python.executable="
})
class RunPythonAnalysisToolEnabledRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void registersPythonToolEvenWhenInterpreterNeedsConfiguration() {
        assertInstanceOf(
                RunPythonAnalysisTool.class,
                toolRegistry.find("run_python_analysis").orElseThrow());
    }
}
