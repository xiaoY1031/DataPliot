package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SampleTableDataToolTest {

    private static final String CATALOG = "datapilot";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet tables = mock(ResultSet.class);
    private final ResultSet columns = mock(ResultSet.class);
    private final ResultSet primaryKeys = mock(ResultSet.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final ResultSetMetaData rowMetadata = mock(ResultSetMetaData.class);

    @Test
    void definesRestrictedSchema() {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 262_144);
        ToolDefinition definition = tool.definition();

        assertEquals("sample_table_data", definition.name());
        assertEquals(List.of("table_name"), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals(Set.of("table_name", "limit"), properties.keySet());
        Map<?, ?> limit = (Map<?, ?>) properties.get("limit");
        assertEquals(1, limit.get("minimum"));
        assertEquals(10, limit.get("maximum"));
    }

    @Test
    void usesDefaultLimitAndReturnsObjectRows() throws Exception {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 262_144);
        prepareTable("tb_shop", "TABLE", "id", "name");
        preparePrimaryKeys("tb_shop", "id");
        prepareQuery("SELECT `id`, `name` FROM `tb_shop` ORDER BY `id` LIMIT ?");
        defineRowColumns(
                new Column("id", Types.BIGINT, "BIGINT"),
                new Column("name", Types.VARCHAR, "VARCHAR"));
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getObject(1)).thenReturn(1L, 2L);
        when(rows.getCharacterStream(2))
                .thenReturn(new StringReader("店铺A"), new StringReader("店铺B"));

        ToolResult result = execute(tool, "{\"table_name\":\"tb_shop\"}");

        assertTrue(result.success(), result.toString());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(5, content.path("sample_limit").asInt());
        assertEquals(2, content.path("returned_rows").asInt());
        assertEquals("店铺A", content.path("rows").get(0).path("name").asText());
        verify(statement).setInt(1, 5);
        verify(statement).setQueryTimeout(5);
        verify(statement).setMaxRows(5);
        verify(connection).setReadOnly(true);
    }

    @Test
    void acceptsBoundaryLimitsAndRejectsOutOfRangeOrUnexpectedArguments() throws Exception {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 262_144);
        ToolExecutor executor = executor(tool);

        for (int limit : List.of(1, 10)) {
            org.mockito.Mockito.reset(dataSource, connection, metadata, tables, columns,
                    primaryKeys, statement, rows, rowMetadata);
            prepareTable("post", "TABLE", "id");
            preparePrimaryKeys("post", "id");
            prepareQuery("SELECT `id` FROM `post` ORDER BY `id` LIMIT ?");
            defineRowColumns(new Column("id", Types.BIGINT, "BIGINT"));
            when(rows.next()).thenReturn(false);
            ToolResult result = executor.execute("call", "sample_table_data",
                    "{\"table_name\":\"post\",\"limit\":" + limit + "}");
            assertTrue(result.success(), result.toString());
            verify(statement).setInt(1, limit);
        }

        ToolResult zero = executor.execute("call", "sample_table_data",
                "{\"table_name\":\"post\",\"limit\":0}");
        ToolResult eleven = executor.execute("call", "sample_table_data",
                "{\"table_name\":\"post\",\"limit\":11}");
        ToolResult extra = executor.execute("call", "sample_table_data",
                "{\"table_name\":\"post\",\"where\":\"1=1\"}");
        assertEquals("INVALID_SAMPLE_LIMIT", zero.errorCode());
        assertEquals("INVALID_SAMPLE_LIMIT", eleven.errorCode());
        assertEquals("INVALID_ARGUMENTS", extra.errorCode());
    }

    @Test
    void rejectsMissingViewOtherCatalogAndSystemDatabase() throws Exception {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 262_144);
        prepareConnection();
        when(metadata.getTables(CATALOG, null, "missing", null)).thenReturn(tables);
        when(tables.next()).thenReturn(false);
        assertEquals("TABLE_NOT_FOUND", execute(tool,
                "{\"table_name\":\"missing\"}").errorCode());

        org.mockito.Mockito.reset(dataSource, connection, metadata, tables);
        prepareTable("report_view", "VIEW", "id");
        assertEquals("TABLE_TYPE_NOT_ALLOWED", execute(tool,
                "{\"table_name\":\"report_view\"}").errorCode());

        org.mockito.Mockito.reset(dataSource, connection, metadata, tables);
        prepareConnection();
        when(metadata.getTables(CATALOG, null, "foreign", null)).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_CAT")).thenReturn("other_database");
        when(tables.getString("TABLE_NAME")).thenReturn("foreign");
        assertEquals("TABLE_NOT_FOUND", execute(tool,
                "{\"table_name\":\"foreign\"}").errorCode());

        org.mockito.Mockito.reset(dataSource, connection);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("mysql");
        assertEquals("SYSTEM_DATABASE_NOT_ALLOWED", execute(tool,
                "{\"table_name\":\"user\"}").errorCode());
    }

    @Test
    void omitsOrderByWithoutPrimaryKeyAndOrdersCompositePrimaryKey() throws Exception {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 262_144);
        prepareTable("event_log", "TABLE", "tenant_id", "event_id");
        preparePrimaryKeys("event_log");
        prepareQuery("SELECT `tenant_id`, `event_id` FROM `event_log` LIMIT ?");
        defineRowColumns(
                new Column("tenant_id", Types.BIGINT, "BIGINT"),
                new Column("event_id", Types.BIGINT, "BIGINT"));
        when(rows.next()).thenReturn(false);
        assertTrue(execute(tool, "{\"table_name\":\"event_log\"}").success());

        org.mockito.Mockito.reset(dataSource, connection, metadata, tables, columns,
                primaryKeys, statement, rows, rowMetadata);
        prepareTable("order_item", "TABLE", "order_id", "line_no");
        preparePrimaryKeys("order_item", "line_no", "order_id");
        when(primaryKeys.getObject("KEY_SEQ")).thenReturn(2, 1);
        prepareQuery("SELECT `order_id`, `line_no` FROM `order_item` "
                + "ORDER BY `order_id`, `line_no` LIMIT ?");
        defineRowColumns(
                new Column("order_id", Types.BIGINT, "BIGINT"),
                new Column("line_no", Types.INTEGER, "INT"));
        when(rows.next()).thenReturn(false);
        assertTrue(execute(tool, "{\"table_name\":\"order_item\"}").success());
    }

    @Test
    void excludesDeniedColumnsAndMasksProtectedColumns() throws Exception {
        SensitiveFieldPolicy policy = SensitiveFieldPolicy.of(
                Set.of("password"), Set.of("phone"));
        SampleTableDataTool tool = tool(policy, 262_144);
        prepareTable("admin", "TABLE", "id", "password", "phone", "name");
        preparePrimaryKeys("admin", "id");
        prepareQuery("SELECT `id`, `phone`, `name` FROM `admin` ORDER BY `id` LIMIT ?");
        defineRowColumns(
                new Column("id", Types.BIGINT, "BIGINT"),
                new Column("phone", Types.VARCHAR, "VARCHAR"),
                new Column("name", Types.VARCHAR, "VARCHAR"));
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(1L);
        when(rows.getObject(2)).thenReturn("13800138000");
        when(rows.getCharacterStream(3)).thenReturn(new StringReader("管理员"));

        ToolResult result = execute(tool, "{\"table_name\":\"admin\"}");

        JsonNode content = objectMapper.readTree(result.content());
        assertTrue(result.success(), result.toString());
        assertEquals("password", content.path("excluded_columns").get(0).path("name").asText());
        assertEquals("DENY", content.path("excluded_columns").get(0).path("reason").asText());
        assertEquals(SensitiveFieldPolicy.MASKED_VALUE,
                content.path("rows").get(0).path("phone").asText());
        assertFalse(result.content().contains("13800138000"));
    }

    @Test
    void rejectsTableWhenNoSafeColumnsRemain() throws Exception {
        SampleTableDataTool tool = tool(
                SensitiveFieldPolicy.of(Set.of("password", "secret"), Set.of()), 262_144);
        prepareTable("credential", "TABLE", "password", "secret");
        preparePrimaryKeys("credential");

        ToolResult result = execute(tool, "{\"table_name\":\"credential\"}");

        assertEquals("NO_SAFE_COLUMNS", result.errorCode());
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void reusesFieldAndResultSizeLimits() throws Exception {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 230);
        prepareTable("post", "TABLE", "content");
        preparePrimaryKeys("post");
        prepareQuery("SELECT `content` FROM `post` LIMIT ?");
        defineRowColumns(new Column("content", Types.VARCHAR, "VARCHAR"));
        when(rows.next()).thenReturn(true, true);
        when(rows.getCharacterStream(1)).thenReturn(new StringReader("中".repeat(2500)));

        ToolResult result = execute(tool, "{\"table_name\":\"post\"}");

        JsonNode content = objectMapper.readTree(result.content());
        assertTrue(result.success(), result.toString());
        assertTrue(content.path("truncated").asBoolean());
        assertEquals(0, content.path("returned_rows").asInt());
        assertTrue(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 230);
        assertTrue(java.util.stream.StreamSupport.stream(
                        content.path("truncationReasons").spliterator(), false)
                .map(JsonNode::asText)
                .anyMatch("RESULT_SIZE_LIMIT"::equals));
    }

    @Test
    void reportsTimeoutAndDatabaseFailureWithoutSensitiveDetails() throws Exception {
        SampleTableDataTool tool = tool(SensitiveFieldPolicy.none(), 262_144);
        prepareTable("post", "TABLE", "id");
        preparePrimaryKeys("post", "id");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(
                new SQLTimeoutException("jdbc:mysql://private password=secret"));

        ToolResult timeout = execute(tool, "{\"table_name\":\"post\"}");
        assertEquals("QUERY_TIMEOUT", timeout.errorCode());
        assertFalse(timeout.toModelContent().contains("secret"));

        DataSource failed = mock(DataSource.class);
        when(failed.getConnection()).thenThrow(new SQLException("password=secret"));
        SampleTableDataTool failedTool = new SampleTableDataTool(
                failed,
                objectMapper,
                SensitiveFieldPolicy.none(),
                new DatabaseResultSetMapper(objectMapper, 262_144));
        ToolResult connectionFailure = execute(failedTool, "{\"table_name\":\"post\"}");
        assertEquals("DATABASE_CONNECTION_ERROR", connectionFailure.errorCode());
        assertFalse(connectionFailure.toModelContent().contains("secret"));
    }

    private SampleTableDataTool tool(SensitiveFieldPolicy policy, int maxBytes) {
        return new SampleTableDataTool(
                dataSource,
                objectMapper,
                policy,
                new DatabaseResultSetMapper(objectMapper, maxBytes));
    }

    private ToolExecutor executor(SampleTableDataTool tool) {
        return new ToolExecutor(new ToolRegistry(List.of(tool)), objectMapper);
    }

    private ToolResult execute(SampleTableDataTool tool, String arguments) {
        return executor(tool).execute("call_sample", "sample_table_data", arguments);
    }

    private void prepareConnection() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(CATALOG);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getIdentifierQuoteString()).thenReturn("`");
    }

    private void prepareTable(String tableName, String type, String... columnNames)
            throws SQLException {
        prepareConnection();
        String pattern = tableName.replace("_", "\\_");
        when(metadata.getTables(eq(CATALOG), isNull(), eq(pattern), isNull())).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(tables.getString("TABLE_NAME")).thenReturn(tableName);
        when(tables.getString("TABLE_TYPE")).thenReturn(type);

        when(metadata.getColumns(eq(CATALOG), isNull(), eq(pattern), eq("%"))).thenReturn(columns);
        boolean[] columnRows = new boolean[columnNames.length + 1];
        java.util.Arrays.fill(columnRows, 0, columnNames.length, true);
        when(columns.next()).thenAnswer(new SequentialBooleanAnswer(columnRows));
        when(columns.getString("TABLE_CAT")).thenAnswer(invocation -> CATALOG);
        when(columns.getString("TABLE_NAME")).thenAnswer(invocation -> tableName);
        java.util.concurrent.atomic.AtomicInteger columnIndex = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(columns.next()).thenAnswer(invocation -> columnIndex.incrementAndGet() < columnNames.length);
        when(columns.getString("COLUMN_NAME"))
                .thenAnswer(invocation -> columnNames[columnIndex.get()]);
        when(columns.getString("TYPE_NAME")).thenReturn("VARCHAR");
        when(columns.getObject("ORDINAL_POSITION"))
                .thenAnswer(invocation -> columnIndex.get() + 1);
    }

    private void preparePrimaryKeys(String tableName, String... names) throws SQLException {
        when(metadata.getPrimaryKeys(CATALOG, null, tableName)).thenReturn(primaryKeys);
        java.util.concurrent.atomic.AtomicInteger keyIndex = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(primaryKeys.next()).thenAnswer(invocation -> keyIndex.incrementAndGet() < names.length);
        when(primaryKeys.getString("TABLE_CAT")).thenAnswer(invocation -> CATALOG);
        when(primaryKeys.getString("TABLE_NAME")).thenAnswer(invocation -> tableName);
        if (names.length > 0) {
            when(primaryKeys.getString("COLUMN_NAME"))
                    .thenAnswer(invocation -> names[keyIndex.get()]);
            when(primaryKeys.getObject("KEY_SEQ"))
                    .thenAnswer(invocation -> keyIndex.get() + 1);
        }
    }

    private void prepareQuery(String expectedSql) throws SQLException {
        when(connection.prepareStatement(expectedSql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(rowMetadata);
    }

    private void defineRowColumns(Column... definitions) throws SQLException {
        when(rowMetadata.getColumnCount()).thenReturn(definitions.length);
        for (int index = 0; index < definitions.length; index++) {
            int jdbcIndex = index + 1;
            Column definition = definitions[index];
            when(rowMetadata.getColumnLabel(jdbcIndex)).thenReturn(definition.name());
            when(rowMetadata.getColumnType(jdbcIndex)).thenReturn(definition.type());
            when(rowMetadata.getColumnTypeName(jdbcIndex)).thenReturn(definition.typeName());
        }
    }

    private record Column(String name, int type, String typeName) {
    }

    private static final class SequentialBooleanAnswer
            implements org.mockito.stubbing.Answer<Boolean> {
        private final boolean[] values;
        private int index;

        private SequentialBooleanAnswer(boolean[] values) {
            this.values = values;
        }

        @Override
        public Boolean answer(org.mockito.invocation.InvocationOnMock invocation) {
            return index < values.length && values[index++];
        }
    }
}
