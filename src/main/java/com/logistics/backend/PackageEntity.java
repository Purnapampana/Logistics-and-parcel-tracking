
package com.logistics.backend;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class PackageEntity {

    private String id;
    private String trackingId;

    @NotBlank
    private String senderName;

    @NotBlank
    private String receiverName;

    @NotBlank
    @Email
    private String receiverEmail;

    @NotBlank
    private String destination;

    // UID of the staff member who registered the package (set by the server).
    private String registeredBy;

    @Positive
    private double weightKg;

    @NotBlank
    private String dimensions;

    private PackageStatus status = PackageStatus.REGISTERED;

    private Object createdAt = Instant.now().toString();

    private Object updatedAt = Instant.now().toString();

    private Object dispatchedAt;

    public PackageEntity() {
    }

    public void prepareForSave() {

        if (trackingId == null || trackingId.isBlank()) {
            trackingId = "PKG-" +
                    UUID.randomUUID()
                            .toString()
                            .substring(0, 8)
                            .toUpperCase();
        }

        if (createdAt == null) {
            createdAt = Instant.now().toString();
        }

        if (updatedAt == null) {
            updatedAt = Instant.now().toString();
        }

        if (status == null) {
            status = PackageStatus.REGISTERED;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverEmail() {
        return receiverEmail;
    }

    public void setReceiverEmail(String receiverEmail) {
        this.receiverEmail = receiverEmail;
    }

    public String getRegisteredBy() {
        return registeredBy;
    }

    public void setRegisteredBy(String registeredBy) {
        this.registeredBy = registeredBy;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(double weightKg) {
        this.weightKg = weightKg;
    }

    public String getDimensions() {
        return dimensions;
    }

    public void setDimensions(String dimensions) {
        this.dimensions = dimensions;
    }

    public PackageStatus getStatus() {
        return status;
    }

    public void setStatus(PackageStatus status) {
        this.status = status;
    }

    public Object getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Object createdAt) {
        this.createdAt = createdAt;
    }

    public Object getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Object updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Object getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Object dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
