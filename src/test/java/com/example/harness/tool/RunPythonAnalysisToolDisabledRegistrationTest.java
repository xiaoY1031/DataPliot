package com.example.harness.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "datapilot.python.enabled=false")
class RunPythonAnalysisToolDisabledRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void doesNotRegisterPythonToolWhenDisabled() {
        assertTrue(toolRegistry.find("run_python_analysis").isEmpty());
    }
}
