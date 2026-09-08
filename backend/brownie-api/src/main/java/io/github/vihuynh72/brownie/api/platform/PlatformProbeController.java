package io.github.vihuynh72.brownie.api.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.OffsetDateTime;

/**
 * Not a product feature: this is the one persisted command and query the
 * local backend platform work demonstrates end to end -- real HTTP, real
 * validation, a real write and a real read against Postgres through the
 * restricted {@code brownie_api} role, and the same error/correlation
 * contract every future controller inherits automatically. Removing it is
 * expected once real domain endpoints exist to demonstrate the same
 * thing.
 */
@RestController
@RequestMapping("/api/v1/platform/probes")
class PlatformProbeController {

    private final PlatformProbeRepository repository;

    PlatformProbeController(PlatformProbeRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    ResponseEntity<ProbeResponse> create(@Valid @RequestBody CreateProbeRequest request) {
        long id = repository.insert(request.message());
        PlatformProbeRepository.PlatformProbe saved = repository.findById(id).orElseThrow();
        return ResponseEntity.created(URI.create("/api/v1/platform/probes/" + id)).body(toResponse(saved));
    }

    @GetMapping("/{id}")
    ProbeResponse get(@PathVariable("id") long id) {
        return repository
                .findById(id)
                .map(PlatformProbeController::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No platform probe with that id."));
    }

    private static ProbeResponse toResponse(PlatformProbeRepository.PlatformProbe probe) {
        return new ProbeResponse(probe.id(), probe.message(), probe.createdAt());
    }

    record CreateProbeRequest(@NotBlank @Size(max = 500) String message) {
    }

    record ProbeResponse(long id, String message, OffsetDateTime createdAt) {
    }
}
