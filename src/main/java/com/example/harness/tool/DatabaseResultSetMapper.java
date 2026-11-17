package com.example.harness.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 JDBC 结果集转换成受字段长度和整体 UTF-8 字节预算约束的 JSON。 */
@Component
public class DatabaseResultSetMapper {

    static final int MAX_FIELD_LENGTH = 2_000;

    private static final Set<Integer> NUMERIC_TYPES = Set.of(
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL);
    private static final Set<Integer> BOOLEAN_TYPES = Set.of(Types.BOOLEAN, Types.BIT);
    private static final Set<Integer> BINARY_TYPES = Set.of(
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB);
    private static final Set<Integer> TEXT_TYPES = Set.of(
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
            Types.CLOB, Types.NCLOB, Types.SQLXML);

    private final ObjectMapper objectMapper;
    private final int maxResultBytes;

    @Autowired
    public DatabaseResultSetMapper(ObjectMapper objectMapper, DatabaseToolProperties properties) {
        this(objectMapper, properties.getQuery().getMaxResultBytes());
    }

    DatabaseResultSetMapper(ObjectMapper objectMapper, int maxResultBytes) {
        if (maxResultBytes <= 0) {
            throw new IllegalArgumentException("max-result-bytes 必须大于 0");
        }
        this.objectMapper = objectMapper;
        this.maxResultBytes = maxResultBytes;
    }

    ObjectNode map(
            ResultSet resultSet,
            ObjectNode content,
            int maxRows,
            Set<Integer> maskedColumnIndexes,
            String rowCountField) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<ColumnOutput> columns = readColumns(metadata);
        ArrayNode columnArray = content.putArray("columns");
        for (ColumnOutput column : columns) {
            ObjectNode item = columnArray.addObject();
            item.put("name", column.outputName());
            item.put("type", column.typeName());
        }

        ArrayNode rows = content.putArray("rows");
        LinkedHashSet<TruncationReason> reasons = new LinkedHashSet<>();
        int rowCount = 0;
        applySummary(content, rowCountField, rowCount, reasons);
        if (serializedSize(content) > maxResultBytes) {
            reasons.add(TruncationReason.RESULT_SIZE_LIMIT);
            shrinkToBudget(content, columnArray, rows, rowCountField, reasons);
            return content;
        }

        while (resultSet.next()) {
            if (rowCount == maxRows) {
                reasons.add(TruncationReason.ROW_LIMIT);
                break;
            }

            ObjectNode row = objectMapper.createObjectNode();
            boolean fieldTruncated = false;
            for (ColumnOutput column : columns) {
                ValueOutput value = readValue(
                        resultSet,
                        column,
                        maskedColumnIndexes.contains(column.index()));
                row.set(column.outputName(), value.value());
                fieldTruncated |= value.truncated();
            }
            if (fieldTruncated) {
                reasons.add(TruncationReason.FIELD_LENGTH_LIMIT);
            }

            rows.add(row);
            applySummary(content, rowCountField, rowCount + 1, reasons);
            if (serializedSize(content) > maxResultBytes) {
                rows.remove(rows.size() - 1);
                reasons.add(TruncationReason.RESULT_SIZE_LIMIT);
                break;
            }
            rowCount++;
        }

        applySummary(content, rowCountField, rowCount, reasons);
        shrinkToBudget(content, columnArray, rows, rowCountField, reasons);
        return content;
    }

    int serializedSize(JsonNode content) {
        try {
            return objectMapper.writeValueAsBytes(content).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法计算数据库工具结果大小", e);
        }
    }

    private void shrinkToBudget(
            ObjectNode content,
            ArrayNode columns,
            ArrayNode rows,
            String rowCountField,
            LinkedHashSet<TruncationReason> reasons) {
        while (serializedSize(content) > maxResultBytes && !rows.isEmpty()) {
            rows.remove(rows.size() - 1);
            reasons.add(TruncationReason.RESULT_SIZE_LIMIT);
            applySummary(content, rowCountField, rows.size(), reasons);
        }
        while (serializedSize(content) > maxResultBytes && !columns.isEmpty()) {
            columns.remove(columns.size() - 1);
            reasons.add(TruncationReason.RESULT_SIZE_LIMIT);
            applySummary(content, rowCountField, 0, reasons);
        }
        if (serializedSize(content) > maxResultBytes) {
            throw new IllegalArgumentException(
                    "max-result-bytes 过小，无法容纳数据库工具的最小结果结构");
        }
    }

    private void applySummary(
            ObjectNode content,
            String rowCountField,
            int rowCount,
            LinkedHashSet<TruncationReason> reasons) {
        content.put(rowCountField, rowCount);
        content.put("truncated", !reasons.isEmpty());
        ArrayNode reasonArray = content.putArray("truncationReasons");
        reasons.forEach(reason -> reasonArray.add(reason.name()));
    }

    private List<ColumnOutput> readColumns(ResultSetMetaData metadata) throws SQLException {
        List<ColumnOutput> result = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            if (label == null || label.isBlank()) {
                label = metadata.getColumnName(index);
            }
            if (label == null || label.isBlank()) {
                label = "column_" + index;
            }
            int occurrence = occurrences.merge(label, 1, Integer::sum);
            String outputName = occurrence == 1 ? label : label + "_" + occurrence;
            result.add(new ColumnOutput(
                    index,
                    outputName,
                    metadata.getColumnType(index),
                    metadata.getColumnTypeName(index)));
        }
        return List.copyOf(result);
    }

    private ValueOutput readValue(
            ResultSet resultSet,
            ColumnOutput column,
            boolean masked) throws SQLException {
        if (masked) {
            return new ValueOutput(
                    resultSet.getObject(column.index()) == null
                            ? NullNode.getInstance()
                            : TextNode.valueOf(SensitiveFieldPolicy.MASKED_VALUE),
                    false);
        }
        if (TEXT_TYPES.contains(column.jdbcType())) {
            return readText(resultSet, column.index());
        }
        if (BINARY_TYPES.contains(column.jdbcType())) {
            return readBinary(resultSet, column.index());
        }

        Object raw = resultSet.getObject(column.index());
        if (raw == null) {
            return new ValueOutput(NullNode.getInstance(), false);
        }
        if (NUMERIC_TYPES.contains(column.jdbcType())
                && (raw instanceof Number || raw instanceof BigInteger || raw instanceof BigDecimal)) {
            return new ValueOutput(objectMapper.valueToTree(raw), false);
        }
        if (BOOLEAN_TYPES.contains(column.jdbcType()) && raw instanceof Boolean value) {
            return new ValueOutput(objectMapper.getNodeFactory().booleanNode(value), false);
        }
        if (raw instanceof java.sql.Date value) {
            return text(value.toLocalDate().toString());
        }
        if (raw instanceof java.sql.Time value) {
            return text(value.toLocalTime().toString());
        }
        if (raw instanceof java.sql.Timestamp value) {
            return text(value.toLocalDateTime().toString());
        }
        if (raw instanceof TemporalAccessor) {
            return text(raw.toString());
        }
        return truncate(String.valueOf(raw));
    }

    private ValueOutput readText(ResultSet resultSet, int index) throws SQLException {
        try (Reader reader = resultSet.getCharacterStream(index)) {
            if (reader == null) {
                return new ValueOutput(NullNode.getInstance(), false);
            }
            return truncate(readCharacters(reader));
        } catch (IOException e) {
            throw new SQLException("读取文本字段失败", e);
        }
    }

    private String readCharacters(Reader reader) throws IOException {
        int limit = MAX_FIELD_LENGTH + 1;
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[Math.min(512, limit)];
        while (content.length() < limit) {
            int count = reader.read(buffer, 0, Math.min(buffer.length, limit - content.length()));
            if (count < 0) {
                break;
            }
            content.append(buffer, 0, count);
        }
        return content.toString();
    }

    private ValueOutput readBinary(ResultSet resultSet, int index) throws SQLException {
        int maxBytes = MAX_FIELD_LENGTH * 3 / 4 + 1;
        try (InputStream stream = resultSet.getBinaryStream(index)) {
            if (stream == null) {
                return new ValueOutput(NullNode.getInstance(), false);
            }
            byte[] bytes = stream.readNBytes(maxBytes);
            return truncate(Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            throw new SQLException("读取二进制字段失败", e);
        }
    }

    private ValueOutput text(String value) {
        return new ValueOutput(TextNode.valueOf(value), false);
    }

    private ValueOutput truncate(String value) {
        if (value.length() <= MAX_FIELD_LENGTH) {
            return text(value);
        }
        return new ValueOutput(
                TextNode.valueOf(value.substring(0, MAX_FIELD_LENGTH) + "…[已截断]"),
                true);
    }

    enum TruncationReason {
        ROW_LIMIT,
        RESULT_SIZE_LIMIT,
        FIELD_LENGTH_LIMIT
    }

    private record ColumnOutput(int index, String outputName, int jdbcType, String typeName) {
    }

    private record ValueOutput(JsonNode value, boolean truncated) {
    }
}
