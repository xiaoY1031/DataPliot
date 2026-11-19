package com.example.harness.tool;

/** Python 进程启动、通信和资源治理层的通用错误。 */
public class PythonProcessException extends Exception {

    private final String errorCode;

    public PythonProcessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
