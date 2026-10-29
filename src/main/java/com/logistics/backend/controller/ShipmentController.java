
package com.logistics.backend.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.logistics.backend.service.NotificationService;

@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final Firestore firestore;
private final NotificationService notificationService;

public ShipmentController(
        Firestore firestore,
        NotificationService notificationService) {
    this.firestore = firestore;
    this.notificationService = notificationService;
}

    // Create a new shipment
    @PostMapping
    public ResponseEntity<Map<String, Object>> createShipment(
        @RequestBody CreateShipmentRequest request,
        Authentication authentication)
        throws ExecutionException, InterruptedException {

        if (request == null
                || request.senderName() == null
                || request.senderName().isBlank()
                || request.receiverName() == null
                || request.receiverName().isBlank()
                || request.origin() == null
                || request.origin().isBlank()
                || request.destination() == null
                || request.destination().isBlank()) {

            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            "Sender, receiver, origin and destination are required."
                    ));
        }

        String trackingId = "LPT-" + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);

        String now = Instant.now().toString();

        Map<String, Object> firstEvent = new HashMap<>();
        firstEvent.put("status", "CREATED");
        firstEvent.put("timestamp", now);
        firstEvent.put("description", "Shipment created");

        Map<String, Object> shipment = new HashMap<>();
        shipment.put("createdByUid",
        String.valueOf(authentication.getPrincipal()));
        shipment.put("trackingId", trackingId);
        shipment.put("senderName", request.senderName().trim());
        shipment.put("receiverName", request.receiverName().trim());
        shipment.put("origin", request.origin().trim());
        shipment.put("destination", request.destination().trim());
        shipment.put("status", "CREATED");
        shipment.put("createdAt", now);
        shipment.put("updatedAt", now);
        shipment.put("trackingHistory", List.of(firstEvent));

        firestore.collection("shipments")
                .document(trackingId)
                .set(shipment)
                .get();

        return ResponseEntity.status(HttpStatus.CREATED).body(shipment);
    }

    // Retrieve shipment details using a tracking ID
  

@GetMapping("/{trackingId}")
public ResponseEntity<Map<String, Object>> trackShipment(
        @PathVariable String trackingId)
        throws ExecutionException, InterruptedException {

    String id = trackingId.trim();

    if (id.isBlank()) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Tracking ID is required."));
    }

    DocumentSnapshot document = firestore
            .collection("shipments")
            .document(id)
            .get()
            .get();

    if (!document.exists()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Shipment not found."));
    }

    Map<String, Object> data = document.getData();

    if (data == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Shipment details are unavailable."));
    }

    // Return only information needed for parcel tracking.
    Map<String, Object> result = new HashMap<>();

    result.put("trackingId", data.getOrDefault("trackingId", id));
    result.put("origin", data.getOrDefault("origin", "Warehouse"));
    result.put("destination", data.getOrDefault("destination", ""));
    result.put("status", data.getOrDefault("status", "UNKNOWN"));
    result.put("createdAt", data.get("createdAt"));
    result.put("updatedAt", data.get("updatedAt"));
    result.put("trackingHistory",
            data.getOrDefault("trackingHistory", List.of()));

    // Include names only if you want them visible on the public tracking page.
    // Remove these two lines if you prefer to keep names private.
    result.put("senderName", data.getOrDefault("senderName", ""));
    result.put("receiverName", data.getOrDefault("receiverName", ""));

    return ResponseEntity.ok(result);
}



    // Update shipment status and append a tracking-history event
    @PatchMapping("/{trackingId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateShipmentStatus(
            @PathVariable String trackingId,
            @RequestBody StatusUpdateRequest request)
            throws ExecutionException, InterruptedException {

        if (request == null
                || request.status() == null
                || request.status().isBlank()) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Shipment status is required."));
        }

        String nextStatus = request.status()
                .trim()
                .toUpperCase(Locale.ROOT);

        Set<String> allowedStatuses = Set.of(
                "CREATED",
                "PACKED",
                "DISPATCHED",
                "IN_TRANSIT",
                "OUT_FOR_DELIVERY",
                "DELIVERED",
                "DELIVERY_FAILED"
        );

        if (!allowedStatuses.contains(nextStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid shipment status."));
        }

        var documentRef = firestore.collection("shipments")
                .document(trackingId);

        DocumentSnapshot document = documentRef.get().get();

        if (!document.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Shipment not found."));
        }
        String currentStatus = document.getString("status");

        if (currentStatus == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error",
                            "Shipment has no current status."));
        }

        Map<String, Set<String>> allowedTransitions = Map.of(
                "CREATED", Set.of("PACKED"),
                "PACKED", Set.of("DISPATCHED"),
                "DISPATCHED", Set.of("IN_TRANSIT"),
                "IN_TRANSIT", Set.of("OUT_FOR_DELIVERY"),
                "OUT_FOR_DELIVERY",
                        Set.of("DELIVERED", "DELIVERY_FAILED"),
                "DELIVERY_FAILED", Set.of("OUT_FOR_DELIVERY"),
                "DELIVERED", Set.of()
        );

        Set<String> nextAllowedStatuses =
                allowedTransitions.getOrDefault(
                        currentStatus, Set.of());

        if (!nextAllowedStatuses.contains(nextStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "Invalid status transition from "
                                    + currentStatus + " to "
                                    + nextStatus + ".",
                            "allowedNextStatuses", nextAllowedStatuses
                    ));
        }
        String now = Instant.now().toString();

        Map<String, Object> event = new HashMap<>();
        event.put("status", nextStatus);
        event.put("timestamp", now);

        event.put("description", switch (nextStatus) {
            case "CREATED" -> "Shipment created";
            case "PACKED" -> "Parcel packed";
            case "DISPATCHED" -> "Shipment dispatched";
            case "IN_TRANSIT" -> "Parcel is in transit";
            case "OUT_FOR_DELIVERY" -> "Parcel is out for delivery";
            case "DELIVERED" -> "Parcel delivered successfully";
            case "DELIVERY_FAILED" -> "Delivery attempt failed";
            default -> "Shipment status updated";
        });

        Map<String, Object> updates = new HashMap<>();
        updates.put("status", nextStatus);
        updates.put("updatedAt", now);
        updates.put("trackingHistory", FieldValue.arrayUnion(event));

        documentRef.update(updates).get();

DocumentSnapshot updatedDocument = documentRef.get().get();

Map<String, Object> updatedShipment = updatedDocument.getData();

if (updatedShipment != null) {
    notificationService.notifyStatusChange(
            updatedShipment,
            nextStatus);
}

return ResponseEntity.ok(updatedShipment);
    }

    // Request body for creating a shipment
    public record CreateShipmentRequest(
            String senderName,
            String receiverName,
            String origin,
            String destination) {
    }

    // Request body for updating a shipment status
    public record StatusUpdateRequest(String status) {
    }
}
