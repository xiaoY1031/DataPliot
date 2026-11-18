package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 在当前数据库的普通表元数据中进行可解释的关键词搜索。 */
@Component
public class SearchSchemaTool implements AgentTool {

    static final int MAX_KEYWORDS = 20;
    static final int MAX_KEYWORD_LENGTH = 64;
    static final int MAX_MATCHES = 20;
    static final int MAX_REASONS_PER_TABLE = 40;
    static final int MAX_MATCHED_COLUMNS_PER_TABLE = 20;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "search_schema",
            "根据业务关键词搜索当前数据库普通表的表名、表注释、字段名和字段注释，"
                    + "返回带匹配原因的候选表；适合目标明确但尚不知道准确表名时使用",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "keywords", Map.of(
                                    "type", "array",
                                    "description", "1 到 20 个业务关键词；可同时提供中文、英文翻译和少量常见同义词",
                                    "minItems", 1,
                                    "maxItems", MAX_KEYWORDS,
                                    "items", Map.of(
                                            "type", "string",
                                            "maxLength", MAX_KEYWORD_LENGTH))),
                    "required", List.of("keywords"),
                    "additionalProperties", false));
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SearchSchemaTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        List<Keyword> keywords = parseKeywords(invocation);
        if (keywords == null) {
            return ToolResult.failure("INVALID_KEYWORDS",
                    "keywords 必须包含 1 到 20 个非空字符串，每个关键词最长 64 个字符。");
        }

        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException | RuntimeException e) {
            return ToolResult.failure("DATABASE_CONNECTION_ERROR",
                    "无法连接配置的数据源，请检查数据库服务、网络和本地数据源配置。");
        }

        try (connection) {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                return ToolResult.failure("DATABASE_NOT_SELECTED",
                        "未选择数据库，请配置 DATAPILOT_DB_NAME 后重启应用。");
            }
            if (SYSTEM_DATABASES.contains(catalog.toLowerCase(Locale.ROOT))) {
                return ToolResult.failure("SYSTEM_DATABASE_NOT_ALLOWED",
                        "不允许查询 MySQL 系统数据库，请将 DATAPILOT_DB_NAME 配置为业务数据库。");
            }

            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, TableMetadata> tables;
            try {
                tables = readTables(metadata, catalog);
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("DATABASE_METADATA_ERROR",
                        "读取当前数据库的表元信息失败，请检查数据库连接和账号的元信息访问权限。");
            }

            List<String> metadataWarnings = new ArrayList<>();
            try {
                readColumns(metadata, catalog, tables);
            } catch (SQLException | RuntimeException e) {
                tables.values().forEach(table -> table.columns().clear());
                metadataWarnings.add("字段元信息不可用，搜索结果仅基于表名和表注释");
            }

            List<TableMatch> allMatches = tables.values().stream()
                    .map(table -> match(table, keywords))
                    .filter(tableMatch -> tableMatch.score() > 0)
                    .sorted(Comparator.comparingInt(TableMatch::score).reversed()
                            .thenComparing(TableMatch::tableName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(TableMatch::tableName))
                    .toList();
            List<TableMatch> returnedMatches = allMatches.stream()
                    .limit(MAX_MATCHES)
                    .toList();
            return ToolResult.success(toJson(
                    keywords, allMatches.size(), returnedMatches, metadataWarnings).toString());
        } catch (SQLException | RuntimeException e) {
            return ToolResult.failure("DATABASE_METADATA_ERROR",
                    "搜索当前数据库的结构信息失败，请检查数据库连接和元信息访问权限。");
        }
    }

    private List<Keyword> parseKeywords(ToolInvocation invocation) {
        var node = invocation.arguments().path("keywords");
        if (!node.isArray() || node.isEmpty() || node.size() > MAX_KEYWORDS) {
            return null;
        }

        Map<String, Keyword> distinct = new LinkedHashMap<>();
        for (var item : node) {
            if (!item.isTextual()) {
                return null;
            }
            String value = item.asText().trim();
            if (value.isEmpty()
                    || value.codePointCount(0, value.length()) > MAX_KEYWORD_LENGTH
                    || value.codePoints().noneMatch(Character::isLetterOrDigit)) {
                return null;
            }
            Keyword keyword = new Keyword(value, normalize(value), compact(value));
            distinct.putIfAbsent(keyword.compact(), keyword);
        }
        return distinct.isEmpty() ? null : List.copyOf(distinct.values());
    }

    private Map<String, TableMetadata> readTables(DatabaseMetaData metadata, String catalog)
            throws SQLException {
        Map<String, TableMetadata> tables = new TreeMap<>();
        try (ResultSet result = metadata.getTables(
                catalog, null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                if (!catalog.equals(result.getString("TABLE_CAT"))
                        || !"TABLE".equals(result.getString("TABLE_TYPE"))) {
                    continue;
                }
                String tableName = trimToNull(result.getString("TABLE_NAME"));
                if (tableName == null) {
                    continue;
                }
                tables.putIfAbsent(tableName, new TableMetadata(
                        tableName,
                        trimToNull(result.getString("REMARKS")),
                        new ArrayList<>()));
            }
        }
        return tables;
    }

    private void readColumns(
            DatabaseMetaData metadata,
            String catalog,
            Map<String, TableMetadata> tables) throws SQLException {
        try (ResultSet result = metadata.getColumns(catalog, null, "%", "%")) {
            while (result.next()) {
                if (!catalog.equals(result.getString("TABLE_CAT"))) {
                    continue;
                }
                TableMetadata table = tables.get(result.getString("TABLE_NAME"));
                if (table == null) {
                    continue;
                }
                String columnName = trimToNull(result.getString("COLUMN_NAME"));
                if (columnName == null) {
                    continue;
                }
                Integer ordinalPosition = optionalInteger(result.getObject("ORDINAL_POSITION"));
                table.columns().add(new ColumnMetadata(
                        columnName,
                        trimToNull(result.getString("REMARKS")),
                        ordinalPosition == null ? Integer.MAX_VALUE : ordinalPosition));
            }
        }
        tables.values().forEach(table -> table.columns().sort(
                Comparator.comparingInt(ColumnMetadata::ordinalPosition)
                        .thenComparing(ColumnMetadata::name)));
    }

    private TableMatch match(TableMetadata table, List<Keyword> keywords) {
        int score = 0;
        Set<String> matchedKeywords = new LinkedHashSet<>();
        List<MatchReason> reasons = new ArrayList<>();
        Map<String, MatchedColumn> matchedColumns = new LinkedHashMap<>();

        for (Keyword keyword : keywords) {
            MatchQuality tableNameMatch = matchQuality(keyword, table.name());
            if (tableNameMatch != MatchQuality.NONE) {
                int points = tableNameMatch == MatchQuality.EXACT ? 100 : 80;
                score += points;
                matchedKeywords.add(keyword.value());
                reasons.add(new MatchReason(
                        keyword.value(), "table_name", table.name(), null, points));
            }
            if (contains(keyword, table.comment())) {
                score += 60;
                matchedKeywords.add(keyword.value());
                reasons.add(new MatchReason(
                        keyword.value(), "table_comment", table.comment(), null, 60));
            }

            for (ColumnMetadata column : table.columns()) {
                MatchQuality columnNameMatch = matchQuality(keyword, column.name());
                if (columnNameMatch != MatchQuality.NONE) {
                    int points = columnNameMatch == MatchQuality.EXACT ? 50 : 40;
                    score += points;
                    matchedKeywords.add(keyword.value());
                    reasons.add(new MatchReason(
                            keyword.value(), "column_name", column.name(), column.name(), points));
                    matchedColumns.computeIfAbsent(
                                    column.name(),
                                    ignored -> new MatchedColumn(
                                            column.name(), column.comment(), new LinkedHashSet<>()))
                            .matchedKeywords().add(keyword.value());
                }
                if (contains(keyword, column.comment())) {
                    score += 20;
                    matchedKeywords.add(keyword.value());
                    reasons.add(new MatchReason(
                            keyword.value(), "column_comment", column.comment(), column.name(), 20));
                    matchedColumns.computeIfAbsent(
                                    column.name(),
                                    ignored -> new MatchedColumn(
                                            column.name(), column.comment(), new LinkedHashSet<>()))
                            .matchedKeywords().add(keyword.value());
                }
            }
        }

        return new TableMatch(
                table.name(),
                table.comment(),
                score,
                List.copyOf(matchedKeywords),
                List.copyOf(reasons),
                List.copyOf(matchedColumns.values()));
    }

    private ObjectNode toJson(
            List<Keyword> keywords,
            int totalMatchCount,
            List<TableMatch> matches,
            List<String> metadataWarnings) {
        ObjectNode content = objectMapper.createObjectNode();
        var keywordArray = content.putArray("keywords");
        keywords.forEach(keyword -> keywordArray.add(keyword.value()));
        content.put("match_count", matches.size());
        content.put("total_match_count", totalMatchCount);
        content.put("truncated", totalMatchCount > matches.size());
        var truncationReasons = content.putArray("truncationReasons");
        if (totalMatchCount > matches.size()) {
            truncationReasons.add("MATCH_LIMIT");
        }

        var matchArray = content.putArray("matches");
        for (TableMatch match : matches) {
            ObjectNode table = matchArray.addObject();
            table.put("table_name", match.tableName());
            putNullable(table, "table_comment", match.tableComment());
            table.put("relevance_score", match.score());
            var matchedKeywordArray = table.putArray("matched_keywords");
            match.matchedKeywords().forEach(matchedKeywordArray::add);

            table.put("match_reason_count", match.reasons().size());
            table.put("match_reasons_truncated",
                    match.reasons().size() > MAX_REASONS_PER_TABLE);
            var reasonArray = table.putArray("match_reasons");
            for (MatchReason reason : match.reasons().stream()
                    .limit(MAX_REASONS_PER_TABLE)
                    .toList()) {
                ObjectNode reasonNode = reasonArray.addObject();
                reasonNode.put("keyword", reason.keyword());
                reasonNode.put("matched_in", reason.matchedIn());
                reasonNode.put("value", reason.value());
                if (reason.columnName() != null) {
                    reasonNode.put("column_name", reason.columnName());
                }
                reasonNode.put("score", reason.score());
            }

            table.put("matched_column_count", match.matchedColumns().size());
            table.put("matched_columns_truncated",
                    match.matchedColumns().size() > MAX_MATCHED_COLUMNS_PER_TABLE);
            var columnArray = table.putArray("matched_columns");
            for (MatchedColumn column : match.matchedColumns().stream()
                    .limit(MAX_MATCHED_COLUMNS_PER_TABLE)
                    .toList()) {
                ObjectNode columnNode = columnArray.addObject();
                columnNode.put("name", column.name());
                putNullable(columnNode, "comment", column.comment());
                var columnKeywords = columnNode.putArray("matched_keywords");
                column.matchedKeywords().forEach(columnKeywords::add);
            }
        }

        var warningArray = content.putArray("metadata_warnings");
        metadataWarnings.forEach(warningArray::add);
        return content;
    }

    private MatchQuality matchQuality(Keyword keyword, String candidate) {
        if (candidate == null) {
            return MatchQuality.NONE;
        }
        String normalizedCandidate = normalize(candidate);
        String compactCandidate = compact(candidate);
        if (normalizedCandidate.equals(keyword.normalized())
                || compactCandidate.equals(keyword.compact())) {
            return MatchQuality.EXACT;
        }
        if (normalizedCandidate.contains(keyword.normalized())
                || compactCandidate.contains(keyword.compact())) {
            return MatchQuality.PARTIAL;
        }
        return MatchQuality.NONE;
    }

    private boolean contains(Keyword keyword, String candidate) {
        return matchQuality(keyword, candidate) != MatchQuality.NONE;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private String compact(String value) {
        StringBuilder compact = new StringBuilder();
        value.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Integer optionalInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private enum MatchQuality {
        NONE,
        PARTIAL,
        EXACT
    }

    private record Keyword(String value, String normalized, String compact) {
    }

    private record TableMetadata(
            String name,
            String comment,
            List<ColumnMetadata> columns) {
    }

    private record ColumnMetadata(String name, String comment, int ordinalPosition) {
    }

    private record MatchReason(
            String keyword,
            String matchedIn,
            String value,
            String columnName,
            int score) {
    }

    private record MatchedColumn(
            String name,
            String comment,
            Set<String> matchedKeywords) {
    }

    private record TableMatch(
            String tableName,
            String tableComment,
            int score,
            List<String> matchedKeywords,
            List<MatchReason> reasons,
            List<MatchedColumn> matchedColumns) {
    }
}
