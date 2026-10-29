
package com.logistics.backend;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.cloud.FirestoreClient;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = {
        "http://localhost:5173",
        "http://localhost:5174"
})
public class AuthController {

    private static final Logger logger =
            LoggerFactory.getLogger(AuthController.class);

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyToken(
            @RequestHeader(
                    value = "Authorization",
                    required = false
            ) String authorization) {

        if (authorization == null ||
                !authorization.startsWith("Bearer ")) {

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "authenticated", false,
                            "error", "Missing Firebase ID token"
                    ));
        }

        String idToken = authorization.substring(7);

        try {
            FirebaseToken decodedToken =
                    FirebaseAuth.getInstance().verifyIdToken(idToken);

            String uid = decodedToken.getUid();

            DocumentSnapshot profile = FirestoreClient.getFirestore()
                    .collection("users")
                    .document(uid)
                    .get()
                    .get();

            if (!profile.exists()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of(
                                "authenticated", false,
                                "error", "User profile not found"
                        ));
            }

            Object roleValue = profile.get("role");

            if (!(roleValue instanceof String role) ||
                    !isValidRole(role)) {

                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of(
                                "authenticated", false,
                                "error", "Invalid user role"
                        ));
            }

            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "uid", uid,
                    "email", decodedToken.getEmail() == null
                            ? "" : decodedToken.getEmail(),
                    "role", role
            ));

        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.error("Token verification interrupted", error);

            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR
            ).body(Map.of(
                    "authenticated", false,
                    "error", "Unable to verify account"
            ));

        } catch (ExecutionException error) {
            logger.error("Could not load user profile", error);

            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR
            ).body(Map.of(
                    "authenticated", false,
                    "error", "Unable to load user profile"
            ));

        } catch (Exception error) {
            logger.error("Firebase token verification failed", error);

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "authenticated", false,
                            "error", "Invalid or expired Firebase ID token"
                    ));
        }
    }

    private boolean isValidRole(String role) {
        return role.equals("Customer")
                || role.equals("Warehouse Staff")
                || role.equals("Delivery Agent")
                || role.equals("Admin");
    }
}
