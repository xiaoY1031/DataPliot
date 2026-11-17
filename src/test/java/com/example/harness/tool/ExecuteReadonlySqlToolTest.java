package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecuteReadonlySqlToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    private final ExecuteReadonlySqlTool tool = new ExecuteReadonlySqlTool(dataSource, objectMapper);
    private final ToolRegistry registry = new ToolRegistry(List.of(tool));
    private final ToolExecutor executor = new ToolExecutor(registry, objectMapper);

    @Test
    void definesRequiredSqlParameterAndExposesToolThroughRegistry() {
        ToolDefinition definition = tool.definition();

        assertEquals("execute_readonly_sql", definition.name());
        assertFalse(definition.description().isBlank());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(List.of("sql"), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals("string", ((Map<?, ?>) properties.get("sql")).get("type"));
        assertEquals(List.of(definition), registry.listDefinitions());
    }

    @Test
    void executesSelectWithWhereAndReturnsStructuredRows() throws Exception {
        prepareQuery();
        defineColumns(
                new Column("id", Types.BIGINT, "BIGINT"),
                new Column("title", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getObject(1)).thenReturn(10L, 11L);
        when(resultSet.getCharacterStream(2))
                .thenReturn(new StringReader("第一篇"))
                .thenReturn(null);

        ToolResult result = execute("SELECT id, title FROM post WHERE status = 1 ORDER BY id");

        assertTrue(result.success(), result.toString());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(2, content.path("rowCount").asInt());
        assertFalse(content.path("truncated").asBoolean());
        assertEquals(0, content.path("truncationReasons").size());
        assertEquals("id", content.path("columns").get(0).path("name").asText());
        assertEquals("BIGINT", content.path("columns").get(0).path("type").asText());
        assertEquals(10L, content.path("rows").get(0).path("id").asLong());
        assertEquals("第一篇", content.path("rows").get(0).path("title").asText());
        assertTrue(content.path("rows").get(1).path("title").isNull());
        verify(connection).setReadOnly(true);
        verify(statement).setQueryTimeout(ExecuteReadonlySqlTool.QUERY_TIMEOUT_SECONDS);
        verify(statement).setMaxRows(ExecuteReadonlySqlTool.MAX_ROWS + 1);
        verify(statement).setFetchSize(ExecuteReadonlySqlTool.MAX_ROWS + 1);
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void supportsAggregateValuesAndConvertsCommonJdbcTypes() throws Exception {
        prepareQuery();
        defineColumns(
                new Column("total", Types.BIGINT, "BIGINT"),
                new Column("amount", Types.DECIMAL, "DECIMAL"),
                new Column("average_score", Types.DECIMAL, "DECIMAL"),
                new Column("latest_time", Types.TIMESTAMP, "TIMESTAMP"));
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(125L);
        when(resultSet.getObject(2)).thenReturn(new BigDecimal("1024.50"));
        when(resultSet.getObject(3)).thenReturn(new BigDecimal("4.75"));
        when(resultSet.getObject(4)).thenReturn(Timestamp.valueOf("2026-11-13 12:30:45"));

        ToolResult result = execute(
                "SELECT COUNT(*) AS total, SUM(amount) AS amount, "
                        + "AVG(score) AS average_score, MAX(created_at) AS latest_time FROM post");

        assertTrue(result.success(), result.toString());
        JsonNode row = objectMapper.readTree(result.content()).path("rows").get(0);
        assertEquals(125L, row.path("total").asLong());
        assertEquals(0, new BigDecimal("1024.50").compareTo(row.path("amount").decimalValue()));
        assertEquals(0, new BigDecimal("4.75").compareTo(row.path("average_score").decimalValue()));
        assertEquals("2026-11-13T12:30:45", row.path("latest_time").asText());
    }

    @Test
    void supportsJoinQuery() throws Exception {
        prepareEmptyQuery();

        ToolResult result = execute(
                "SELECT p.id, c.name FROM post p "
                        + "JOIN post_category c ON c.id = p.category_id WHERE p.status = 1");

        assertTrue(result.success(), result.toString());
        verify(statement).executeQuery(anyString());
    }

    @Test
    void supportsReadonlyCteQuery() throws Exception {
        prepareEmptyQuery();

        ToolResult result = execute(
                "WITH recent AS (SELECT id, category_id FROM post WHERE status = 1) "
                        + "SELECT category_id, COUNT(*) AS total FROM recent GROUP BY category_id");

        assertTrue(result.success(), result.toString());
        verify(statement).executeQuery(anyString());
    }

    @Test
    void rejectsNonSelectStatementsBeforeOpeningConnection() {
        for (String sql : List.of(
                "INSERT INTO post(title) VALUES ('x')",
                "UPDATE post SET title = 'x'",
                "DELETE FROM post",
                "DROP TABLE post",
                "ALTER TABLE post ADD COLUMN x INT",
                "CREATE TABLE x(id INT)",
                "TRUNCATE TABLE post")) {
            ToolResult result = execute(sql);
            assertFalse(result.success(), sql);
            assertEquals("SQL_SAFETY_REJECTED", result.errorCode(), sql);
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsMultipleStatements() {
        ToolResult result = execute("SELECT * FROM post; DELETE FROM post");

        assertEquals("SQL_SAFETY_REJECTED", result.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsSelectIntoOutfileAndVariableWrites() {
        ToolResult outfile = execute("SELECT * FROM post INTO OUTFILE '/tmp/post.txt'");
        ToolResult dumpfile = execute("SELECT content FROM post INTO DUMPFILE '/tmp/post.bin'");
        ToolResult variable = execute("SELECT COUNT(*) INTO @total FROM post");

        assertEquals("SQL_SAFETY_REJECTED", outfile.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", dumpfile.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", variable.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsLockingQueries() {
        ToolResult forUpdate = execute("SELECT * FROM post FOR UPDATE");
        ToolResult shareMode = execute("SELECT * FROM post LOCK IN SHARE MODE");

        assertEquals("SQL_SAFETY_REJECTED", forUpdate.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", shareMode.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsCrossDatabaseReferences() throws Exception {
        prepareConnectionOnly();

        ToolResult result = execute("SELECT * FROM other_database.post");

        assertEquals("CROSS_DATABASE_ACCESS_REJECTED", result.errorCode());
        verify(connection, never()).createStatement();
        verify(connection).close();
    }

    @Test
    void permitsExplicitReferenceToCurrentDatabase() throws Exception {
        prepareEmptyQuery();

        ToolResult result = execute("SELECT COUNT(*) AS total FROM datapilot.post");

        assertTrue(result.success(), result.toString());
    }

    @Test
    void rejectsUnparseableSqlAndDangerousFunctions() {
        ToolResult invalid = execute("SELECT FROM WHERE");
        ToolResult fileRead = execute("SELECT LOAD_FILE('/etc/passwd')");
        ToolResult sleep = execute("SELECT SLEEP(30)");
        ToolResult lock = execute("SELECT GET_LOCK('agent', 10)");

        assertEquals("SQL_PARSE_ERROR", invalid.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", fileRead.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", sleep.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", lock.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsExecutableCommentsSessionVariablesAndUnknownFunctions() {
        ToolResult executableComment = execute("SELECT 1 /*!50000 UNION SELECT 2 */");
        ToolResult optimizerHint = execute("SELECT /*+ MAX_EXECUTION_TIME(60000) */ * FROM post");
        ToolResult sessionVariable = execute("SELECT @current_value");
        ToolResult unknownFunction = execute("SELECT custom_business_function(id) FROM post");

        assertEquals("SQL_SAFETY_REJECTED", executableComment.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", optimizerHint.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", sessionVariable.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", unknownFunction.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void doesNotTreatKeywordsInsideStringsOrOrdinaryCommentsAsOperations() throws Exception {
        prepareQuery();
        defineColumns(new Column("value", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getCharacterStream(1)).thenReturn(
                new StringReader("FOR UPDATE @value INTO OUTFILE"));

        ToolResult result = execute(
                "SELECT 'FOR UPDATE @value INTO OUTFILE' AS value /* ordinary comment */");

        assertTrue(result.success(), result.toString());
    }

    @Test
    void rejectsMissingWrongTypeAndUnexpectedArguments() {
        ToolResult missing = executor.execute("call_1", "execute_readonly_sql", "{}");
        ToolResult wrongType = executor.execute("call_2", "execute_readonly_sql", "{\"sql\":1}");
        ToolResult extra = executor.execute("call_3", "execute_readonly_sql",
                "{\"sql\":\"SELECT 1\",\"url\":\"jdbc:mysql://other\"}");

        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertEquals("INVALID_ARGUMENTS", wrongType.errorCode());
        assertEquals("INVALID_ARGUMENTS", extra.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void returnsAtMostOneHundredRowsAndMarksTruncation() throws Exception {
        prepareQuery();
        defineColumns(new Column("id", Types.INTEGER, "INT"));
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicInteger value = new AtomicInteger(1);
        when(resultSet.next()).thenAnswer(invocation -> nextCalls.getAndIncrement() < 101);
        when(resultSet.getObject(1)).thenAnswer(invocation -> value.getAndIncrement());

        ToolResult result = execute("SELECT id FROM post ORDER BY id");

        assertTrue(result.success());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(100, content.path("rowCount").asInt());
        assertEquals(100, content.path("rows").size());
        assertTrue(content.path("truncated").asBoolean());
        assertEquals("ROW_LIMIT", content.path("truncationReasons").get(0).asText());
        verify(statement).setMaxRows(101);
    }

    @Test
    void truncatesLongTextAndHandlesBinaryAndNullValues() throws Exception {
        prepareQuery();
        defineColumns(
                new Column("content", Types.LONGVARCHAR, "TEXT"),
                new Column("payload", Types.VARBINARY, "VARBINARY"),
                new Column("optional_value", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getCharacterStream(1)).thenReturn(
                new StringReader("x".repeat(ExecuteReadonlySqlTool.MAX_TEXT_LENGTH + 10)));
        when(resultSet.getBinaryStream(2)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(resultSet.getCharacterStream(3)).thenReturn(null);

        ToolResult result = execute("SELECT content, payload, optional_value FROM post LIMIT 1");

        JsonNode row = objectMapper.readTree(result.content()).path("rows").get(0);
        assertTrue(row.path("content").asText().endsWith("…[已截断]"));
        assertEquals("AQID", row.path("payload").asText());
        assertTrue(row.path("optional_value").isNull());
        JsonNode content = objectMapper.readTree(result.content());
        assertTrue(content.path("truncated").asBoolean());
        assertEquals("FIELD_LENGTH_LIMIT",
                content.path("truncationReasons").get(0).asText());
    }

    @Test
    void limitsSerializedUtf8ResultSizeWithoutLoadingRemainingRows() throws Exception {
        ExecuteReadonlySqlTool limitedTool = toolWithPolicy(360, Set.of(), Set.of());
        prepareQuery();
        defineColumns(new Column("content", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getCharacterStream(1))
                .thenReturn(new StringReader("中".repeat(70)))
                .thenReturn(new StringReader("文".repeat(70)));

        ToolResult result = execute(limitedTool, "SELECT content FROM post");

        JsonNode content = objectMapper.readTree(result.content());
        assertTrue(result.success(), result.toString());
        assertTrue(content.path("truncated").asBoolean());
        assertTrue(containsReason(content, "RESULT_SIZE_LIMIT"));
        assertTrue(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 360);
        assertTrue(content.path("rowCount").asInt() < 3);
        verify(resultSet, org.mockito.Mockito.atMost(2)).next();
    }

    @Test
    void returnsLegalEmptyRowsWhenFirstRowExceedsResultBudget() throws Exception {
        ExecuteReadonlySqlTool limitedTool = toolWithPolicy(190, Set.of(), Set.of());
        prepareQuery();
        defineColumns(new Column("content", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getCharacterStream(1)).thenReturn(new StringReader("x".repeat(150)));

        ToolResult result = execute(limitedTool, "SELECT content FROM post");

        JsonNode content = objectMapper.readTree(result.content());
        assertTrue(result.success(), result.toString());
        assertEquals(0, content.path("rowCount").asInt());
        assertEquals(0, content.path("rows").size());
        assertTrue(containsReason(content, "RESULT_SIZE_LIMIT"));
        assertTrue(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 190);
    }

    @Test
    void reportsMultipleTruncationReasonsOnlyOnce() throws Exception {
        ExecuteReadonlySqlTool limitedTool = toolWithPolicy(420, Set.of(), Set.of());
        prepareQuery();
        defineColumns(new Column("content", Types.VARCHAR, "VARCHAR"));
        AtomicInteger calls = new AtomicInteger();
        when(resultSet.next()).thenAnswer(invocation -> calls.getAndIncrement() < 101);
        when(resultSet.getCharacterStream(1)).thenReturn(
                new StringReader("x".repeat(ExecuteReadonlySqlTool.MAX_TEXT_LENGTH + 20)));

        ToolResult result = execute(limitedTool, "SELECT content FROM post");

        JsonNode content = objectMapper.readTree(result.content());
        assertTrue(content.path("truncated").asBoolean());
        assertTrue(containsReason(content, "FIELD_LENGTH_LIMIT"));
        assertTrue(containsReason(content, "RESULT_SIZE_LIMIT"));
        assertEquals(content.path("truncationReasons").size(),
                java.util.stream.StreamSupport.stream(
                                content.path("truncationReasons").spliterator(), false)
                        .map(JsonNode::asText)
                        .distinct()
                        .count());
    }

    @Test
    void returnsStableEmptyResultWithoutTruncation() throws Exception {
        prepareEmptyQuery();

        ToolResult result = execute("SELECT id FROM post WHERE 1 = 0");

        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(0, content.path("rowCount").asInt());
        assertEquals(0, content.path("rows").size());
        assertFalse(content.path("truncated").asBoolean());
        assertEquals(0, content.path("truncationReasons").size());
    }

    @Test
    void rejectsDeniedSensitiveFieldsIncludingAliasesTableAliasesAndJoins() throws Exception {
        ExecuteReadonlySqlTool protectedTool = toolWithPolicy(
                ExecuteReadonlySqlTool.DEFAULT_MAX_RESULT_BYTES,
                Set.of("password"),
                Set.of());

        for (String sql : List.of(
                "SELECT password FROM admin",
                "SELECT password AS value FROM admin",
                "SELECT a.password AS value FROM admin a",
                "SELECT a.id, u.password FROM admin a JOIN user u ON u.id = a.user_id")) {
            ToolResult result = execute(protectedTool, sql);
            assertEquals("SQL_SAFETY_REJECTED", result.errorCode(), sql);
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void protectsSensitiveFieldsThroughSubqueriesAndCtes() throws Exception {
        ExecuteReadonlySqlTool protectedTool = toolWithPolicy(
                ExecuteReadonlySqlTool.DEFAULT_MAX_RESULT_BYTES,
                Set.of("password"),
                Set.of());

        ToolResult subquery = execute(protectedTool,
                "SELECT x.value FROM (SELECT password AS value FROM admin) x");
        ToolResult cte = execute(protectedTool,
                "WITH secured AS (SELECT password AS value FROM admin) SELECT value FROM secured");
        ToolResult renamedCte = execute(protectedTool,
                "WITH secured(value) AS (SELECT password FROM admin) SELECT value FROM secured");

        assertEquals("SQL_SAFETY_REJECTED", subquery.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", cte.errorCode());
        assertEquals("SQL_SAFETY_REJECTED", renamedCte.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void permitsCountButRejectsAggregatesThatCanReturnSensitiveValues() throws Exception {
        ExecuteReadonlySqlTool protectedTool = toolWithPolicy(
                ExecuteReadonlySqlTool.DEFAULT_MAX_RESULT_BYTES,
                Set.of("password"),
                Set.of());
        prepareQuery();
        defineColumns(new Column("total", Types.BIGINT, "BIGINT"));
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(1L);

        ToolResult count = execute(protectedTool, "SELECT COUNT(password) AS total FROM admin");
        ToolResult max = execute(protectedTool, "SELECT MAX(password) AS value FROM admin");

        assertTrue(count.success(), count.toString());
        assertEquals("SQL_SAFETY_REJECTED", max.errorCode());
    }

    @Test
    void masksConfirmedSourceColumnAndDenyWinsOnConfigurationConflict() throws Exception {
        ExecuteReadonlySqlTool maskingTool = toolWithPolicy(
                ExecuteReadonlySqlTool.DEFAULT_MAX_RESULT_BYTES,
                Set.of(),
                Set.of("phone"));
        prepareQuery();
        defineColumns(new Column("contact", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("13800138000");

        ToolResult masked = execute(maskingTool, "SELECT a.phone AS contact FROM admin a");

        assertTrue(masked.success(), masked.toString());
        assertEquals(SensitiveFieldPolicy.MASKED_VALUE,
                objectMapper.readTree(masked.content()).path("rows").get(0).path("contact").asText());

        ExecuteReadonlySqlTool conflictTool = toolWithPolicy(
                ExecuteReadonlySqlTool.DEFAULT_MAX_RESULT_BYTES,
                Set.of("phone"),
                Set.of("phone"));
        ToolResult conflict = execute(conflictTool, "SELECT phone FROM admin");
        assertEquals("SQL_SAFETY_REJECTED", conflict.errorCode());
    }

    @Test
    void ordinaryNonSensitiveFieldsRemainUnaffected() throws Exception {
        ExecuteReadonlySqlTool protectedTool = toolWithPolicy(
                ExecuteReadonlySqlTool.DEFAULT_MAX_RESULT_BYTES,
                Set.of("password"),
                Set.of("phone"));
        prepareQuery();
        defineColumns(new Column("name", Types.VARCHAR, "VARCHAR"));
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getCharacterStream(1)).thenReturn(new StringReader("Alice"));

        ToolResult result = execute(protectedTool, "SELECT name FROM admin");

        assertTrue(result.success(), result.toString());
        assertEquals("Alice", objectMapper.readTree(result.content())
                .path("rows").get(0).path("name").asText());
    }

    @Test
    void reportsTimeoutAndDatabaseFailuresWithoutLeakingSensitiveMessages() throws Exception {
        prepareConnectionOnly();
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(
                new SQLTimeoutException("jdbc:mysql://private password=secret"));

        ToolResult timeout = execute("SELECT * FROM post");

        assertEquals("QUERY_TIMEOUT", timeout.errorCode());
        assertFalse(timeout.toModelContent().contains("private"));
        assertFalse(timeout.toModelContent().contains("secret"));

        DataSource failedDataSource = mock(DataSource.class);
        when(failedDataSource.getConnection()).thenThrow(
                new SQLException("jdbc:mysql://private password=secret"));
        ToolExecutor failedExecutor = new ToolExecutor(
                new ToolRegistry(List.of(new ExecuteReadonlySqlTool(failedDataSource, objectMapper))),
                objectMapper);
        ToolResult connectionFailure = failedExecutor.execute(
                "call_failed", "execute_readonly_sql", "{\"sql\":\"SELECT 1\"}");

        assertEquals("DATABASE_CONNECTION_ERROR", connectionFailure.errorCode());
        assertFalse(connectionFailure.toModelContent().contains("private"));
        assertFalse(connectionFailure.toModelContent().contains("secret"));
    }

    @Test
    void reportsSqlExecutionFailureWithoutLeakingDriverDetails() throws Exception {
        prepareConnectionOnly();
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(
                new SQLException("jdbc:mysql://private user=root password=secret"));

        ToolResult result = execute("SELECT missing_column FROM post");

        assertEquals("SQL_EXECUTION_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("private"));
        assertFalse(result.toModelContent().contains("root"));
        assertFalse(result.toModelContent().contains("secret"));
    }

    @Test
    void reportsDatabaseSyntaxErrorsSeparately() throws Exception {
        prepareConnectionOnly();
        when(connection.createStatement()).thenReturn(statement);
        SQLException syntaxError = new SQLException("Unknown column password=secret", "42S22");
        when(statement.executeQuery(anyString())).thenThrow(syntaxError);

        ToolResult result = execute("SELECT missing_column FROM post");

        assertEquals("SQL_SYNTAX_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("secret"));
    }

    private ToolResult execute(String sql) {
        return execute(tool, sql);
    }

    private ToolResult execute(ExecuteReadonlySqlTool targetTool, String sql) {
        String arguments = objectMapper.createObjectNode().put("sql", sql).toString();
        ToolExecutor targetExecutor = targetTool == tool
                ? executor
                : new ToolExecutor(new ToolRegistry(List.of(targetTool)), objectMapper);
        return targetExecutor.execute("call_execute_sql", "execute_readonly_sql", arguments);
    }

    private ExecuteReadonlySqlTool toolWithPolicy(
            int maxResultBytes,
            Set<String> denied,
            Set<String> masked) {
        return new ExecuteReadonlySqlTool(
                dataSource,
                objectMapper,
                SensitiveFieldPolicy.of(denied, masked),
                new DatabaseResultSetMapper(objectMapper, maxResultBytes));
    }

    private boolean containsReason(JsonNode content, String reason) {
        return java.util.stream.StreamSupport.stream(
                        content.path("truncationReasons").spliterator(), false)
                .anyMatch(value -> reason.equals(value.asText()));
    }

    private void prepareConnectionOnly() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("datapilot");
    }

    private void prepareQuery() throws SQLException {
        prepareConnectionOnly();
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
    }

    private void prepareEmptyQuery() throws SQLException {
        prepareQuery();
        defineColumns(new Column("value", Types.BIGINT, "BIGINT"));
        when(resultSet.next()).thenReturn(false);
    }

    private void defineColumns(Column... columns) throws SQLException {
        when(metadata.getColumnCount()).thenReturn(columns.length);
        for (int index = 0; index < columns.length; index++) {
            int jdbcIndex = index + 1;
            Column column = columns[index];
            when(metadata.getColumnLabel(jdbcIndex)).thenReturn(column.name());
            when(metadata.getColumnType(jdbcIndex)).thenReturn(column.type());
            when(metadata.getColumnTypeName(jdbcIndex)).thenReturn(column.typeName());
        }
    }

    private record Column(String name, int type, String typeName) {
    }
}
