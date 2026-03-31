package com.non.chain.tool;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具定义，用于 LLM 的 function_call
 */
public class Tool {

    private final String name;
    private final String description;
    private final Map<String, Property> properties;
    private final List<String> required;

    private Tool(String name, String description, Map<String, Property> properties, List<String> required) {
        this.name = name;
        this.description = description;
        this.properties = properties;
        this.required = required;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * 转换为 SDK 的 FunctionDefinition
     */
    public FunctionDefinition toFunctionDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Property> entry : properties.entrySet()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", entry.getValue().type);
            if (entry.getValue().description != null) {
                prop.put("description", entry.getValue().description);
            }
            if (entry.getValue().enumValues != null && !entry.getValue().enumValues.isEmpty()) {
                prop.put("enum", entry.getValue().enumValues);
            }
            props.put(entry.getKey(), prop);
        }

        FunctionParameters params = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(props))
                .putAdditionalProperty("required", JsonValue.from(required))
                .build();

        FunctionDefinition.Builder fdBuilder = FunctionDefinition.builder()
                .name(name)
                .parameters(params);

        if (description != null) {
            fdBuilder.description(description);
        }

        return fdBuilder.build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String description;
        private final Map<String, Property> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addProperty(String name, String type, String description, boolean isRequired) {
            this.properties.put(name, new Property(type, description, null));
            if (isRequired) {
                this.required.add(name);
            }
            return this;
        }

        public Builder addProperty(String name, String type, String description, boolean isRequired, List<String> enumValues) {
            this.properties.put(name, new Property(type, description, enumValues));
            if (isRequired) {
                this.required.add(name);
            }
            return this;
        }

        public Tool build() {
            return new Tool(name, description, properties, required);
        }
    }

    private static class Property {
        final String type;
        final String description;
        final List<String> enumValues;

        Property(String type, String description, List<String> enumValues) {
            this.type = type;
            this.description = description;
            this.enumValues = enumValues;
        }
    }
}
