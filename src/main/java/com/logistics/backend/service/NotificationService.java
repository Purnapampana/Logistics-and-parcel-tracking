
package com.logistics.backend.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private static final Set<String> NOTIFIABLE_STATUSES = Set.of(
            "DISPATCHED",
            "IN_TRANSIT",
            "OUT_FOR_DELIVERY",
            "DELIVERED",
            "DELIVERY_FAILED"
    );

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String trackingPageUrl;

    public NotificationService(
            JavaMailSender mailSender,
            @Value("${app.notifications.from}") String fromAddress,
            @Value("${app.notifications.tracking-url}") String trackingPageUrl) {

        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.trackingPageUrl = trackingPageUrl;
    }

    /**
     * Call after successfully saving the shipment status.
     * Uses the receiverEmail stored in the shipment document.
     */
    public void notifyStatusChange(
            Map<String, Object> shipment,
            String newStatus) {

        if (shipment == null || newStatus == null) {
            return;
        }

        String status = newStatus.trim().toUpperCase(Locale.ROOT);

        if (!NOTIFIABLE_STATUSES.contains(status)) {
            return;
        }

        String trackingId =
                stringValue(shipment.get("trackingId"));

        String email =
                stringValue(shipment.get("receiverEmail"));

        if (trackingId == null) {
            log.warn("Notification skipped: tracking ID is missing.");
            return;
        }

        if (email == null || !isValidEmail(email)) {
            log.warn(
                    "Notification skipped: receiver email is missing or invalid for {}.",
                    trackingId);
            return;
        }

        String receiverName =
                stringValue(shipment.get("receiverName"));

        String destination =
                stringValue(shipment.get("destination"));

        String subject = "Parcel " + trackingId
                + " - " + status.replace('_', ' ');

        String body = buildEmailBody(
                receiverName,
                trackingId,
                status,
                destination);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

            log.info(
                    "Notification sent to receiver for shipment {} with status {}.",
                    trackingId,
                    status);

        } catch (Exception ex) {
            // Email failure must not undo a successful status update.
            log.error(
                    "Failed to send notification for shipment {} with status {}.",
                    trackingId,
                    status,
                    ex);
        }
    }

    private String buildEmailBody(
            String receiverName,
            String trackingId,
            String status,
            String destination) {

        String displayStatus = status.replace('_', ' ');
        StringBuilder body = new StringBuilder();

        body.append("Hello");

        if (receiverName != null) {
            body.append(" ").append(receiverName);
        }

        body.append(",\n\n");
        body.append("There is an update on your parcel.\n\n");
        body.append("Tracking ID: ").append(trackingId).append("\n");
        body.append("Current status: ").append(displayStatus).append("\n");

        if (destination != null) {
            body.append("Destination: ").append(destination).append("\n");
        }

        body.append("\n");

        switch (status) {
            case "DISPATCHED" ->
                    body.append("Your parcel has been dispatched.");
            case "IN_TRANSIT" ->
                    body.append("Your parcel is on its way.");
            case "OUT_FOR_DELIVERY" ->
                    body.append("Your parcel is out for delivery.");
            case "DELIVERED" ->
                    body.append("Your parcel has been marked as delivered.");
            case "DELIVERY_FAILED" ->
                    body.append(
                            "The delivery attempt was unsuccessful. "
                            + "Please check the tracking page for updates.");
            default ->
                    body.append("Your parcel status has been updated.");
        }

        body.append("\n\nTrack your parcel here:\n");
        body.append(trackingPageUrl);
        body.append("\n\nThank you,\n");
        body.append("Logistics & Parcel Tracking System");

        return body.toString();
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }

        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
