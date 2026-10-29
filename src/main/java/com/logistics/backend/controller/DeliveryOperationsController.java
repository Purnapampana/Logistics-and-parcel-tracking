package com.logistics.backend.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.logistics.backend.service.NotificationService;

@RestController
@RequestMapping("/api/delivery")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class DeliveryOperationsController {
    private final Firestore firestore;
    private final NotificationService notificationService;

    public DeliveryOperationsController(Firestore firestore, NotificationService notificationService) {
        this.firestore = firestore;
        this.notificationService = notificationService;
    }

    @GetMapping("/shipments")
    @PreAuthorize("hasAnyRole('ADMIN','DELIVERY_AGENT')")
    public List<Map<String, Object>> shipments(Authentication authentication)
            throws ExecutionException, InterruptedException {
        boolean admin = hasRole(authentication, "ROLE_ADMIN");
        String uid = String.valueOf(authentication.getPrincipal());
        return firestore.collection("shipments").get().get().getDocuments().stream()
                .map(doc -> {
                    Map<String, Object> data = new HashMap<>(doc.getData());
                    data.put("documentId", doc.getId());
                    return data;
                })
                .filter(s -> admin || uid.equals(s.get("assignedAgentUid")))
                .sorted((a, b) -> String.valueOf(b.getOrDefault("updatedAt", ""))
                        .compareTo(String.valueOf(a.getOrDefault("updatedAt", ""))))
                .collect(Collectors.toList());
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> users()
            throws ExecutionException, InterruptedException {
        return firestore.collection("users").get().get().getDocuments().stream()
                .map(doc -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("uid", doc.getId());
                    item.put("name", Objects.toString(doc.getString("name"), ""));
                    item.put("email", Objects.toString(doc.getString("email"), ""));
                    item.put("role", Objects.toString(doc.getString("role"), "Customer"));
                    item.put("active", !Boolean.FALSE.equals(doc.getBoolean("active")));
                    return item;
                }).sorted(Comparator.comparing(a -> String.valueOf(a.get("name"))))
                .collect(Collectors.toList());
    }

    @GetMapping("/agents")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> agents()
            throws ExecutionException, InterruptedException {
        return firestore.collection("users").get().get().getDocuments().stream()
                .filter(doc -> "Delivery Agent".equals(doc.getString("role")))
                .map(doc -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("uid", doc.getId());
                    item.put("name", Objects.toString(doc.getString("name"), ""));
                    item.put("email", Objects.toString(doc.getString("email"), ""));
                    item.put("active", !Boolean.FALSE.equals(doc.getBoolean("active")));
                    return item;
                }).sorted(Comparator.comparing(a -> String.valueOf(a.get("name"))))
                .collect(Collectors.toList());
    }

    @PatchMapping("/shipments/{trackingId}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> assign(@PathVariable String trackingId,
                                      @RequestBody AssignRequest request)
            throws ExecutionException, InterruptedException {
        if (request == null || request.agentUid() == null || request.agentUid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a delivery agent.");
        }
        DocumentSnapshot agent = firestore.collection("users").document(request.agentUid()).get().get();
        if (!agent.exists() || !"Delivery Agent".equals(agent.getString("role"))
                || Boolean.FALSE.equals(agent.getBoolean("active"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected user is not an active delivery agent.");
        }
        var ref = firestore.collection("shipments").document(trackingId);
        DocumentSnapshot shipment = ref.get().get();
        if (!shipment.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found.");
        String status = shipment.getString("status");
        if ("DELIVERED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivered parcels cannot be reassigned.");

        String now = Instant.now().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("status", status == null ? "CREATED" : status);
        event.put("timestamp", now);
        event.put("description", "Assigned to delivery agent " + Objects.toString(agent.getString("name"), agent.getString("email")));
        Map<String, Object> updates = new HashMap<>();
        updates.put("assignedAgentUid", request.agentUid());
        updates.put("assignedAgentName", Objects.toString(agent.getString("name"), agent.getString("email")));
        updates.put("assignedAgentEmail", Objects.toString(agent.getString("email"), ""));
        updates.put("assignedAt", now);
        updates.put("updatedAt", now);
        updates.put("trackingHistory", FieldValue.arrayUnion(event));
        ref.update(updates).get();
        return ref.get().get().getData();
    }


    /**
     * Assigns many shipments to one delivery agent in a single request.
     * Shipments that cannot be assigned (not found, already delivered, or
     * already assigned to this agent) are skipped and reported back instead
     * of failing the whole request.
     */
    @PatchMapping("/shipments/bulk-assign")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> bulkAssign(@RequestBody BulkAssignRequest request)
            throws ExecutionException, InterruptedException {
        if (request == null || request.agentUid() == null || request.agentUid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a delivery agent.");
        }
        if (request.trackingIds() == null || request.trackingIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one shipment.");
        }
        if (request.trackingIds().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can assign at most 100 shipments at once.");
        }

        DocumentSnapshot agent = firestore.collection("users").document(request.agentUid()).get().get();
        if (!agent.exists() || !"Delivery Agent".equals(agent.getString("role"))
                || Boolean.FALSE.equals(agent.getBoolean("active"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected user is not an active delivery agent.");
        }
        String agentName = Objects.toString(agent.getString("name"), agent.getString("email"));
        String agentEmail = Objects.toString(agent.getString("email"), "");

        List<String> assigned = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();

        for (String rawId : new LinkedHashSet<>(request.trackingIds())) {
            if (rawId == null || rawId.isBlank()) continue;
            String id = rawId.trim();

            var ref = firestore.collection("shipments").document(id);
            DocumentSnapshot shipment = ref.get().get();
            if (!shipment.exists()) {
                skipped.add(Map.of("trackingId", id, "reason", "Shipment not found."));
                continue;
            }
            String status = shipment.getString("status");
            if ("DELIVERED".equals(status)) {
                skipped.add(Map.of("trackingId", id, "reason", "Already delivered."));
                continue;
            }
            if (request.agentUid().equals(shipment.getString("assignedAgentUid"))) {
                skipped.add(Map.of("trackingId", id, "reason", "Already assigned to this agent."));
                continue;
            }

            String now = Instant.now().toString();
            Map<String, Object> event = new HashMap<>();
            event.put("status", status == null ? "CREATED" : status);
            event.put("timestamp", now);
            event.put("description", "Assigned to delivery agent " + agentName);

            Map<String, Object> updates = new HashMap<>();
            updates.put("assignedAgentUid", request.agentUid());
            updates.put("assignedAgentName", agentName);
            updates.put("assignedAgentEmail", agentEmail);
            updates.put("assignedAt", now);
            updates.put("updatedAt", now);
            updates.put("trackingHistory", FieldValue.arrayUnion(event));
            ref.update(updates).get();
            assigned.add(id);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("assignedCount", assigned.size());
        result.put("assigned", assigned);
        result.put("skipped", skipped);
        result.put("agentName", agentName);
        return result;
    }


    @PatchMapping("/shipments/{trackingId}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
    public Map<String, Object> dispatchShipment(@PathVariable String trackingId)
            throws ExecutionException, InterruptedException {
        var ref = firestore.collection("shipments").document(trackingId);
        DocumentSnapshot shipment = ref.get().get();
        if (!shipment.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found for this tracking ID.");
        }
        String current = shipment.getString("status");
        if (!"PACKED".equals(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only PACKED shipments can be dispatched. Current status: " + current);
        }
        String now = Instant.now().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("status", "DISPATCHED");
        event.put("timestamp", now);
        event.put("description", "Shipment dispatched from warehouse");
        ref.update(Map.of(
                "status", "DISPATCHED",
                "updatedAt", now,
                "dispatchedAt", now,
                "trackingHistory", FieldValue.arrayUnion(event)
        )).get();
        Map<String, Object> updatedShipment =
        ref.get().get().getData();

if (updatedShipment != null) {
    notificationService.notifyStatusChange(
            updatedShipment,
            "DISPATCHED"
    );
}

return updatedShipment;
    }

    @PatchMapping("/shipments/{trackingId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','DELIVERY_AGENT')")
    public Map<String, Object> updateDeliveryStatus(@PathVariable String trackingId,
                                                     @RequestBody DeliveryStatusRequest request,
                                                     Authentication authentication)
            throws ExecutionException, InterruptedException {
        if (request == null || request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required.");
        }
        String next = request.status().trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of("IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED");
        if (!allowed.contains(next)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delivery status.");
        var ref = firestore.collection("shipments").document(trackingId);
        DocumentSnapshot doc = ref.get().get();
        if (!doc.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found.");

        String uid = String.valueOf(authentication.getPrincipal());
        boolean admin = hasRole(authentication, "ROLE_ADMIN");
        if (!admin && !uid.equals(doc.getString("assignedAgentUid"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can update only parcels assigned to you.");
        }
        String current = doc.getString("status");
        Map<String, Set<String>> transitions = Map.of(
                "DISPATCHED", Set.of("IN_TRANSIT"),
                "IN_TRANSIT", Set.of("OUT_FOR_DELIVERY"),
                "OUT_FOR_DELIVERY", Set.of("DELIVERED", "DELIVERY_FAILED"),
                "DELIVERY_FAILED", Set.of("OUT_FOR_DELIVERY"),
                "DELIVERED", Set.of()
        );
        if (current == null || !transitions.getOrDefault(current, Set.of()).contains(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot change status from " + current + " to " + next + ".");
        }

        String now = Instant.now().toString();
        String note = request.note() == null ? "" : request.note().trim();
        String description = switch (next) {
            case "IN_TRANSIT" -> "Parcel is in transit";
            case "OUT_FOR_DELIVERY" -> "Parcel is out for delivery";
            case "DELIVERED" -> "Delivery confirmed successfully";
            case "DELIVERY_FAILED" -> note.isBlank() ? "Delivery attempt failed" : "Delivery attempt failed: " + note;
            default -> "Delivery status updated";
        };
        Map<String, Object> event = new HashMap<>();
        event.put("status", next);
        event.put("timestamp", now);
        event.put("description", description);
        if (!note.isBlank()) event.put("note", note);
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", next);
        updates.put("updatedAt", now);
        updates.put("trackingHistory", FieldValue.arrayUnion(event));
        if ("DELIVERED".equals(next)) {
            updates.put("deliveredAt", now);
            updates.put("deliveryConfirmation", Map.of(
                    "confirmedAt", now,
                    "confirmedByUid", uid,
                    "note", note
            ));
        }
        if ("DELIVERY_FAILED".equals(next)) {
            updates.put("lastDeliveryAttemptAt", now);
            updates.put("lastDeliveryFailureNote", note);
        }
        ref.update(updates).get();

Map<String, Object> updatedShipment =
        ref.get().get().getData();

if (updatedShipment != null) {
    notificationService.notifyStatusChange(
            updatedShipment,
            next
    );
}

return updatedShipment;
    }

    @PatchMapping("/agents/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> manageAgent(@PathVariable String uid,
                                           @RequestBody ManageAgentRequest request)
            throws ExecutionException, InterruptedException {
        if (request == null || request.action() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action is required.");
        }
        var ref = firestore.collection("users").document(uid);
        DocumentSnapshot profile = ref.get().get();
        if (!profile.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User profile not found.");
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> updates = new HashMap<>();
        if ("ACTIVATE".equals(action)) {
            if (!"Delivery Agent".equals(profile.getString("role"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only delivery agents can be activated here.");
            }
            updates.put("active", true);
        } else if ("DEACTIVATE".equals(action)) {
            if (!"Delivery Agent".equals(profile.getString("role"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only delivery agents can be deactivated here.");
            }
            updates.put("active", false);
        } else if ("MAKE_AGENT".equals(action)) {
            updates.put("role", "Delivery Agent");
            updates.put("active", true);
        } else if ("REMOVE_AGENT".equals(action)) {
            if (!"Delivery Agent".equals(profile.getString("role"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not a delivery agent.");
            }
            updates.put("role", "Customer");
            updates.put("active", true);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported action.");
        }
        updates.put("updatedAt", Instant.now().toString());
        ref.update(updates).get();
        Map<String, Object> result = new HashMap<>(ref.get().get().getData());
        result.put("uid", uid);
        return result;
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    public record AssignRequest(String agentUid) {}
    public record BulkAssignRequest(String agentUid, List<String> trackingIds) {}
    public record DeliveryStatusRequest(String status, String note) {}
    public record ManageAgentRequest(String action) {}
    
@PatchMapping("/warehouse/packages/{trackingId}/dispatch")
@PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_STAFF')")
public Map<String, Object> dispatchWarehousePackage(
        @PathVariable String trackingId)
        throws ExecutionException, InterruptedException {

    // Find the existing warehouse package by tracking ID.
    var query = firestore.collection("packages")
            .whereEqualTo("trackingId", trackingId)
            .get()
            .get();

    if (query.isEmpty()) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Warehouse package not found."
        );
    }

    QueryDocumentSnapshot packageDoc = query.getDocuments().get(0);
    String packageStatus = packageDoc.getString("status");

    // Only stored parcels can be dispatched.
    if (!"STORED".equals(packageStatus)) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only STORED packages can be dispatched."
        );
    }

    var shipmentRef = firestore.collection("shipments")
            .document(trackingId);

    DocumentSnapshot existingShipment = shipmentRef.get().get();

    if (existingShipment.exists()) {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A shipment already exists for this tracking ID."
        );
    }

    String now = Instant.now().toString();

    Map<String, Object> event = new HashMap<>();
    event.put("status", "DISPATCHED");
    event.put("timestamp", now);
    event.put("description", "Dispatched from warehouse");

    Map<String, Object> shipment = new HashMap<>();
    shipment.put("trackingId", trackingId);
    shipment.put("senderName", packageDoc.getString("senderName"));
    shipment.put("receiverName", packageDoc.getString("receiverName"));
    shipment.put("receiverEmail", packageDoc.getString("receiverEmail"));
    shipment.put("origin", "Warehouse");
    shipment.put("destination", packageDoc.getString("destination"));
    shipment.put("status", "DISPATCHED");
    shipment.put("createdAt", now);
    shipment.put("updatedAt", now);
    shipment.put("dispatchedAt", now);
    shipment.put("trackingHistory", List.of(event));

    // Preserve the original customer UID for ownership checks.
    String registeredBy = packageDoc.getString("registeredBy");
    if (registeredBy != null) {
        shipment.put("createdByUid", registeredBy);
    }

    // Use the same tracking ID for both records.
    shipmentRef.set(shipment).get();

    Map<String, Object> packageUpdate = new HashMap<>();
    packageUpdate.put("status", "DISPATCHED");
    packageUpdate.put("dispatchedAt", now);
    packageUpdate.put("updatedAt", now);

    packageDoc.getReference().update(packageUpdate).get();

notificationService.notifyStatusChange(
        shipment,
        "DISPATCHED"
);

return shipment;
}

}