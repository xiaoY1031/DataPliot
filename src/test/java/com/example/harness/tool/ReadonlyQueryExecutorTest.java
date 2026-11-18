package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadonlyQueryExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    private final ReadonlySqlValidator validator =
            new ReadonlySqlValidator(SensitiveFieldPolicy.none());
    private final ReadonlyQueryExecutor executor = new ReadonlyQueryExecutor(
            dataSource,
            validator,
            new DatabaseResultSetMapper(objectMapper, 262_144),
            objectMapper);

    @Test
    void analysisModeRejectsSentinelRowInsteadOfUsingTruncatedDataset() throws Exception {
        prepareQuery();
        defineColumn("id", Types.BIGINT, "BIGINT");
        when(resultSet.next()).thenReturn(true, true, true);
        when(resultSet.getObject(1)).thenReturn(1L, 2L);

        ReadonlyQueryExecutor.QueryExecutionException error = expectFailure(
                "SELECT id FROM post ORDER BY id", 2, 4096);

        assertEquals("DATASET_LIMIT_EXCEEDED", error.errorCode());
        verify(statement).setMaxRows(3);
        verify(statement).setFetchSize(3);
    }

    @Test
    void analysisModeEnforcesUtf8LimitWhileReadingRows() throws Exception {
        prepareQuery();
        defineColumn("content", Types.VARCHAR, "VARCHAR");
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getCharacterStream(1))
                .thenReturn(new StringReader("中".repeat(300)));

        ReadonlyQueryExecutor.QueryExecutionException error = expectFailure(
                "SELECT content FROM post", 100, 256);

        assertEquals("DATASET_LIMIT_EXCEEDED", error.errorCode());
    }

    @Test
    void analysisModeUsesStableJdbcToJsonTypes() throws Exception {
        prepareQuery();
        when(metadata.getColumnCount()).thenReturn(6);
        defineColumn(1, "amount", Types.DECIMAL, "DECIMAL");
        defineColumn(2, "created_at", Types.TIMESTAMP, "TIMESTAMP");
        defineColumn(3, "optional_value", Types.VARCHAR, "VARCHAR");
        defineColumn(4, "total", Types.BIGINT, "BIGINT");
        defineColumn(5, "not_a_number", Types.DOUBLE, "DOUBLE");
        defineColumn(6, "infinity", Types.REAL, "REAL");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(new BigDecimal("1234567890.123456789"));
        when(resultSet.getObject(2)).thenReturn(Timestamp.valueOf("2026-11-18 12:34:56"));
        when(resultSet.getCharacterStream(3)).thenReturn(null);
        when(resultSet.getObject(4)).thenReturn(12L);
        when(resultSet.getObject(5)).thenReturn(Double.NaN);
        when(resultSet.getObject(6)).thenReturn(Float.POSITIVE_INFINITY);

        ReadonlyQueryExecutor.AnalysisDataset dataset = executor.executeForAnalysis(
                validator.validate("SELECT amount, created_at, optional_value, total, "
                        + "score AS not_a_number, ratio AS infinity FROM post"),
                10,
                4096,
                10);

        JsonNode json = objectMapper.readTree(dataset.json());
        JsonNode row = json.path("rows").get(0);
        assertEquals("1234567890.123456789", row.path("amount").asText());
        assertEquals("DECIMAL_STRING", json.path("columns").get(0).path("encoding").asText());
        assertEquals("2026-11-18T12:34:56", row.path("created_at").asText());
        assertTrue(row.path("optional_value").isNull());
        assertEquals(12L, row.path("total").asLong());
        assertTrue(row.path("not_a_number").isNull());
        assertTrue(row.path("infinity").isNull());
        assertEquals(1, dataset.rowCount());
    }

    private ReadonlyQueryExecutor.QueryExecutionException expectFailure(
            String sql, int maxRows, int maxBytes) throws Exception {
        try {
            executor.executeForAnalysis(validator.validate(sql), maxRows, maxBytes, 10);
            fail("expected QueryExecutionException");
            return null;
        } catch (ReadonlyQueryExecutor.QueryExecutionException e) {
            return e;
        }
    }

    private void prepareQuery() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("datapilot");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
    }

    private void defineColumn(String name, int type, String typeName) throws Exception {
        when(metadata.getColumnCount()).thenReturn(1);
        defineColumn(1, name, type, typeName);
    }

    private void defineColumn(int index, String name, int type, String typeName) throws Exception {
        when(metadata.getColumnLabel(index)).thenReturn(name);
        when(metadata.getColumnType(index)).thenReturn(type);
        when(metadata.getColumnTypeName(index)).thenReturn(typeName);
    }
}
