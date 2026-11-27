package dev.dsatracker.planning;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StudyPlanApprovalAccess {
    private final boolean configured;
    private final byte[] authorization;

    StudyPlanApprovalAccess(@Value("${tracker.study-plan-approval-token}") String token) {
        configured = token.matches("[0-9a-fA-F]{64}");
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    public boolean configured() {
        return configured;
    }

    void requireOperator(HttpServletRequest request) {
        if (!configured || !loopback(request.getRemoteAddr()) || request.getHeader("Origin") != null
                || request.getHeader("Sec-Fetch-Site") != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Study plan approvals require a local operator.");
        }
        String supplied = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A study plan approval token is required.");
        }
    }

    private static boolean loopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
