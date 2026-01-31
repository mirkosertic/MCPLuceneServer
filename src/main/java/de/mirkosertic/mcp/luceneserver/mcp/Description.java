package de.mirkosertic.mcp.luceneserver.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to provide a description for a record component.
 * Used by SchemaGenerator to populate the JSON schema description field.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Description {
    /**
     * The description of the record component.
     */
    String value();
}
