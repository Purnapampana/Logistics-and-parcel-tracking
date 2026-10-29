
package com.logistics.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;

@Repository
public class PackageRepository {

    private final CollectionReference packages;

    public PackageRepository(Firestore firestore) {
        this.packages = firestore.collection("packages");
    }

    public PackageEntity save(PackageEntity entity)
            throws ExecutionException, InterruptedException {

        entity.prepareForSave();

        String trackingId = entity.getTrackingId();

        if (trackingId == null || trackingId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tracking ID is required.");
        }

        // Use the tracking ID as the document ID for new backend-created
        // packages. Existing documents keep their current document ID.
        String documentId = entity.getId();

        if (documentId == null || documentId.isBlank()) {
            documentId = trackingId;
            entity.setId(documentId);
        }

        // Prevent a new document from overwriting an existing package.
        DocumentSnapshot existing = packages
                .document(documentId)
                .get()
                .get();

        
        final String resolvedDocumentId = documentId;

if (!existing.exists()) {
    List<QueryDocumentSnapshot> matches = packages
            .whereEqualTo("trackingId", trackingId)
            .get()
            .get()
            .getDocuments();

    boolean duplicate = matches.stream()
            .anyMatch(doc -> !doc.getId().equals(resolvedDocumentId));

    if (duplicate) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A package with this tracking ID already exists.");
    }
}

        packages.document(documentId).set(entity).get();

        return entity;
    }

    public List<PackageEntity> findAll()
            throws ExecutionException, InterruptedException {

        ApiFuture<QuerySnapshot> future = packages.get();

        List<QueryDocumentSnapshot> documents =
                future.get().getDocuments();

        List<PackageEntity> result = new ArrayList<>();

        for (QueryDocumentSnapshot document : documents) {
            PackageEntity entity =
                    document.toObject(PackageEntity.class);

            entity.setId(document.getId());
            result.add(entity);
        }

        return result;
    }

    public Optional<PackageEntity> findByTrackingIdIgnoreCase(
            String trackingId)
            throws ExecutionException, InterruptedException {

        if (trackingId == null || trackingId.isBlank()) {
            return Optional.empty();
        }

        String searchId = trackingId.trim().toLowerCase(Locale.ROOT);

        // Firestore equality queries are case-sensitive, so compare the
        // tracking IDs after loading the package records.
        for (PackageEntity entity : findAll()) {
            String existingId = entity.getTrackingId();

            if (existingId != null
                    && existingId.trim().toLowerCase(Locale.ROOT)
                            .equals(searchId)) {
                return Optional.of(entity);
            }
        }

        return Optional.empty();
    }

    public Optional<PackageEntity> findById(String id)
            throws ExecutionException, InterruptedException {

        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        DocumentSnapshot document =
                packages.document(id).get().get();

        if (!document.exists()) {
            return Optional.empty();
        }

        PackageEntity entity =
                document.toObject(PackageEntity.class);

        if (entity == null) {
            return Optional.empty();
        }

        entity.setId(document.getId());

        return Optional.of(entity);
    }

    public void deleteById(String id)
            throws ExecutionException, InterruptedException {

        packages.document(id).delete().get();
    }
}
