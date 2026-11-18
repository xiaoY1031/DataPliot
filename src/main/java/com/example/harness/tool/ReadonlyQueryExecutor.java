package com.example.harness.tool;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 统一执行当前数据源中的只读查询，并为工具展示和 Python 输入提供不同溢出策略。 */
@Component
public class ReadonlyQueryExecutor {

    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");
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

    private final DataSource dataSource;
    private final ReadonlySqlValidator validator;
    private final DatabaseResultSetMapper resultSetMapper;
    private final ObjectMapper objectMapper;

    public ReadonlyQueryExecutor(
            DataSource dataSource,
            ReadonlySqlValidator validator,
            DatabaseResultSetMapper resultSetMapper,
            ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.validator = validator;
        this.resultSetMapper = resultSetMapper;
        this.objectMapper = objectMapper;
    }

    public ToolResult executeForTool(
            ReadonlySqlValidator.ValidatedQuery query,
            int maxRows,
            int queryTimeoutSeconds) {
        try (Connection connection = openValidatedConnection(query);
             Statement statement = connection.createStatement()) {
            configureStatement(statement, maxRows, queryTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(query.sql())) {
                ObjectNode content = objectMapper.createObjectNode();
                resultSetMapper.map(
                        resultSet,
                        content,
                        maxRows,
                        query.maskedColumnIndexes(),
                        "rowCount");
                return ToolResult.success(content.toString());
            }
        } catch (QueryExecutionException e) {
            return ToolResult.failure(e.errorCode(), e.getMessage());
        } catch (SQLException e) {
            return databaseFailure(e, queryTimeoutSeconds);
        } catch (RuntimeException e) {
            return ToolResult.failure(
                    "SQL_EXECUTION_ERROR",
                    "只读 SQL 执行失败，请检查 SQL 语法、字段名称和数据库访问权限。");
        }
    }

    public AnalysisDataset executeForAnalysis(
            ReadonlySqlValidator.ValidatedQuery query,
            int maxRows,
            int maxBytes,
            int queryTimeoutSeconds) throws QueryExecutionException {
        if (maxRows <= 0 || maxBytes <= 0 || queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("查询限制必须大于 0");
        }
        long startedAt = System.nanoTime();
        try (Connection connection = openValidatedConnection(query);
             Statement statement = connection.createStatement()) {
            configureStatement(statement, maxRows, queryTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(query.sql())) {
                byte[] payload = serializeAnalysisDataset(
                        resultSet, maxRows, maxBytes, query.maskedColumnIndexes());
                return new AnalysisDataset(
                        payload,
                        readRowCount(payload),
                        elapsedMillis(startedAt));
            }
        } catch (QueryExecutionException e) {
            throw e;
        } catch (SQLException e) {
            ToolResult failure = databaseFailure(e, queryTimeoutSeconds);
            throw new QueryExecutionException(failure.errorCode(), failure.content());
        } catch (IOException e) {
            throw new QueryExecutionException(
                    "SQL_EXECUTION_ERROR",
                    "数据库结果无法转换为 Python 分析输入。");
        } catch (RuntimeException e) {
            throw new QueryExecutionException(
                    "SQL_EXECUTION_ERROR",
                    "只读 SQL 执行失败，请检查 SQL 语法、字段名称和数据库访问权限。");
        }
    }

    private Connection openValidatedConnection(
            ReadonlySqlValidator.ValidatedQuery query) throws QueryExecutionException {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException | RuntimeException e) {
            throw new QueryExecutionException(
                    "DATABASE_CONNECTION_ERROR",
                    "无法连接配置的数据源，请检查数据库服务、网络和本地数据源配置。");
        }
        boolean accepted = false;
        try {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                throw new QueryExecutionException(
                        "DATABASE_NOT_SELECTED",
                        "未选择数据库，请配置 DATAPILOT_DB_NAME 后重启应用。");
            }
            if (SYSTEM_DATABASES.contains(catalog.toLowerCase(Locale.ROOT))) {
                throw new QueryExecutionException(
                        "SYSTEM_DATABASE_NOT_ALLOWED",
                        "不允许查询 MySQL 系统数据库，请将 DATAPILOT_DB_NAME 配置为业务数据库。");
            }
            String catalogError = validator.validateCatalogReferences(query, catalog);
            if (catalogError != null) {
                throw new QueryExecutionException(
                        "CROSS_DATABASE_ACCESS_REJECTED", catalogError);
            }
            connection.setReadOnly(true);
            accepted = true;
            return connection;
        } catch (SQLException e) {
            throw new QueryExecutionException(
                    "SQL_EXECUTION_ERROR",
                    "无法准备只读数据库连接。");
        } finally {
            if (!accepted) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 原始安全错误优先。
                }
            }
        }
    }

    private void configureStatement(
            Statement statement, int maxRows, int queryTimeoutSeconds) throws SQLException {
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setMaxRows(maxRows + 1);
        statement.setFetchSize(maxRows + 1);
    }

    private byte[] serializeAnalysisDataset(
            ResultSet resultSet,
            int maxRows,
            int maxBytes,
            Set<Integer> maskedColumnIndexes) throws SQLException, IOException, QueryExecutionException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Column> columns = readColumns(metadata);
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(maxBytes);
        JsonFactory factory = objectMapper.getFactory();
        int rowCount = 0;
        try (JsonGenerator generator = factory.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeArrayFieldStart("columns");
            for (Column column : columns) {
                generator.writeStartObject();
                generator.writeStringField("name", column.outputName());
                generator.writeStringField("type", column.typeName());
                generator.writeNumberField("jdbcType", column.jdbcType());
                if (column.decimal()) {
                    generator.writeStringField("encoding", "DECIMAL_STRING");
                }
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeArrayFieldStart("rows");
            while (resultSet.next()) {
                if (rowCount == maxRows) {
                    throw new QueryExecutionException(
                            "DATASET_LIMIT_EXCEEDED",
                            "查询结果超过 Python 分析允许的最大行数 " + maxRows + "，请缩小查询范围或先聚合。");
                }
                generator.writeStartObject();
                for (Column column : columns) {
                    generator.writeFieldName(column.outputName());
                    writeValue(
                            generator,
                            resultSet,
                            column,
                            maskedColumnIndexes.contains(column.index()),
                            maxBytes);
                }
                generator.writeEndObject();
                rowCount++;
            }
            generator.writeEndArray();
            generator.writeNumberField("rowCount", rowCount);
            generator.writeEndObject();
        } catch (SizeLimitExceededException e) {
            throw new QueryExecutionException(
                    "DATASET_LIMIT_EXCEEDED",
                    "查询结果超过 Python 分析允许的最大输入大小 " + maxBytes + " 字节，请缩小查询范围或先聚合。");
        }
        return output.toByteArray();
    }

    private int readRowCount(byte[] payload) throws IOException {
        return objectMapper.readTree(new ByteArrayInputStream(payload)).path("rowCount").asInt();
    }

    private List<Column> readColumns(ResultSetMetaData metadata) throws SQLException {
        List<Column> columns = new ArrayList<>();
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
            int jdbcType = metadata.getColumnType(index);
            columns.add(new Column(
                    index,
                    outputName,
                    jdbcType,
                    metadata.getColumnTypeName(index),
                    jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL));
        }
        return List.copyOf(columns);
    }

    private void writeValue(
            JsonGenerator generator,
            ResultSet resultSet,
            Column column,
            boolean masked,
            int maxBytes) throws SQLException, IOException, QueryExecutionException {
        if (masked) {
            if (resultSet.getObject(column.index()) == null) {
                generator.writeNull();
            } else {
                generator.writeString(SensitiveFieldPolicy.MASKED_VALUE);
            }
            return;
        }
        if (TEXT_TYPES.contains(column.jdbcType())) {
            writeText(generator, resultSet, column.index(), maxBytes);
            return;
        }
        if (BINARY_TYPES.contains(column.jdbcType())) {
            writeBinary(generator, resultSet, column.index(), maxBytes);
            return;
        }

        Object raw = resultSet.getObject(column.index());
        if (raw == null) {
            generator.writeNull();
        } else if (column.decimal() && raw instanceof BigDecimal value) {
            generator.writeString(value.toPlainString());
        } else if (raw instanceof Double value && !Double.isFinite(value)) {
            generator.writeNull();
        } else if (raw instanceof Float value && !Float.isFinite(value)) {
            generator.writeNull();
        } else if (NUMERIC_TYPES.contains(column.jdbcType())
                && (raw instanceof Number || raw instanceof BigInteger)) {
            generator.writeObject(raw);
        } else if (BOOLEAN_TYPES.contains(column.jdbcType()) && raw instanceof Boolean value) {
            generator.writeBoolean(value);
        } else if (raw instanceof java.sql.Date value) {
            generator.writeString(value.toLocalDate().toString());
        } else if (raw instanceof java.sql.Time value) {
            generator.writeString(value.toLocalTime().toString());
        } else if (raw instanceof java.sql.Timestamp value) {
            generator.writeString(value.toLocalDateTime().toString());
        } else if (raw instanceof TemporalAccessor) {
            generator.writeString(raw.toString());
        } else {
            generator.writeString(String.valueOf(raw));
        }
    }

    private void writeText(
            JsonGenerator generator, ResultSet resultSet, int index, int maxBytes)
            throws SQLException, IOException, QueryExecutionException {
        try (Reader reader = resultSet.getCharacterStream(index)) {
            if (reader == null) {
                generator.writeNull();
                return;
            }
            String value = readBoundedText(reader, maxBytes);
            generator.writeString(value);
        }
    }

    private String readBoundedText(Reader reader, int maxBytes)
            throws IOException, QueryExecutionException {
        StringBuilder value = new StringBuilder(Math.min(maxBytes, 4096));
        char[] buffer = new char[Math.min(1024, maxBytes + 1)];
        int utf8Bytes = 0;
        char pendingHighSurrogate = 0;
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            for (int index = 0; index < count; index++) {
                char current = buffer[index];
                if (pendingHighSurrogate != 0) {
                    if (Character.isLowSurrogate(current)) {
                        utf8Bytes += 4;
                        value.append(pendingHighSurrogate).append(current);
                        pendingHighSurrogate = 0;
                        ensureTextByteLimit(utf8Bytes, maxBytes);
                        continue;
                    }
                    utf8Bytes += 3;
                    value.append(pendingHighSurrogate);
                    pendingHighSurrogate = 0;
                    ensureTextByteLimit(utf8Bytes, maxBytes);
                }
                if (Character.isHighSurrogate(current)) {
                    pendingHighSurrogate = current;
                } else {
                    utf8Bytes += utf8Bytes(current);
                    value.append(current);
                    ensureTextByteLimit(utf8Bytes, maxBytes);
                }
            }
        }
        if (pendingHighSurrogate != 0) {
            utf8Bytes += 3;
            value.append(pendingHighSurrogate);
            ensureTextByteLimit(utf8Bytes, maxBytes);
        }
        return value.toString();
    }

    private int utf8Bytes(char value) {
        if (value <= 0x7F) {
            return 1;
        }
        if (value <= 0x7FF) {
            return 2;
        }
        return 3;
    }

    private void ensureTextByteLimit(int currentBytes, int maxBytes)
            throws QueryExecutionException {
        if (currentBytes > maxBytes) {
            throw new QueryExecutionException(
                    "DATASET_LIMIT_EXCEEDED",
                    "单个文本字段超过 Python 分析输入大小限制，请在 SQL 中缩小字段或聚合数据。");
        }
    }

    private void writeBinary(
            JsonGenerator generator, ResultSet resultSet, int index, int maxBytes)
            throws SQLException, IOException, QueryExecutionException {
        try (InputStream stream = resultSet.getBinaryStream(index)) {
            if (stream == null) {
                generator.writeNull();
                return;
            }
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new QueryExecutionException(
                        "DATASET_LIMIT_EXCEEDED",
                        "单个二进制字段超过 Python 分析输入大小限制，请不要选择该字段。");
            }
            generator.writeString(Base64.getEncoder().encodeToString(bytes));
        }
    }

    private ToolResult databaseFailure(SQLException exception, int timeoutSeconds) {
        if (isTimeout(exception)) {
            return ToolResult.failure(
                    "QUERY_TIMEOUT",
                    "查询执行超过 " + timeoutSeconds + " 秒，已终止。");
        }
        if (exception.getSQLState() != null && exception.getSQLState().startsWith("42")) {
            return ToolResult.failure(
                    "SQL_SYNTAX_ERROR",
                    "SQL 语法、表名或字段名无效，请根据数据库结构修正查询。");
        }
        return ToolResult.failure(
                "SQL_EXECUTION_ERROR",
                "只读 SQL 执行失败，请检查 SQL 语法、字段名称和数据库访问权限。");
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

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record AnalysisDataset(byte[] json, int rowCount, long queryMs) {
        public AnalysisDataset {
            json = json.clone();
        }

        @Override
        public byte[] json() {
            return json.clone();
        }

        public String jsonText() {
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    public static class QueryExecutionException extends Exception {
        private final String errorCode;

        public QueryExecutionException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    private record Column(
            int index, String outputName, int jdbcType, String typeName, boolean decimal) {
    }

    private static class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maxBytes;

        private BoundedByteArrayOutputStream(int maxBytes) {
            super(Math.min(maxBytes, 8192));
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityFor(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            ensureCapacityFor(length);
            super.write(values, offset, length);
        }

        private void ensureCapacityFor(int additionalBytes) {
            if (count > maxBytes - additionalBytes) {
                throw new SizeLimitExceededException();
            }
        }
    }

    private static class SizeLimitExceededException extends RuntimeException {
    }
}
