package com.example.harness.tool;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.StatementFeatureVisitor;
import net.sf.jsqlparser.statement.StatementFeatures;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.StmtFeature;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 对模型生成的 SQL 做只读 AST 校验，并保留执行所需的投影保护信息。 */
@Component
public class ReadonlySqlValidator {

    public static final int MAX_SQL_LENGTH = 20_000;

    private static final Set<String> BLOCKED_FUNCTIONS = Set.of(
            "benchmark", "get_lock", "is_free_lock", "is_used_lock",
            "load_file", "master_pos_wait", "release_all_locks",
            "release_lock", "sleep", "sys_exec", "sys_eval");
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "abs", "acos", "adddate", "ascii", "asin", "atan", "atan2",
            "avg", "bin", "bit_and", "bit_count", "bit_or", "bit_xor",
            "cast", "ceil", "ceiling", "char_length", "character_length",
            "coalesce", "concat", "concat_ws", "convert", "cos", "cot",
            "count", "crc32", "curdate", "current_date", "current_time",
            "current_timestamp", "curtime", "date", "date_add", "date_format",
            "date_sub", "datediff", "day", "dayname", "dayofmonth",
            "dayofweek", "dayofyear", "degrees", "dense_rank", "elt", "exp",
            "extract", "field", "find_in_set", "first_value", "floor", "format",
            "from_days", "from_unixtime", "greatest", "group_concat", "hex",
            "hour", "if", "ifnull", "instr", "json_array", "json_array_length",
            "json_contains", "json_contains_path", "json_depth", "json_extract",
            "json_keys", "json_length", "json_object", "json_pretty", "json_quote",
            "json_search", "json_type", "json_unquote", "lag", "last_day",
            "last_value", "lcase", "lead", "least", "left", "length", "ln",
            "locate", "log", "log10", "log2", "lower", "lpad", "ltrim",
            "makedate", "maketime", "max", "md5", "microsecond", "mid", "min",
            "minute", "mod", "month", "monthname", "now", "nth_value",
            "ntile", "nullif", "oct", "octet_length", "percent_rank", "pi",
            "position", "pow", "power", "quarter", "radians", "rand", "rank",
            "regexp_instr", "regexp_like", "regexp_replace", "regexp_substr",
            "repeat", "replace", "reverse", "right", "round", "row_number",
            "rpad", "rtrim", "second", "sha1", "sha2", "sign", "sin",
            "space", "sqrt", "std", "stddev", "stddev_pop", "stddev_samp",
            "str_to_date", "strcmp", "substr", "substring", "substring_index",
            "sum", "tan", "time", "time_format", "timediff", "timestamp",
            "timestampadd", "timestampdiff", "to_days", "to_seconds", "trim",
            "truncate", "ucase", "unhex", "unix_timestamp", "upper", "utc_date",
            "utc_time", "utc_timestamp", "variance", "var_pop", "var_samp",
            "week", "weekday", "weekofyear", "year", "yearweek");
    private static final Pattern FORBIDDEN_MYSQL_SYNTAX = Pattern.compile(
            "(?is)(?:\\binto\\s+(?:out|dump)file\\b"
                    + "|\\binto\\s+@"
                    + "|\\block\\s+in\\s+share\\s+mode\\b"
                    + "|\\bfor\\s+(?:update|share)\\b"
                    + "|:=|@)");
    private static final Pattern EXECUTABLE_COMMENT = Pattern.compile("(?s)/\\*[!+]");

    private final SensitiveFieldPolicy sensitiveFieldPolicy;

    public ReadonlySqlValidator(SensitiveFieldPolicy sensitiveFieldPolicy) {
        this.sensitiveFieldPolicy = sensitiveFieldPolicy;
    }

    public ValidatedQuery validate(String sql) throws SqlValidationException {
        if (sql == null || sql.isBlank()) {
            throw new SqlValidationException("INVALID_SQL", "sql 不能为空。");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new SqlValidationException(
                    "INVALID_SQL", "SQL 长度不能超过 " + MAX_SQL_LENGTH + " 个字符。");
        }
        try {
            return validateParsedSql(sql);
        } catch (UnsafeSqlException e) {
            throw new SqlValidationException("SQL_SAFETY_REJECTED", e.getMessage());
        } catch (JSQLParserException | RuntimeException e) {
            throw new SqlValidationException(
                    "SQL_PARSE_ERROR",
                    "无法解析 SQL，只有能够明确验证为安全的单条 SELECT 查询才允许执行。");
        }
    }

    public String validateCatalogReferences(ValidatedQuery query, String catalog) {
        String current = unquoteIdentifier(catalog);
        for (String reference : query.tableReferences()) {
            List<String> parts = splitQualifiedName(reference);
            if (parts.size() > 2) {
                return "不允许访问当前数据库之外的对象：" + reference;
            }
            if (parts.size() == 2
                    && !current.equalsIgnoreCase(unquoteIdentifier(parts.get(0)))) {
                return "不允许访问其他数据库：" + reference;
            }
        }
        return null;
    }

    private ValidatedQuery validateParsedSql(String sql)
            throws JSQLParserException, UnsafeSqlException {
        if (EXECUTABLE_COMMENT.matcher(sql).find()) {
            throw new UnsafeSqlException("不允许使用 MySQL 可执行注释或优化器提示。");
        }
        if (FORBIDDEN_MYSQL_SYNTAX.matcher(maskQuotedContentAndComments(sql)).find()) {
            throw new UnsafeSqlException(
                    "不允许使用 SELECT INTO、OUTFILE、DUMPFILE、变量写入或锁定查询。");
        }
        Statements statements = CCJSqlParserUtil.parseStatements(sql);
        if (statements.size() != 1) {
            throw new UnsafeSqlException("只允许执行一条 SQL 查询。");
        }
        if (!(statements.get(0) instanceof Select select)) {
            throw new UnsafeSqlException("只允许执行 SELECT 查询。");
        }

        StatementFeatures features = StatementFeatureVisitor.analyse(
                select,
                functionName -> functionName != null
                        && ALLOWED_FUNCTIONS.contains(functionName.toLowerCase(Locale.ROOT)));
        if (!features.returnsResultSet()
                || features.modifiesData()
                || features.modifiesSchema()
                || features.may(StmtFeature.MODIFIES_SESSION)
                || features.may(StmtFeature.MODIFIES_TRANSACTION)
                || features.isOpaque()
                || !features.getUnresolvedReferences().isEmpty()) {
            throw new UnsafeSqlException(
                    "SQL 包含无法确认安全或可能修改数据、Schema、会话或事务的操作。");
        }

        validateSelectTree(select);
        SensitiveSqlProjectionAnalyzer.ProjectionPlan projectionPlan;
        try {
            projectionPlan = new SensitiveSqlProjectionAnalyzer(sensitiveFieldPolicy).analyze(select);
        } catch (SensitiveSqlProjectionAnalyzer.SensitiveAccessException e) {
            throw new UnsafeSqlException(e.getMessage());
        }

        String normalized = select.toString();
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String function : BLOCKED_FUNCTIONS) {
            if (lower.matches("(?s).*\\b" + function + "\\s*\\(.*")) {
                throw new UnsafeSqlException(
                        "不允许调用可能访问文件、加锁或长时间阻塞的函数：" + function);
            }
        }

        Set<String> tableReferences = new TablesNamesFinder<Void>()
                .getTables((net.sf.jsqlparser.statement.Statement) select);
        return new ValidatedQuery(
                normalized,
                Set.copyOf(tableReferences),
                projectionPlan.maskedColumnIndexes());
    }

    private void validateSelectTree(Select select) throws UnsafeSqlException {
        if (select.getForClause() != null
                || select.getForUpdate() != null
                || select.getForMode() != null
                || select.getForUpdateTable() != null
                || (select.getForUpdateTables() != null && !select.getForUpdateTables().isEmpty())
                || select.isSkipLocked()
                || select.getWait() != null) {
            throw new UnsafeSqlException(
                    "不允许执行 FOR UPDATE、LOCK IN SHARE MODE 或其他锁定查询。");
        }

        if (select.getWithItemsList() != null) {
            for (WithItem<?> withItem : select.getWithItemsList()) {
                if (withItem.getSelect() == null) {
                    throw new UnsafeSqlException("WITH 子句只能包含只读 SELECT。");
                }
                validateSelectTree(withItem.getSelect().getSelect());
            }
        }

        if (select instanceof PlainSelect plainSelect) {
            if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
                    || plainSelect.getIntoTempTable() != null
                    || plainSelect.getMySqlSelectIntoClause() != null
                    || (plainSelect.getSettings() != null && !plainSelect.getSettings().isEmpty())) {
                throw new UnsafeSqlException(
                        "不允许使用 SELECT INTO、OUTFILE、DUMPFILE 或变量写入。");
            }
        } else if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                validateSelectTree(child);
            }
        } else if (select instanceof ParenthesedSelect parenthesedSelect) {
            validateSelectTree(parenthesedSelect.getSelect());
        } else {
            throw new UnsafeSqlException("暂不支持该 SELECT 结构，无法确认其只读安全性。");
        }
    }


    private String maskQuotedContentAndComments(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    masked.append(current);
                } else {
                    masked.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    masked.append("  ");
                    index++;
                    blockComment = false;
                } else {
                    masked.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                masked.append(' ');
                if (current == '\\' && index + 1 < sql.length()) {
                    masked.append(' ');
                    index++;
                } else if (current == quote) {
                    if (next == quote) {
                        masked.append(' ');
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '-' && next == '-') {
                masked.append("  ");
                index++;
                lineComment = true;
            } else if (current == '#') {
                masked.append(' ');
                lineComment = true;
            } else if (current == '/' && next == '*') {
                masked.append("  ");
                index++;
                blockComment = true;
            } else if (current == '\'' || current == '"' || current == 96) {
                masked.append(' ');
                quote = current;
            } else {
                masked.append(current);
            }
        }
        return masked.toString();
    }

    private List<String> splitQualifiedName(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            if ((value == 96 || value == '"') && (quote == 0 || quote == value)) {
                quote = quote == 0 ? value : 0;
                current.append(value);
            } else if (value == '.' && quote == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private String unquoteIdentifier(String value) {
        String trimmed = value == null ? "" : value.trim();
        String backtick = String.valueOf((char) 96);
        if (trimmed.length() >= 2
                && ((trimmed.startsWith(backtick) && trimmed.endsWith(backtick))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record ValidatedQuery(
            String sql,
            Set<String> tableReferences,
            Set<Integer> maskedColumnIndexes) {
    }

    public static class SqlValidationException extends Exception {
        private final String errorCode;

        private SqlValidationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    private static class UnsafeSqlException extends Exception {
        private UnsafeSqlException(String message) {
            super(message);
        }
    }
}
