package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/** 执行经过 AST 安全校验的单条只读 SELECT 查询。 */
@Component
public class ExecuteReadonlySqlTool implements AgentTool {

    static final int MAX_ROWS = 100;
    static final int QUERY_TIMEOUT_SECONDS = 10;
    static final int MAX_TEXT_LENGTH = DatabaseResultSetMapper.MAX_FIELD_LENGTH;
    static final int MAX_SQL_LENGTH = ReadonlySqlValidator.MAX_SQL_LENGTH;
    static final int DEFAULT_MAX_RESULT_BYTES = 262_144;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "execute_readonly_sql",
            "在当前配置的 MySQL 数据库中执行一条只读 SELECT 查询；最多返回 100 行，禁止跨库、写入、锁定和多语句",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "sql", Map.of(
                                    "type", "string",
                                    "description", "需要执行的单条只读 SELECT 或最终返回 SELECT 的 WITH 查询")),
                    "required", List.of("sql"),
                    "additionalProperties", false));
    private final ReadonlySqlValidator sqlValidator;
    private final ReadonlyQueryExecutor queryExecutor;

    public ExecuteReadonlySqlTool(DataSource dataSource, ObjectMapper objectMapper) {
        this(
                dataSource,
                objectMapper,
                SensitiveFieldPolicy.none(),
                new DatabaseResultSetMapper(objectMapper, DEFAULT_MAX_RESULT_BYTES));
    }

    @Autowired
    public ExecuteReadonlySqlTool(
            DataSource dataSource,
            ObjectMapper objectMapper,
            SensitiveFieldPolicy sensitiveFieldPolicy,
            DatabaseResultSetMapper resultSetMapper) {
        this.sqlValidator = new ReadonlySqlValidator(sensitiveFieldPolicy);
        this.queryExecutor = new ReadonlyQueryExecutor(
                dataSource,
                sqlValidator,
                resultSetMapper,
                objectMapper);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String sql = invocation.arguments().path("sql").asText(null);
        ReadonlySqlValidator.ValidatedQuery query;
        try {
            query = sqlValidator.validate(sql);
        } catch (ReadonlySqlValidator.SqlValidationException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        }
        return queryExecutor.executeForTool(query, MAX_ROWS, QUERY_TIMEOUT_SECONDS);
    }

}
