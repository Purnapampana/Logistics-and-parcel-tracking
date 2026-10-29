
package com.logistics.backend;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/packages")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class PackageController {

    private final PackageRepository repository;

    public PackageController(PackageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    public List<PackageEntity> allPackages()
            throws ExecutionException, InterruptedException {
        return repository.findAll();
    }

    @GetMapping("/{trackingId}")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    public PackageEntity getByTrackingId(
            @PathVariable String trackingId)
            throws ExecutionException, InterruptedException {

        return repository.findByTrackingIdIgnoreCase(trackingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Package not found"));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public PackageEntity register(
            @Valid @RequestBody PackageEntity request,
            Authentication authentication)
            throws ExecutionException, InterruptedException {

        // Never accept IDs or operational status from the client.
        request.setId(null);
        request.setTrackingId(null);
        request.setStatus(PackageStatus.REGISTERED);
        request.setCreatedAt(Instant.now().toString());
        request.setUpdatedAt(Instant.now().toString());
        request.setDispatchedAt(null);
        request.setRegisteredBy(String.valueOf(authentication.getPrincipal()));

        request.prepareForSave();

        return repository.save(request);
    }

    @PatchMapping("/{id}/store")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    public PackageEntity markStored(@PathVariable String id)
            throws ExecutionException, InterruptedException {

        PackageEntity item = findById(id);

        if (item.getStatus() != PackageStatus.REGISTERED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only REGISTERED packages can be marked as STORED.");
        }

        item.setStatus(PackageStatus.STORED);
        item.setUpdatedAt(Instant.now().toString());

        return repository.save(item);
    }

    @PatchMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    public PackageEntity dispatch(@PathVariable String id)
            throws ExecutionException, InterruptedException {

        PackageEntity item = findById(id);

        if (item.getStatus() != PackageStatus.STORED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only STORED packages can be dispatched.");
        }

        String now = Instant.now().toString();

        item.setStatus(PackageStatus.DISPATCHED);
        item.setDispatchedAt(now);
        item.setUpdatedAt(now);

        return repository.save(item);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    public Map<String, Long> summary()
            throws ExecutionException, InterruptedException {

        List<PackageEntity> all = repository.findAll();

        return Map.of(
                "total", (long) all.size(),
                "registered", all.stream()
                        .filter(p -> p.getStatus() == PackageStatus.REGISTERED)
                        .count(),
                "stored", all.stream()
                        .filter(p -> p.getStatus() == PackageStatus.STORED)
                        .count(),
                "dispatched", all.stream()
                        .filter(p -> p.getStatus() == PackageStatus.DISPATCHED)
                        .count()
        );
    }

    private PackageEntity findById(String id)
            throws ExecutionException, InterruptedException {

        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Package not found"));
    }
}
