package dev.dsatracker.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
class McpLocalAccessConfiguration {

    @Bean
    FilterRegistrationBean<Filter> mcpLocalAccessFilter(
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}") String endpoint) {
        Filter localAccess = (request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            if (!InetAddress.getByName(httpRequest.getRemoteAddr()).isLoopbackAddress()
                    || CorsUtils.isCorsRequest(httpRequest)) {
                ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(localAccess);
        registration.addUrlPatterns(endpoint);
        return registration;
    }
}
