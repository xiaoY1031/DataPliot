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
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 读取当前配置数据库中指定普通表的结构，不执行用户提供的 SQL。 */
@Component
public class DescribeTableTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "describe_table",
            "查询当前配置的 MySQL 数据库中指定普通表的字段、主键、外键和索引信息；未声明外键不代表不存在业务关联",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "table_name", Map.of(
                                    "type", "string",
                                    "description", "需要查询结构的当前数据库普通表名称")),
                    "required", List.of("table_name"),
                    "additionalProperties", false));
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_$-]+");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public DescribeTableTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String tableName = invocation.arguments().path("table_name").asText(null);
        if (!isValidTableName(tableName)) {
            return ToolResult.failure("INVALID_TABLE_NAME",
                    "table_name 必须是 1 到 64 个字符的合法表名，只能包含字母、数字、下划线、美元符号或连字符。");
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
            TableMetadata table;
            try {
                table = findTable(metadata, catalog, tableName);
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("DATABASE_METADATA_ERROR",
                        "确认目标表信息失败，请检查数据库连接和账号的元信息访问权限。");
            }
            if (table == null) {
                return ToolResult.failure("TABLE_NOT_FOUND",
                        "当前数据库中不存在指定表：" + tableName);
            }
            if (!"TABLE".equals(table.type())) {
                return ToolResult.failure("TABLE_TYPE_NOT_ALLOWED",
                        "只能查询当前数据库中的普通表，目标对象不是普通表：" + tableName);
            }

            Set<String> primaryKeys;
            try {
                primaryKeys = readPrimaryKeys(metadata, catalog, table.name());
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("PRIMARY_KEY_METADATA_ERROR",
                        "读取目标表的主键信息失败，请检查账号的元信息访问权限。");
            }

            List<ColumnMetadata> columns;
            try {
                columns = readColumns(metadata, catalog, table.name());
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("COLUMN_METADATA_ERROR",
                        "读取目标表的字段信息失败，请检查账号的元信息访问权限。");
            }
            if (columns.isEmpty()) {
                return ToolResult.failure("TABLE_COLUMNS_EMPTY",
                        "目标表没有可用的字段元信息：" + tableName);
            }

            List<String> metadataWarnings = new ArrayList<>();
            List<ForeignKeyMetadata> importedKeys = null;
            try {
                importedKeys = readForeignKeys(
                        metadata.getImportedKeys(catalog, null, table.name()),
                        catalog,
                        table.name(),
                        ForeignKeyDirection.IMPORTED);
            } catch (SQLException | RuntimeException e) {
                metadataWarnings.add("导入外键元信息不可用");
            }

            List<ForeignKeyMetadata> exportedKeys = null;
            try {
                exportedKeys = readForeignKeys(
                        metadata.getExportedKeys(catalog, null, table.name()),
                        catalog,
                        table.name(),
                        ForeignKeyDirection.EXPORTED);
            } catch (SQLException | RuntimeException e) {
                metadataWarnings.add("导出外键元信息不可用");
            }

            List<IndexMetadata> indexes = null;
            try {
                indexes = readIndexes(metadata, catalog, table.name());
            } catch (SQLException | RuntimeException e) {
                metadataWarnings.add("索引和唯一索引元信息不可用");
            }

            return ToolResult.success(toJson(
                    table,
                    columns,
                    primaryKeys,
                    importedKeys,
                    exportedKeys,
                    indexes,
                    metadataWarnings).toString());
        } catch (SQLException | RuntimeException e) {
            // 不返回 JDBC 异常正文，避免泄露连接地址、账号或密码。
            return ToolResult.failure("DATABASE_METADATA_ERROR",
                    "读取目标表结构失败，请检查数据库连接和元信息访问权限。");
        }
    }

    private boolean isValidTableName(String tableName) {
        return tableName != null
                && !tableName.isBlank()
                && tableName.equals(tableName.trim())
                && tableName.codePointCount(0, tableName.length()) <= 64
                && TABLE_NAME_PATTERN.matcher(tableName).matches();
    }

    private TableMetadata findTable(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        String tablePattern = escapePattern(metadata, tableName);
        TableMetadata disallowedTable = null;
        try (ResultSet tables = metadata.getTables(catalog, null, tablePattern, null)) {
            while (tables.next()) {
                if (!catalog.equals(tables.getString("TABLE_CAT"))
                        || !tableName.equals(tables.getString("TABLE_NAME"))) {
                    continue;
                }
                TableMetadata candidate = new TableMetadata(
                        tables.getString("TABLE_NAME"),
                        tables.getString("TABLE_TYPE"),
                        tables.getString("REMARKS"));
                if ("TABLE".equals(candidate.type())) {
                    return candidate;
                }
                disallowedTable = candidate;
            }
        }
        return disallowedTable;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(catalog, null, tableName)) {
            while (keys.next()) {
                if (!catalog.equals(keys.getString("TABLE_CAT"))
                        || !tableName.equals(keys.getString("TABLE_NAME"))) {
                    continue;
                }
                String columnName = keys.getString("COLUMN_NAME");
                if (columnName == null || columnName.isBlank()) {
                    throw new SQLException("主键元信息缺少字段名称");
                }
                primaryKeys.add(columnName);
            }
        }
        return primaryKeys;
    }

    private List<ColumnMetadata> readColumns(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        String tablePattern = escapePattern(metadata, tableName);
        try (ResultSet result = metadata.getColumns(catalog, null, tablePattern, "%")) {
            while (result.next()) {
                if (!catalog.equals(result.getString("TABLE_CAT"))
                        || !tableName.equals(result.getString("TABLE_NAME"))) {
                    continue;
                }
                String columnName = requiredText(result, "COLUMN_NAME");
                String typeName = requiredText(result, "TYPE_NAME");
                Integer ordinalPosition = optionalInteger(result.getObject("ORDINAL_POSITION"));
                if (ordinalPosition == null || ordinalPosition <= 0) {
                    throw new SQLException("字段元信息缺少有效顺序");
                }
                Integer nullableCode = optionalInteger(result.getObject("NULLABLE"));
                columns.add(new ColumnMetadata(
                        columnName,
                        typeName,
                        optionalInteger(result.getObject("COLUMN_SIZE")),
                        optionalInteger(result.getObject("DECIMAL_DIGITS")),
                        toNullable(nullableCode),
                        result.getString("COLUMN_DEF"),
                        ordinalPosition));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnMetadata::ordinalPosition));
        return List.copyOf(columns);
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
            throw new SQLException("字段元信息缺少必要值");
        }
        return value;
    }

    private Integer optionalInteger(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new SQLException("字段元信息中的数字格式无效", e);
        }
    }

    private Boolean toNullable(Integer nullableCode) {
        if (nullableCode == null || nullableCode == DatabaseMetaData.columnNullableUnknown) {
            return null;
        }
        return nullableCode == DatabaseMetaData.columnNullable;
    }

    private List<ForeignKeyMetadata> readForeignKeys(
            ResultSet keys,
            String catalog,
            String tableName,
            ForeignKeyDirection direction) throws SQLException {
        try (keys) {
            Map<String, MutableForeignKey> groups = new LinkedHashMap<>();
            int unnamedGroup = 0;
            String previousUnnamedSignature = null;
            short previousSequence = 0;
            while (keys.next()) {
                String currentCatalog = direction == ForeignKeyDirection.IMPORTED
                        ? keys.getString("FKTABLE_CAT")
                        : keys.getString("PKTABLE_CAT");
                String currentTable = direction == ForeignKeyDirection.IMPORTED
                        ? keys.getString("FKTABLE_NAME")
                        : keys.getString("PKTABLE_NAME");
                if (!catalog.equals(currentCatalog) || !tableName.equals(currentTable)) {
                    continue;
                }

                String foreignKeyName = keys.getString("FK_NAME");
                short keySequence = requiredPositiveShort(keys.getObject("KEY_SEQ"), "外键字段顺序");
                String referencedTable = requiredText(keys, "PKTABLE_NAME");
                String referencingTable = requiredText(keys, "FKTABLE_NAME");
                String signature = referencedTable + "\u0000" + referencingTable;
                String groupKey;
                if (foreignKeyName != null && !foreignKeyName.isBlank()) {
                    groupKey = "named:" + signature + "\u0000" + foreignKeyName;
                } else {
                    if (!signature.equals(previousUnnamedSignature) || keySequence <= previousSequence) {
                        unnamedGroup++;
                    }
                    groupKey = "unnamed:" + unnamedGroup;
                    previousUnnamedSignature = signature;
                    previousSequence = keySequence;
                }

                MutableForeignKey group = groups.get(groupKey);
                if (group == null) {
                    group = new MutableForeignKey(
                            foreignKeyName,
                            referencedTable,
                            referencingTable,
                            ruleName(keys.getObject("UPDATE_RULE")),
                            ruleName(keys.getObject("DELETE_RULE")));
                    groups.put(groupKey, group);
                }
                group.columns.add(new ForeignKeyColumn(
                        requiredText(keys, "FKCOLUMN_NAME"),
                        requiredText(keys, "PKCOLUMN_NAME"),
                        keySequence));
            }

            List<ForeignKeyMetadata> result = new ArrayList<>();
            for (MutableForeignKey group : groups.values()) {
                group.columns.sort(Comparator.comparingInt(ForeignKeyColumn::sequence));
                result.add(new ForeignKeyMetadata(
                        group.name,
                        group.referencedTable,
                        group.referencingTable,
                        List.copyOf(group.columns),
                        group.updateRule,
                        group.deleteRule));
            }
            return List.copyOf(result);
        }
    }

    private List<IndexMetadata> readIndexes(
            DatabaseMetaData metadata,
            String catalog,
            String tableName) throws SQLException {
        Map<String, MutableIndex> groups = new LinkedHashMap<>();
        try (ResultSet indexes = metadata.getIndexInfo(catalog, null, tableName, false, false)) {
            while (indexes.next()) {
                String rowCatalog = indexes.getString("TABLE_CAT");
                String rowTable = indexes.getString("TABLE_NAME");
                if (!catalog.equals(rowCatalog) || !tableName.equals(rowTable)) {
                    continue;
                }
                Integer type = optionalInteger(indexes.getObject("TYPE"));
                String indexName = indexes.getString("INDEX_NAME");
                String columnName = indexes.getString("COLUMN_NAME");
                if ((type != null && type == DatabaseMetaData.tableIndexStatistic)
                        || indexName == null || indexName.isBlank()
                        || columnName == null || columnName.isBlank()) {
                    continue;
                }
                Integer position = optionalInteger(indexes.getObject("ORDINAL_POSITION"));
                if (position == null || position <= 0) {
                    throw new SQLException("索引元信息缺少有效字段顺序");
                }
                boolean nonUnique = indexes.getBoolean("NON_UNIQUE");
                if (indexes.wasNull()) {
                    throw new SQLException("索引元信息缺少唯一性标记");
                }
                boolean unique = !nonUnique;
                MutableIndex group = groups.computeIfAbsent(indexName,
                        ignored -> new MutableIndex(indexName, unique));
                if (group.unique != unique) {
                    throw new SQLException("同一索引的唯一性元信息不一致");
                }
                group.columns.add(new IndexColumn(columnName, position));
            }
        }

        List<IndexMetadata> result = new ArrayList<>();
        for (MutableIndex group : groups.values()) {
            group.columns.sort(Comparator.comparingInt(IndexColumn::position));
            result.add(new IndexMetadata(
                    group.name,
                    group.unique,
                    group.columns.stream().map(IndexColumn::name).toList()));
        }
        return List.copyOf(result);
    }

    private short requiredPositiveShort(Object value, String label) throws SQLException {
        Integer parsed = optionalInteger(value);
        if (parsed == null || parsed <= 0 || parsed > Short.MAX_VALUE) {
            throw new SQLException(label + "无效");
        }
        return parsed.shortValue();
    }

    private String ruleName(Object value) throws SQLException {
        Integer rule = optionalInteger(value);
        if (rule == null) {
            return null;
        }
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeySetNull -> "SET_NULL";
            case DatabaseMetaData.importedKeyNoAction -> "NO_ACTION";
            case DatabaseMetaData.importedKeySetDefault -> "SET_DEFAULT";
            default -> "UNKNOWN";
        };
    }

    private ObjectNode toJson(
            TableMetadata table,
            List<ColumnMetadata> columns,
            Set<String> primaryKeys,
            List<ForeignKeyMetadata> importedKeys,
            List<ForeignKeyMetadata> exportedKeys,
            List<IndexMetadata> indexes,
            List<String> metadataWarnings) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("table_name", table.name());
        putNullable(content, "table_comment", table.comment());
        var columnArray = content.putArray("columns");
        for (ColumnMetadata column : columns) {
            ObjectNode item = columnArray.addObject();
            item.put("name", column.name());
            item.put("type", column.type());
            putNullable(item, "size", column.size());
            putNullable(item, "decimal_digits", column.decimalDigits());
            putNullable(item, "nullable", column.nullable());
            item.put("primary_key", primaryKeys.contains(column.name()));
            putNullable(item, "default_value", column.defaultValue());
            item.put("ordinal_position", column.ordinalPosition());
        }
        if (importedKeys != null) {
            var importedArray = content.putArray("imported_foreign_keys");
            importedKeys.forEach(key -> writeImportedForeignKey(importedArray.addObject(), key));
        }
        if (exportedKeys != null) {
            var exportedArray = content.putArray("exported_foreign_keys");
            exportedKeys.forEach(key -> writeExportedForeignKey(exportedArray.addObject(), key));
        }
        if (indexes != null) {
            var indexArray = content.putArray("indexes");
            var uniqueArray = content.putArray("unique_indexes");
            for (IndexMetadata index : indexes) {
                ObjectNode item = indexArray.addObject();
                item.put("name", index.name());
                item.put("unique", index.unique());
                var names = item.putArray("columns");
                index.columns().forEach(names::add);
                if (index.unique()) {
                    ObjectNode unique = uniqueArray.addObject();
                    unique.put("name", index.name());
                    var uniqueNames = unique.putArray("columns");
                    index.columns().forEach(uniqueNames::add);
                }
            }
        }
        var warningArray = content.putArray("metadata_warnings");
        metadataWarnings.forEach(warningArray::add);
        return content;
    }

    private void writeImportedForeignKey(ObjectNode target, ForeignKeyMetadata key) {
        putNullable(target, "name", key.name());
        target.put("referenced_table", key.referencedTable());
        var mappings = target.putArray("column_mappings");
        for (ForeignKeyColumn column : key.columns()) {
            ObjectNode mapping = mappings.addObject();
            mapping.put("column", column.referencingColumn());
            mapping.put("referenced_column", column.referencedColumn());
            mapping.put("key_sequence", column.sequence());
        }
        if (key.columns().size() == 1) {
            target.put("column", key.columns().get(0).referencingColumn());
            target.put("referenced_column", key.columns().get(0).referencedColumn());
        }
        putNullable(target, "update_rule", key.updateRule());
        putNullable(target, "delete_rule", key.deleteRule());
    }

    private void writeExportedForeignKey(ObjectNode target, ForeignKeyMetadata key) {
        putNullable(target, "name", key.name());
        target.put("referencing_table", key.referencingTable());
        var mappings = target.putArray("column_mappings");
        for (ForeignKeyColumn column : key.columns()) {
            ObjectNode mapping = mappings.addObject();
            mapping.put("referencing_column", column.referencingColumn());
            mapping.put("column", column.referencedColumn());
            mapping.put("key_sequence", column.sequence());
        }
        if (key.columns().size() == 1) {
            target.put("referencing_column", key.columns().get(0).referencingColumn());
            target.put("column", key.columns().get(0).referencedColumn());
        }
        putNullable(target, "update_rule", key.updateRule());
        putNullable(target, "delete_rule", key.deleteRule());
    }

    private void putNullable(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private void putNullable(ObjectNode target, String field, Integer value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private void putNullable(ObjectNode target, String field, Boolean value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private record TableMetadata(String name, String type, String comment) {
    }

    private record ColumnMetadata(
            String name,
            String type,
            Integer size,
            Integer decimalDigits,
            Boolean nullable,
            String defaultValue,
            int ordinalPosition) {
    }

    private record ForeignKeyMetadata(
            String name,
            String referencedTable,
            String referencingTable,
            List<ForeignKeyColumn> columns,
            String updateRule,
            String deleteRule) {
    }

    private record ForeignKeyColumn(
            String referencingColumn,
            String referencedColumn,
            int sequence) {
    }

    private record IndexMetadata(String name, boolean unique, List<String> columns) {
    }

    private record IndexColumn(String name, int position) {
    }

    private static final class MutableForeignKey {
        private final String name;
        private final String referencedTable;
        private final String referencingTable;
        private final String updateRule;
        private final String deleteRule;
        private final List<ForeignKeyColumn> columns = new ArrayList<>();

        private MutableForeignKey(
                String name,
                String referencedTable,
                String referencingTable,
                String updateRule,
                String deleteRule) {
            this.name = name;
            this.referencedTable = referencedTable;
            this.referencingTable = referencingTable;
            this.updateRule = updateRule;
            this.deleteRule = deleteRule;
        }
    }

    private static final class MutableIndex {
        private final String name;
        private final boolean unique;
        private final List<IndexColumn> columns = new ArrayList<>();

        private MutableIndex(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }

    private enum ForeignKeyDirection {
        IMPORTED,
        EXPORTED
    }
}
