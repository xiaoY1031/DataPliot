package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 安全地读取当前数据库普通表中的少量真实样本数据。 */
@Component
public class SampleTableDataTool implements AgentTool {

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 10;
    static final int QUERY_TIMEOUT_SECONDS = 5;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "sample_table_data",
            "读取当前配置数据库中指定普通表的少量真实样本；最多 10 行，拒绝敏感字段并脱敏受保护字段",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "table_name", Map.of(
                                    "type", "string",
                                    "description", "需要查看样本的当前数据库普通表名称"),
                            "limit", Map.of(
                                    "type", "integer",
                                    "description", "返回样本行数，默认 5，范围 1 到 10",
                                    "minimum", 1,
                                    "maximum", 10)),
                    "required", List.of("table_name"),
                    "additionalProperties", false));
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_$-]+");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SensitiveFieldPolicy sensitiveFieldPolicy;
    private final DatabaseResultSetMapper resultSetMapper;

    public SampleTableDataTool(
            DataSource dataSource,
            ObjectMapper objectMapper,
            SensitiveFieldPolicy sensitiveFieldPolicy,
            DatabaseResultSetMapper resultSetMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.sensitiveFieldPolicy = sensitiveFieldPolicy;
        this.resultSetMapper = resultSetMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String requestedTable = invocation.arguments().path("table_name").asText(null);
        if (!isValidTableName(requestedTable)) {
            return ToolResult.failure("INVALID_TABLE_NAME",
                    "table_name 必须是 1 到 64 个字符的合法表名，只能包含字母、数字、下划线、美元符号或连字符。");
        }
        int limit = invocation.arguments().has("limit")
                ? invocation.arguments().path("limit").asInt()
                : DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            return ToolResult.failure("INVALID_SAMPLE_LIMIT", "limit 必须在 1 到 10 之间。");
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
                        "不允许读取 MySQL 系统数据库中的样本数据。");
            }

            DatabaseMetaData metadata = connection.getMetaData();
            TableInfo table = findTable(metadata, catalog, requestedTable);
            if (table == null) {
                return ToolResult.failure("TABLE_NOT_FOUND",
                        "当前数据库中不存在指定表：" + requestedTable);
            }
            if (!"TABLE".equals(table.type())) {
                return ToolResult.failure("TABLE_TYPE_NOT_ALLOWED",
                        "只能读取当前数据库中的普通表，目标对象不是普通表：" + requestedTable);
            }

            List<ColumnInfo> columns = readColumns(metadata, catalog, table.name());
            if (columns.isEmpty()) {
                return ToolResult.failure("TABLE_COLUMNS_EMPTY",
                        "目标表没有可用的字段元信息：" + requestedTable);
            }
            List<PrimaryKeyColumn> primaryKeys = readPrimaryKeys(metadata, catalog, table.name());

            List<ColumnInfo> selectedColumns = new ArrayList<>();
            List<ExcludedColumn> excludedColumns = new ArrayList<>();
            Set<Integer> maskedIndexes = new LinkedHashSet<>();
            for (ColumnInfo column : columns) {
                SensitiveFieldPolicy.Action action = sensitiveFieldPolicy.actionFor(column.name());
                if (action == SensitiveFieldPolicy.Action.DENY) {
                    excludedColumns.add(new ExcludedColumn(column.name(), "DENY"));
                    continue;
                }
                selectedColumns.add(column);
                if (action == SensitiveFieldPolicy.Action.MASK) {
                    maskedIndexes.add(selectedColumns.size());
                }
            }
            if (selectedColumns.isEmpty()) {
                return ToolResult.failure("NO_SAFE_COLUMNS",
                        "目标表的全部字段都被敏感字段策略禁止返回，无法提供样本数据。");
            }

            String sql = buildSql(metadata, table.name(), selectedColumns, primaryKeys);
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, limit);
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(limit);
                statement.setFetchSize(limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ObjectNode content = objectMapper.createObjectNode();
                    content.put("table_name", table.name());
                    content.put("sample_limit", limit);
                    var excludedArray = content.putArray("excluded_columns");
                    for (ExcludedColumn excluded : excludedColumns) {
                        ObjectNode item = excludedArray.addObject();
                        item.put("name", excluded.name());
                        item.put("reason", excluded.reason());
                    }
                    resultSetMapper.map(
                            resultSet,
                            content,
                            limit,
                            Set.copyOf(maskedIndexes),
                            "returned_rows");
                    return ToolResult.success(content.toString());
                }
            }
        } catch (SQLException e) {
            if (isTimeout(e)) {
                return ToolResult.failure("QUERY_TIMEOUT",
                        "样本查询执行超过 " + QUERY_TIMEOUT_SECONDS + " 秒，已终止。");
            }
            return ToolResult.failure("SAMPLE_QUERY_ERROR",
                    "读取表样本失败，请检查数据库连接和账号的只读访问权限。");
        } catch (RuntimeException e) {
            return ToolResult.failure("SAMPLE_QUERY_ERROR",
                    "读取表样本失败，请检查数据库连接和账号的只读访问权限。");
        }
    }

    private boolean isValidTableName(String tableName) {
        return tableName != null
                && !tableName.isBlank()
                && tableName.equals(tableName.trim())
                && tableName.codePointCount(0, tableName.length()) <= 64
                && TABLE_NAME_PATTERN.matcher(tableName).matches();
    }

    private TableInfo findTable(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        String pattern = escapePattern(metadata, tableName);
        TableInfo disallowed = null;
        try (ResultSet tables = metadata.getTables(catalog, null, pattern, null)) {
            while (tables.next()) {
                if (!catalog.equals(tables.getString("TABLE_CAT"))
                        || !tableName.equals(tables.getString("TABLE_NAME"))) {
                    continue;
                }
                TableInfo candidate = new TableInfo(
                        tables.getString("TABLE_NAME"),
                        tables.getString("TABLE_TYPE"));
                if ("TABLE".equals(candidate.type())) {
                    return candidate;
                }
                disallowed = candidate;
            }
        }
        return disallowed;
    }

    private List<ColumnInfo> readColumns(
            DatabaseMetaData metadata,
            String catalog,
            String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet result = metadata.getColumns(
                catalog,
                null,
                escapePattern(metadata, tableName),
                "%")) {
            while (result.next()) {
                if (!catalog.equals(result.getString("TABLE_CAT"))
                        || !tableName.equals(result.getString("TABLE_NAME"))) {
                    continue;
                }
                String name = requiredText(result, "COLUMN_NAME");
                String type = requiredText(result, "TYPE_NAME");
                int position = requiredPositiveInt(result.getObject("ORDINAL_POSITION"));
                columns.add(new ColumnInfo(name, type, position));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnInfo::position));
        return List.copyOf(columns);
    }

    private List<PrimaryKeyColumn> readPrimaryKeys(
            DatabaseMetaData metadata,
            String catalog,
            String tableName) throws SQLException {
        List<PrimaryKeyColumn> keys = new ArrayList<>();
        try (ResultSet result = metadata.getPrimaryKeys(catalog, null, tableName)) {
            while (result.next()) {
                if (!catalog.equals(result.getString("TABLE_CAT"))
                        || !tableName.equals(result.getString("TABLE_NAME"))) {
                    continue;
                }
                keys.add(new PrimaryKeyColumn(
                        requiredText(result, "COLUMN_NAME"),
                        requiredPositiveInt(result.getObject("KEY_SEQ"))));
            }
        }
        keys.sort(Comparator.comparingInt(PrimaryKeyColumn::sequence));
        return List.copyOf(keys);
    }

    private String buildSql(
            DatabaseMetaData metadata,
            String tableName,
            List<ColumnInfo> columns,
            List<PrimaryKeyColumn> primaryKeys) throws SQLException {
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            quote = "`";
        }
        String identifierQuote = quote;
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(quoteIdentifier(columns.get(index).name(), identifierQuote));
        }
        sql.append(" FROM ").append(quoteIdentifier(tableName, identifierQuote));
        if (!primaryKeys.isEmpty()) {
            sql.append(" ORDER BY ");
            for (int index = 0; index < primaryKeys.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                sql.append(quoteIdentifier(primaryKeys.get(index).name(), identifierQuote));
            }
        }
        sql.append(" LIMIT ?");
        return sql.toString();
    }

    private String quoteIdentifier(String value, String quote) {
        return quote + value.replace(quote, quote + quote) + quote;
    }

    private String escapePattern(DatabaseMetaData metadata, String value) throws SQLException {
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    private String requiredText(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null || value.isBlank()) {
            throw new SQLException("数据库元信息缺少必要值");
        }
        return value;
    }

    private int requiredPositiveInt(Object value) throws SQLException {
        if (!(value instanceof Number number) || number.intValue() <= 0) {
            throw new SQLException("数据库元信息缺少有效顺序");
        }
        return number.intValue();
    }

    private boolean isTimeout(SQLException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        String state = exception.getSQLState();
        return "S1T00".equalsIgnoreCase(state)
                || "HYT00".equalsIgnoreCase(state)
                || "HYT01".equalsIgnoreCase(state)
                || exception.getErrorCode() == 3024
                || exception.getErrorCode() == 1317;
    }

    private record TableInfo(String name, String type) {
    }

    private record ColumnInfo(String name, String type, int position) {
    }

    private record PrimaryKeyColumn(String name, int sequence) {
    }

    private record ExcludedColumn(String name, String reason) {
    }
}
