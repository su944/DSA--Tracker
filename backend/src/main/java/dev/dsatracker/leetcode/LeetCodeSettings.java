package dev.dsatracker.leetcode;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tracker.leetcode")
public record LeetCodeSettings(
        @DefaultValue("https://leetcode.com/graphql/") URI endpoint,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout,
        @DefaultValue("14s") Duration scanTimeout,
        @DefaultValue("50") int maxPages,
        @DefaultValue("10m") Duration previewTtl) {
    public LeetCodeSettings {
        boolean publicEndpoint = URI.create("https://leetcode.com/graphql/").equals(endpoint);
        boolean loopbackMock = "http".equals(endpoint.getScheme())
                && ("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost()))
                && endpoint.getPort() > 0 && "/graphql/".equals(endpoint.getRawPath())
                && endpoint.getRawUserInfo() == null && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null;
        if (!publicEndpoint && !loopbackMock) {
            throw new IllegalArgumentException("LeetCode discovery requires the public endpoint or a loopback mock.");
        }
        if (connectTimeout.toMillis() < 1 || readTimeout.toMillis() < 1
                || scanTimeout.compareTo(readTimeout) <= 0 || scanTimeout.compareTo(Duration.ofSeconds(18)) > 0
                || maxPages < 1 || maxPages > 100 || previewTtl.isNegative() || previewTtl.isZero()) {
            throw new IllegalArgumentException("Invalid LeetCode timeouts, page budget, or preview lifetime.");
        }
    }
}
