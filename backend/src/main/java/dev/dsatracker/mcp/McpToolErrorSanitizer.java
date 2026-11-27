package dev.dsatracker.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.util.ToolInputValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
class McpToolErrorSanitizer implements BeanPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(McpToolErrorSanitizer.class);
    private static final Pattern INTERNAL = Pattern.compile(
            "(?i)SQLITE|SQLException|jdbc:|Hibernate|PreparedStatement|JDBCException|could not execute statement"
                    + "|(?:/Users/|/home/|/data/|/tmp/|/private/|/var/)|[A-Z]:\\\\");
    private static final String FAILURE = "The operation could not be completed. Check the tracker server logs.";

    private static final Set<String> QUERY = Set.of("query_tracker", "describe_tracker");
    private static final Set<String> WORKFLOWS = Set.of("ping_tracker", "recommend_study_plan",
            "search_leetcode_problems", "get_leetcode_problem", "preview_leetcode_import");
    private final Profile profile;

    McpToolErrorSanitizer(Environment environment) {
        profile = Profile.valueOf(environment.getProperty("tracker.mcp-tool-profile", "LEGACY"));
    }

    private enum Profile { LEGACY, UNIFIED, ALL }

    private boolean exposed(SyncToolSpecification spec) {
        String name = spec.tool().name();
        return switch (profile) {
            case LEGACY -> !QUERY.contains(name);
            case UNIFIED -> QUERY.contains(name) || WORKFLOWS.contains(name)
                    || Boolean.FALSE.equals(spec.tool().annotations().readOnlyHint());
            case ALL -> true;
        };
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof List<?> list) || list.isEmpty()
                || !list.stream().allMatch(SyncToolSpecification.class::isInstance)) {
            return bean;
        }
        return list.stream().map(SyncToolSpecification.class::cast).filter(this::exposed)
                .map(spec -> new SyncToolSpecification(spec.tool(), (exchange, request) -> {
                    try {
                        if (profile != Profile.LEGACY && !spec.tool().name().equals("query_tracker")) {
                            var invalid = ToolInputValidator.validate(spec.tool(), request.arguments(), true,
                                    McpJsonDefaults.getSchemaValidator());
                            if (invalid != null) return invalid;
                        }
                        var result = spec.callHandler().apply(exchange, request);
                        if (Boolean.TRUE.equals(result.isError()) && INTERNAL.matcher(result.content().toString()).find()) {
                            log.warn("MCP tool {} returned an internal failure", spec.tool().name());
                            return failure();
                        }
                        return result;
                    } catch (RuntimeException exception) {
                        log.warn("MCP tool {} failed", spec.tool().name(), exception);
                        return failure();
                    }
                })).toList();
    }

    private static CallToolResult failure() {
        return CallToolResult.builder().isError(true).addTextContent(FAILURE).build();
    }
}
