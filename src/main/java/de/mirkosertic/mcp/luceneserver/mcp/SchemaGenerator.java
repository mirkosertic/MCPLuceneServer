package de.mirkosertic.mcp.luceneserver.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates JSON Schema from Java record classes for MCP tool definitions.
 * Supports basic types, collections, and nested objects.
 */
public final class SchemaGenerator {

    private SchemaGenerator() {
    }

    /**
     * Generate a JsonSchema from a record class.
     *
     * @param recordClass the record class to generate schema for
     * @return a JsonSchema suitable for MCP tool inputSchema
     */
    public static McpSchema.JsonSchema generateSchema(final Class<? extends Record> recordClass) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> required = new ArrayList<>();

        for (final RecordComponent component : recordClass.getRecordComponents()) {
            final String fieldName = component.getName();
            final Type fieldType = component.getGenericType();
            final boolean isNullable = component.isAnnotationPresent(Nullable.class);

            properties.put(fieldName, generatePropertySchema(fieldType, component));

            if (!isNullable && !isOptionalType(fieldType)) {
                required.add(fieldName);
            }
        }

        return new McpSchema.JsonSchema("object", properties, required, null, null, null);
    }

    /**
     * Generate an empty schema for tools that take no parameters.
     */
    public static McpSchema.JsonSchema emptySchema() {
        return new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
    }

    private static Map<String, Object> generatePropertySchema(final Type type, final RecordComponent component) {
        final Map<String, Object> schema = new LinkedHashMap<>();

        // Check for @Description annotation
        final Description description = component.getAnnotation(Description.class);
        if (description != null) {
            schema.put("description", description.value());
        }

        if (type instanceof Class<?> clazz) {
            addTypeSchema(schema, clazz);
        } else if (type instanceof ParameterizedType paramType) {
            addParameterizedTypeSchema(schema, paramType);
        } else {
            schema.put("type", "string");
        }

        return schema;
    }

    private static void addTypeSchema(final Map<String, Object> schema, final Class<?> clazz) {
        if (clazz == String.class) {
            schema.put("type", "string");
        } else if (clazz == Integer.class || clazz == int.class) {
            schema.put("type", "integer");
        } else if (clazz == Long.class || clazz == long.class) {
            schema.put("type", "integer");
        } else if (clazz == Double.class || clazz == double.class) {
            schema.put("type", "number");
        } else if (clazz == Float.class || clazz == float.class) {
            schema.put("type", "number");
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            schema.put("type", "boolean");
        } else if (clazz.isEnum()) {
            schema.put("type", "string");
            final Object[] constants = clazz.getEnumConstants();
            final List<String> enumValues = new ArrayList<>();
            for (final Object constant : constants) {
                enumValues.add(((Enum<?>) constant).name());
            }
            schema.put("enum", enumValues);
        } else if (clazz.isRecord()) {
            // Nested record - inline the schema
            schema.put("type", "object");
            final Map<String, Object> nestedProperties = new LinkedHashMap<>();
            for (final RecordComponent component : clazz.getRecordComponents()) {
                nestedProperties.put(component.getName(),
                        generatePropertySchema(component.getGenericType(), component));
            }
            schema.put("properties", nestedProperties);
        } else {
            // Default to object for unknown types
            schema.put("type", "object");
        }
    }

    private static void addParameterizedTypeSchema(final Map<String, Object> schema,
            final ParameterizedType paramType) {
        final Type rawType = paramType.getRawType();

        if (rawType instanceof Class<?> rawClass) {
            if (List.class.isAssignableFrom(rawClass) || Set.class.isAssignableFrom(rawClass)) {
                schema.put("type", "array");
                final Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0) {
                    final Map<String, Object> itemSchema = new LinkedHashMap<>();
                    if (typeArgs[0] instanceof Class<?> itemClass) {
                        addTypeSchema(itemSchema, itemClass);
                    } else {
                        itemSchema.put("type", "object");
                    }
                    schema.put("items", itemSchema);
                }
            } else if (Map.class.isAssignableFrom(rawClass)) {
                schema.put("type", "object");
                schema.put("additionalProperties", true);
            } else {
                schema.put("type", "object");
            }
        } else {
            schema.put("type", "object");
        }
    }

    private static boolean isOptionalType(final Type type) {
        if (type instanceof Class<?> clazz) {
            // Primitive wrappers are considered optional if annotated with @Nullable
            return false;
        }
        return false;
    }
}
