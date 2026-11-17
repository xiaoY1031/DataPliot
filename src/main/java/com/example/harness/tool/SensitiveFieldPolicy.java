package com.example.harness.tool;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 集中维护数据库字段的拒绝返回和脱敏策略。 */
@Component
public class SensitiveFieldPolicy {

    public static final String MASKED_VALUE = "[已脱敏]";

    private final Set<String> deniedFields;
    private final Set<String> maskedFields;

    @Autowired
    public SensitiveFieldPolicy(DatabaseToolProperties properties) {
        this(properties.getSensitiveFields().getDeny(), properties.getSensitiveFields().getMask());
    }

    SensitiveFieldPolicy(Collection<String> deniedFields, Collection<String> maskedFields) {
        this.deniedFields = normalize(deniedFields);
        this.maskedFields = normalize(maskedFields);
    }

    static SensitiveFieldPolicy none() {
        return new SensitiveFieldPolicy(Set.of(), Set.of());
    }

    static SensitiveFieldPolicy of(Collection<String> deniedFields, Collection<String> maskedFields) {
        return new SensitiveFieldPolicy(deniedFields, maskedFields);
    }

    public Action actionFor(String fieldName) {
        String normalized = normalize(fieldName);
        if (deniedFields.contains(normalized)) {
            return Action.DENY;
        }
        if (maskedFields.contains(normalized)) {
            return Action.MASK;
        }
        return Action.ALLOW;
    }

    public boolean hasRules() {
        return !deniedFields.isEmpty() || !maskedFields.isEmpty();
    }

    private static Set<String> normalize(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(SensitiveFieldPolicy::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum Action {
        ALLOW,
        MASK,
        DENY
    }
}
