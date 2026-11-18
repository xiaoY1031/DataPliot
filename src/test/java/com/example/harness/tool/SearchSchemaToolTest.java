package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchSchemaToolTest {

    private static final String CATALOG = "datapilot";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet tables = mock(ResultSet.class);
    private final ResultSet columns = mock(ResultSet.class);
    private final SearchSchemaTool tool = new SearchSchemaTool(dataSource, objectMapper);
    private final ToolRegistry registry = new ToolRegistry(List.of(tool));
    private final ToolExecutor executor = new ToolExecutor(registry, objectMapper);

    @Test
    void definesKeywordSearchAndExposesItThroughRegistry() {
        ToolDefinition definition = tool.definition();

        assertEquals("search_schema", definition.name());
        assertTrue(definition.description().contains("准确表名"));
        assertEquals(List.of("keywords"), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        Map<?, ?> keywords = (Map<?, ?>) properties.get("keywords");
        assertEquals("array", keywords.get("type"));
        assertEquals(1, keywords.get("minItems"));
        assertEquals(20, keywords.get("maxItems"));
        assertEquals("string", ((Map<?, ?>) keywords.get("items")).get("type"));
        assertEquals(List.of(definition), registry.listDefinitions());
    }

    @Test
    void searchesNamesAndCommentsAndExplainsWhyTablesMatched() throws Exception {
        prepareConnection();
        prepareTables(
                new TableRow("tb_shop_type", "店铺分类"),
                new TableRow("tb_shop", null),
                new TableRow("audit_log", "审计日志"));
        prepareColumns(
                new ColumnRow("tb_shop", "id", null, 1),
                new ColumnRow("tb_shop", "sold", "累计销量", 2),
                new ColumnRow("tb_shop_type", "name", "类型名称", 1),
                new ColumnRow("audit_log", "id", null, 1));

        ToolResult result = execute("店铺", "shop", "sold");

        assertTrue(result.success(), result.toString());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(List.of("店铺", "shop", "sold"), textList(content.path("keywords")));
        assertEquals(2, content.path("match_count").asInt());
        assertEquals(2, content.path("total_match_count").asInt());
        assertFalse(content.path("truncated").asBoolean());

        JsonNode first = content.path("matches").get(0);
        assertEquals("tb_shop_type", first.path("table_name").asText());
        assertEquals("店铺分类", first.path("table_comment").asText());
        assertEquals(List.of("店铺", "shop"), textList(first.path("matched_keywords")));
        assertTrue(hasReason(first, "店铺", "table_comment", "店铺分类"));
        assertTrue(hasReason(first, "shop", "table_name", "tb_shop_type"));

        JsonNode second = content.path("matches").get(1);
        assertEquals("tb_shop", second.path("table_name").asText());
        assertTrue(second.path("table_comment").isNull());
        assertTrue(hasReason(second, "sold", "column_name", "sold"));
        assertEquals("sold", second.path("matched_columns").get(0).path("name").asText());
        assertEquals(0, content.path("metadata_warnings").size());
    }

    @Test
    void matchesCaseAndIdentifierFormattingWithoutInventingSynonyms() throws Exception {
        prepareConnection();
        prepareTables(new TableRow("ShopType", null));
        prepareColumns(new ColumnRow("ShopType", "TYPE_ID", null, 1));

        ToolResult result = execute("shop type", "type_id", "merchant");

        JsonNode match = objectMapper.readTree(result.content()).path("matches").get(0);
        assertEquals("ShopType", match.path("table_name").asText());
        assertEquals(List.of("shop type", "type_id"), textList(match.path("matched_keywords")));
        assertFalse(textList(match.path("matched_keywords")).contains("merchant"));
    }

    @Test
    void deduplicatesEquivalentKeywordFormatting() throws Exception {
        prepareConnection();
        prepareTables(new TableRow("shop_type", null));
        prepareColumns();

        ToolResult result = execute("shop type", "shop_type", "SHOP-TYPE");

        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(List.of("shop type"), textList(content.path("keywords")));
        assertEquals(100, content.path("matches").get(0)
                .path("relevance_score").asInt());
    }

    @Test
    void returnsSuccessfulEmptyResultWhenNothingMatches() throws Exception {
        prepareConnection();
        prepareTables(new TableRow("audit_log", null));
        prepareColumns(new ColumnRow("audit_log", "created_at", null, 1));

        ToolResult result = execute("店铺", "shop");

        assertTrue(result.success());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(0, content.path("match_count").asInt());
        assertEquals(0, content.path("total_match_count").asInt());
        assertEquals(0, content.path("matches").size());
    }

    @Test
    void keepsTableMatchesWhenColumnMetadataIsUnavailable() throws Exception {
        prepareConnection();
        prepareTables(new TableRow("tb_shop", null));
        when(metadata.getColumns(CATALOG, null, "%", "%"))
                .thenThrow(new SQLException("password=secret"));

        ToolResult result = execute("shop");

        assertTrue(result.success());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(1, content.path("match_count").asInt());
        assertEquals(1, content.path("metadata_warnings").size());
        assertFalse(result.content().contains("secret"));
    }

    @Test
    void discardsPartialColumnsWhenMetadataFailsDuringIteration() throws Exception {
        prepareConnection();
        prepareTables(new TableRow("tb_shop", null));
        when(metadata.getColumns(CATALOG, null, "%", "%")).thenReturn(columns);
        when(columns.next()).thenReturn(true).thenThrow(new SQLException("password=secret"));
        when(columns.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(columns.getString("TABLE_NAME")).thenReturn("tb_shop");
        when(columns.getString("COLUMN_NAME")).thenReturn("sold");
        when(columns.getString("REMARKS")).thenReturn("累计销量");
        when(columns.getObject("ORDINAL_POSITION")).thenReturn(1);

        ToolResult result = execute("sold");

        assertTrue(result.success());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(0, content.path("match_count").asInt());
        assertEquals(1, content.path("metadata_warnings").size());
        assertFalse(result.content().contains("secret"));
    }

    @Test
    void limitsReturnedCandidatesAndReportsTruncation() throws Exception {
        prepareConnection();
        TableRow[] tableRows = new TableRow[21];
        for (int index = 0; index < tableRows.length; index++) {
            tableRows[index] = new TableRow(String.format("shop_%02d", index), null);
        }
        prepareTables(tableRows);
        prepareColumns();

        ToolResult result = execute("shop");

        JsonNode content = objectMapper.readTree(result.content());
        assertEquals(20, content.path("match_count").asInt());
        assertEquals(21, content.path("total_match_count").asInt());
        assertTrue(content.path("truncated").asBoolean());
        assertEquals(List.of("MATCH_LIMIT"), textList(content.path("truncationReasons")));
        assertEquals("shop_00", content.path("matches").get(0).path("table_name").asText());
        assertEquals("shop_19", content.path("matches").get(19).path("table_name").asText());
    }

    @Test
    void boundsMatchDetailsForWideTables() throws Exception {
        prepareConnection();
        prepareTables(new TableRow("shop_metrics", null));
        ColumnRow[] columnRows = new ColumnRow[25];
        for (int index = 0; index < columnRows.length; index++) {
            columnRows[index] = new ColumnRow(
                    "shop_metrics",
                    "shop_metric_" + index,
                    "shop metric",
                    index + 1);
        }
        prepareColumns(columnRows);

        ToolResult result = execute("shop", "metric");

        JsonNode match = objectMapper.readTree(result.content()).path("matches").get(0);
        assertTrue(match.path("match_reason_count").asInt() > 40);
        assertTrue(match.path("match_reasons_truncated").asBoolean());
        assertEquals(40, match.path("match_reasons").size());
        assertEquals(25, match.path("matched_column_count").asInt());
        assertTrue(match.path("matched_columns_truncated").asBoolean());
        assertEquals(20, match.path("matched_columns").size());
    }

    @Test
    void rejectsInvalidKeywordCollectionsBeforeConnecting() throws Exception {
        String[] invalidArguments = {
                "{}",
                "{\"keywords\":[]}",
                "{\"keywords\":[\" \"]}",
                "{\"keywords\":[\"___\"]}",
                "{\"keywords\":[1]}",
                "{\"keywords\":[\"" + "a".repeat(65) + "\"]}",
                objectMapper.writeValueAsString(Map.of(
                        "keywords", java.util.stream.IntStream.range(0, 21)
                                .mapToObj(index -> "k" + index)
                                .toList())),
                "{\"keywords\":[\"shop\"],\"database\":\"other\"}"
        };

        for (String arguments : invalidArguments) {
            ToolResult result = executor.execute("call_search_schema", "search_schema", arguments);
            assertFalse(result.success(), arguments);
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsMissingAndSystemDatabase() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(null, "mysql");

        assertEquals("DATABASE_NOT_SELECTED", execute("shop").errorCode());
        assertEquals("SYSTEM_DATABASE_NOT_ALLOWED", execute("shop").errorCode());
        verifyNoInteractions(metadata);
    }

    @Test
    void hidesConnectionErrors() throws Exception {
        when(dataSource.getConnection()).thenThrow(
                new SQLException("jdbc:mysql://private-host/db password=secret"));

        ToolResult connectionFailure = execute("shop");

        assertEquals("DATABASE_CONNECTION_ERROR", connectionFailure.errorCode());
        assertFalse(connectionFailure.toModelContent().contains("private"));
        assertFalse(connectionFailure.toModelContent().contains("secret"));
    }

    @Test
    void hidesTableMetadataErrors() throws Exception {
        prepareConnection();
        when(metadata.getTables(eq(CATALOG), isNull(), eq("%"), aryEq(new String[]{"TABLE"})))
                .thenThrow(new SQLException("password=secret"));

        ToolResult metadataFailure = execute("shop");

        assertEquals("DATABASE_METADATA_ERROR", metadataFailure.errorCode());
        assertFalse(metadataFailure.toModelContent().contains("secret"));
        verify(connection).close();
    }

    private ToolResult execute(String... keywords) throws Exception {
        return executor.execute(
                "call_search_schema",
                "search_schema",
                objectMapper.writeValueAsString(Map.of("keywords", List.of(keywords))));
    }

    private void prepareConnection() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(CATALOG);
        when(connection.getMetaData()).thenReturn(metadata);
    }

    private void prepareTables(TableRow... rows) throws SQLException {
        when(metadata.getTables(eq(CATALOG), isNull(), eq("%"), aryEq(new String[]{"TABLE"})))
                .thenReturn(tables);
        AtomicInteger rowIndex = new AtomicInteger(-1);
        when(tables.next()).thenAnswer(invocation -> rowIndex.incrementAndGet() < rows.length);
        when(tables.getString("TABLE_CAT")).thenAnswer(invocation -> CATALOG);
        when(tables.getString("TABLE_TYPE")).thenAnswer(invocation -> "TABLE");
        when(tables.getString("TABLE_NAME"))
                .thenAnswer(invocation -> rows[rowIndex.get()].name());
        when(tables.getString("REMARKS"))
                .thenAnswer(invocation -> rows[rowIndex.get()].comment());
    }

    private void prepareColumns(ColumnRow... rows) throws SQLException {
        when(metadata.getColumns(CATALOG, null, "%", "%")).thenReturn(columns);
        AtomicInteger rowIndex = new AtomicInteger(-1);
        when(columns.next()).thenAnswer(invocation -> rowIndex.incrementAndGet() < rows.length);
        when(columns.getString("TABLE_CAT")).thenAnswer(invocation -> CATALOG);
        when(columns.getString("TABLE_NAME"))
                .thenAnswer(invocation -> rows[rowIndex.get()].tableName());
        when(columns.getString("COLUMN_NAME"))
                .thenAnswer(invocation -> rows[rowIndex.get()].name());
        when(columns.getString("REMARKS"))
                .thenAnswer(invocation -> rows[rowIndex.get()].comment());
        when(columns.getObject("ORDINAL_POSITION"))
                .thenAnswer(invocation -> rows[rowIndex.get()].ordinalPosition());
    }

    private List<String> textList(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private boolean hasReason(JsonNode match, String keyword, String matchedIn, String value) {
        return java.util.stream.StreamSupport.stream(
                        match.path("match_reasons").spliterator(), false)
                .anyMatch(reason -> keyword.equals(reason.path("keyword").asText())
                        && matchedIn.equals(reason.path("matched_in").asText())
                        && value.equals(reason.path("value").asText()));
    }

    private record TableRow(String name, String comment) {
    }

    private record ColumnRow(String tableName, String name, String comment, int ordinalPosition) {
    }
}
