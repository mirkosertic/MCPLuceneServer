package de.mirkosertic.mcp.luceneserver.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SQL templates with named parameters (:paramName) into JDBC-compatible
 * positional parameter queries (?).
 * <p>
 * Example:
 *   "SELECT * FROM docs WHERE path = :file_path"
 *   → sql:  "SELECT * FROM docs WHERE path = ?"
 *   → params: ["file_path"]
 */
public class SqlTemplateParser {

    private static final Pattern NAMED_PARAM_PATTERN =
            Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    public record ParsedTemplate(String sql, List<String> parameterNames) {}

    public ParsedTemplate parse(final String template) {
        final List<String> params = new ArrayList<>();
        final Matcher matcher = NAMED_PARAM_PATTERN.matcher(template);

        final StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            params.add(matcher.group(1));
            matcher.appendReplacement(sb, "?");
        }
        matcher.appendTail(sb);

        return new ParsedTemplate(sb.toString(), List.copyOf(params));
    }
}
