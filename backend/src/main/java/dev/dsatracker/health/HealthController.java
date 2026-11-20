package dev.dsatracker.health;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final HealthService service;

    HealthController(HealthService service) {
        this.service = service;
    }

    @GetMapping("/api/health")
    ResponseEntity<HealthResponse> health() {
        boolean available = service.isAvailable();
        return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new HealthResponse(available ? "UP" : "DOWN"));
    }

    record HealthResponse(String status) {}
}
