package dev.dsatracker.catalog;

import dev.dsatracker.api.ApiException;
import java.net.URI;
import java.util.regex.Pattern;

public record LeetCodeLink(String slug, String url) {
    private static final Pattern PROBLEM_PATH = Pattern.compile("/problems/([a-z0-9]+(?:-[a-z0-9]+)*)/?");

    public static LeetCodeLink parse(String input) {
        URI uri;
        try {
            uri = URI.create(input.strip());
        } catch (IllegalArgumentException invalid) {
            throw invalidUrl();
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("leetcode.com") || host.equalsIgnoreCase("www.leetcode.com"))
                || uri.getRawUserInfo() != null || uri.getPort() != -1) {
            throw invalidUrl();
        }
        var path = PROBLEM_PATH.matcher(uri.getRawPath());
        if (!path.matches() || path.group(1).length() > 255) {
            throw invalidUrl();
        }
        String slug = path.group(1);
        return new LeetCodeLink(slug, "https://leetcode.com/problems/" + slug + "/");
    }

    private static ApiException invalidUrl() {
        return ApiException.invalid("url", "Enter a LeetCode problem URL such as https://leetcode.com/problems/two-sum/.");
    }
}
