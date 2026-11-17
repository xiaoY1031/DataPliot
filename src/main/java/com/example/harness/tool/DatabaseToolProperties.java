package com.example.harness.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 数据库工具共享配置。 */
@Component
@ConfigurationProperties(prefix = "datapilot.database")
public class DatabaseToolProperties {

    private final Query query = new Query();
    private final SensitiveFields sensitiveFields = new SensitiveFields();

    public Query getQuery() {
        return query;
    }

    public SensitiveFields getSensitiveFields() {
        return sensitiveFields;
    }

    public static class Query {
        private int maxResultBytes = 262_144;

        public int getMaxResultBytes() {
            return maxResultBytes;
        }

        public void setMaxResultBytes(int maxResultBytes) {
            this.maxResultBytes = maxResultBytes;
        }
    }

    public static class SensitiveFields {
        private List<String> deny = new ArrayList<>();
        private List<String> mask = new ArrayList<>();

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny == null ? new ArrayList<>() : new ArrayList<>(deny);
        }

        public List<String> getMask() {
            return mask;
        }

        public void setMask(List<String> mask) {
            this.mask = mask == null ? new ArrayList<>() : new ArrayList<>(mask);
        }
    }
}
