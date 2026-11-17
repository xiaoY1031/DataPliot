package com.example.harness.tool;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 根据 SQL AST 追踪投影字段来源，避免通过字段别名绕过敏感字段策略。 */
final class SensitiveSqlProjectionAnalyzer {

    private static final Set<String> NON_VALUE_AGGREGATES = Set.of(
            "count", "sum", "avg", "std", "stddev", "stddev_pop", "stddev_samp",
            "variance", "var_pop", "var_samp", "bit_and", "bit_or", "bit_xor");

    private final SensitiveFieldPolicy policy;

    SensitiveSqlProjectionAnalyzer(SensitiveFieldPolicy policy) {
        this.policy = policy;
    }

    ProjectionPlan analyze(Select select) throws SensitiveAccessException {
        if (!policy.hasRules()) {
            return new ProjectionPlan(Set.of());
        }
        List<OutputField> outputs = analyzeSelect(select, Map.of());
        LinkedHashSet<Integer> maskedIndexes = new LinkedHashSet<>();
        for (int index = 0; index < outputs.size(); index++) {
            Exposure exposure = outputs.get(index).exposure();
            if (exposure == Exposure.DENY) {
                throw new SensitiveAccessException("查询试图返回禁止访问的敏感字段原值。");
            }
            if (exposure == Exposure.UNSAFE_EXPRESSION) {
                throw new SensitiveAccessException(
                        "查询中的复杂表达式可能暴露敏感字段原值，无法确认安全，已拒绝执行。");
            }
            if (exposure == Exposure.MASK) {
                maskedIndexes.add(index + 1);
            }
        }
        return new ProjectionPlan(Set.copyOf(maskedIndexes));
    }

    private List<OutputField> analyzeSelect(
            Select select,
            Map<String, Relation> inheritedCtes) throws SensitiveAccessException {
        Map<String, Relation> ctes = new LinkedHashMap<>(inheritedCtes);
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getSelect() == null || item.getSelect().getSelect() == null) {
                    throw new SensitiveAccessException("WITH 子句结构无法进行敏感字段来源分析。");
                }
                List<OutputField> fields = analyzeSelect(item.getSelect().getSelect(), ctes);
                if (item.getWithItemList() != null && !item.getWithItemList().isEmpty()) {
                    if (item.getWithItemList().size() != fields.size()) {
                        throw new SensitiveAccessException(
                                "WITH 输出列别名数量与查询字段数量不一致，无法确认敏感字段来源。");
                    }
                    List<OutputField> renamedFields = new ArrayList<>();
                    for (int index = 0; index < fields.size(); index++) {
                        String alias = item.getWithItemList().get(index).getUnquotedAliasName();
                        if (alias == null || alias.isBlank()) {
                            throw new SensitiveAccessException("WITH 输出列别名无效，无法确认敏感字段来源。");
                        }
                        renamedFields.add(new OutputField(
                                normalize(alias), fields.get(index).exposure()));
                    }
                    fields = List.copyOf(renamedFields);
                }
                ctes.put(normalize(item.getUnquotedAliasName()), new DerivedRelation(fields));
            }
        }

        if (select instanceof PlainSelect plainSelect) {
            return analyzePlainSelect(plainSelect, ctes);
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return analyzeSelect(parenthesedSelect.getSelect(), ctes);
        }
        if (select instanceof SetOperationList setOperationList) {
            return analyzeSetOperation(setOperationList, ctes);
        }
        throw new SensitiveAccessException("该 SELECT 结构无法可靠追踪敏感字段来源。");
    }

    private List<OutputField> analyzePlainSelect(
            PlainSelect select,
            Map<String, Relation> ctes) throws SensitiveAccessException {
        Scope scope = new Scope();
        addFromItem(scope, select.getFromItem(), ctes);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                addFromItem(scope, join.getRightItem(), ctes);
            }
        }

        List<OutputField> outputs = new ArrayList<>();
        int generatedName = 1;
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns) {
                throw new SensitiveAccessException(
                        "启用敏感字段策略后必须显式列出查询字段，不能使用 SELECT * 或 table.*。");
            }
            Exposure exposure = classifyExpression(expression, scope, ctes);
            String outputName = item.getUnquotedAliasName();
            if ((outputName == null || outputName.isBlank()) && expression instanceof Column column) {
                outputName = column.getUnquotedColumnName();
            }
            if (outputName == null || outputName.isBlank()) {
                outputName = "expression_" + generatedName;
            }
            outputs.add(new OutputField(normalize(outputName), exposure));
            generatedName++;
        }
        return List.copyOf(outputs);
    }

    private List<OutputField> analyzeSetOperation(
            SetOperationList select,
            Map<String, Relation> ctes) throws SensitiveAccessException {
        List<OutputField> merged = null;
        for (Select child : select.getSelects()) {
            List<OutputField> fields = analyzeSelect(child, ctes);
            if (merged == null) {
                merged = new ArrayList<>(fields);
                continue;
            }
            if (merged.size() != fields.size()) {
                throw new SensitiveAccessException("集合查询各分支的字段数量不一致，无法确认敏感字段来源。");
            }
            for (int index = 0; index < merged.size(); index++) {
                OutputField current = merged.get(index);
                merged.set(index, new OutputField(
                        current.name(),
                        merge(current.exposure(), fields.get(index).exposure())));
            }
        }
        return merged == null ? List.of() : List.copyOf(merged);
    }

    private void addFromItem(
            Scope scope,
            FromItem fromItem,
            Map<String, Relation> ctes) throws SensitiveAccessException {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            String tableName = normalize(table.getName());
            Relation relation = ctes.getOrDefault(tableName, new BaseRelation());
            String alias = table.getAlias() == null
                    ? tableName
                    : normalize(table.getAlias().getUnquotedName());
            scope.add(alias, relation);
            if (!alias.equals(tableName)) {
                scope.addQualifier(tableName, relation);
            }
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            List<OutputField> fields = analyzeSelect(parenthesedSelect.getSelect(), ctes);
            if (parenthesedSelect.getAlias() == null) {
                throw new SensitiveAccessException("子查询缺少别名，无法可靠追踪敏感字段来源。");
            }
            scope.add(normalize(parenthesedSelect.getAlias().getUnquotedName()),
                    new DerivedRelation(fields));
            return;
        }
        if (fromItem instanceof ParenthesedFromItem) {
            throw new SensitiveAccessException("复杂 FROM 结构无法可靠追踪敏感字段来源。");
        }
        throw new SensitiveAccessException("FROM 子句包含无法追踪字段来源的数据源。");
    }

    private Exposure classifyExpression(
            Expression expression,
            Scope scope,
            Map<String, Relation> ctes) throws SensitiveAccessException {
        if (expression instanceof Column column) {
            return scope.resolve(column, policy);
        }
        if (expression instanceof ParenthesedSelect parenthesedSelect) {
            List<OutputField> fields = analyzeSelect(parenthesedSelect.getSelect(), ctes);
            if (fields.size() != 1) {
                return Exposure.UNSAFE_EXPRESSION;
            }
            return fields.get(0).exposure();
        }
        if (expression instanceof Function function
                && NON_VALUE_AGGREGATES.contains(normalize(function.getName()))) {
            return Exposure.ALLOW;
        }

        Set<Exposure> referenced = collectSensitiveReferences(expression, scope, ctes);
        return referenced.isEmpty() ? Exposure.ALLOW : Exposure.UNSAFE_EXPRESSION;
    }

    private Set<Exposure> collectSensitiveReferences(
            Expression expression,
            Scope scope,
            Map<String, Relation> ctes) throws SensitiveAccessException {
        LinkedHashSet<Exposure> result = new LinkedHashSet<>();
        try {
            expression.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(Column column, S context) {
                    Exposure exposure = scope.resolve(column, policy);
                    if (exposure != Exposure.ALLOW) {
                        result.add(exposure);
                    }
                    return null;
                }

                @Override
                public <S> Void visit(ParenthesedSelect select, S context) {
                    try {
                        for (OutputField field : analyzeSelect(select.getSelect(), ctes)) {
                            if (field.exposure() != Exposure.ALLOW) {
                                result.add(field.exposure());
                            }
                        }
                    } catch (SensitiveAccessException e) {
                        throw new AnalysisRuntimeException(e);
                    }
                    return null;
                }
            }, null);
        } catch (AnalysisRuntimeException e) {
            throw e.cause;
        }
        return Set.copyOf(result);
    }

    private Exposure merge(Exposure left, Exposure right) {
        if (left == Exposure.UNSAFE_EXPRESSION || right == Exposure.UNSAFE_EXPRESSION) {
            return Exposure.UNSAFE_EXPRESSION;
        }
        if (left == Exposure.DENY || right == Exposure.DENY) {
            return Exposure.DENY;
        }
        if (left == Exposure.MASK || right == Exposure.MASK) {
            return Exposure.MASK;
        }
        return Exposure.ALLOW;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record ProjectionPlan(Set<Integer> maskedColumnIndexes) {
    }

    static class SensitiveAccessException extends Exception {
        SensitiveAccessException(String message) {
            super(message);
        }
    }

    private enum Exposure {
        ALLOW,
        MASK,
        DENY,
        UNSAFE_EXPRESSION
    }

    private record OutputField(String name, Exposure exposure) {
    }

    private interface Relation {
        Exposure resolve(String fieldName, SensitiveFieldPolicy policy);
    }

    private static final class BaseRelation implements Relation {
        @Override
        public Exposure resolve(String fieldName, SensitiveFieldPolicy policy) {
            return from(policy.actionFor(fieldName));
        }
    }

    private static final class DerivedRelation implements Relation {
        private final Map<String, Exposure> outputs = new HashMap<>();

        private DerivedRelation(List<OutputField> fields) {
            fields.forEach(field -> outputs.merge(
                    field.name(), field.exposure(), SensitiveSqlProjectionAnalyzer::mergeStatic));
        }

        @Override
        public Exposure resolve(String fieldName, SensitiveFieldPolicy policy) {
            return outputs.getOrDefault(normalize(fieldName), Exposure.ALLOW);
        }
    }

    private static final class Scope {
        private final Map<String, Relation> qualifiers = new HashMap<>();
        private final List<Relation> relations = new ArrayList<>();

        void add(String qualifier, Relation relation) {
            qualifiers.put(qualifier, relation);
            relations.add(relation);
        }

        void addQualifier(String qualifier, Relation relation) {
            qualifiers.put(qualifier, relation);
        }

        Exposure resolve(Column column, SensitiveFieldPolicy policy) {
            String fieldName = column.getUnquotedColumnName();
            SensitiveFieldPolicy.Action directAction = policy.actionFor(fieldName);
            if (directAction != SensitiveFieldPolicy.Action.ALLOW) {
                return from(directAction);
            }
            String qualifier = normalize(column.getUnquotedTableName());
            if (!qualifier.isBlank()) {
                Relation relation = qualifiers.get(qualifier);
                return relation == null ? Exposure.UNSAFE_EXPRESSION : relation.resolve(fieldName, policy);
            }
            Exposure result = Exposure.ALLOW;
            for (Relation relation : relations) {
                result = mergeStatic(result, relation.resolve(fieldName, policy));
            }
            return result;
        }
    }

    private static Exposure from(SensitiveFieldPolicy.Action action) {
        return switch (action) {
            case ALLOW -> Exposure.ALLOW;
            case MASK -> Exposure.MASK;
            case DENY -> Exposure.DENY;
        };
    }

    private static Exposure mergeStatic(Exposure left, Exposure right) {
        if (left == Exposure.UNSAFE_EXPRESSION || right == Exposure.UNSAFE_EXPRESSION) {
            return Exposure.UNSAFE_EXPRESSION;
        }
        if (left == Exposure.DENY || right == Exposure.DENY) {
            return Exposure.DENY;
        }
        if (left == Exposure.MASK || right == Exposure.MASK) {
            return Exposure.MASK;
        }
        return Exposure.ALLOW;
    }

    private static final class AnalysisRuntimeException extends RuntimeException {
        private final SensitiveAccessException cause;

        private AnalysisRuntimeException(SensitiveAccessException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
