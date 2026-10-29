
package com.logistics.backend;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.cloud.FirestoreClient;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Map<String, String> ROLE_AUTHORITIES = Map.of(
            "Customer", "ROLE_CUSTOMER",
            "Warehouse Staff", "ROLE_WAREHOUSE_STAFF",
            "Delivery Agent", "ROLE_DELIVERY_AGENT",
            "Admin", "ROLE_ADMIN"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorization = request.getHeader("Authorization");

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String idToken = authorization.substring(7);

            FirebaseToken decodedToken =
                    FirebaseAuth.getInstance().verifyIdToken(idToken);

            String uid = decodedToken.getUid();

            DocumentSnapshot profile = FirestoreClient.getFirestore()
                    .collection("users")
                    .document(uid)
                    .get()
                    .get();

            if (!profile.exists()) {
                reject(response, "User profile not found");
                return;
            }

            String role = profile.getString("role");
            if ("Delivery Agent".equals(role)
                    && Boolean.FALSE.equals(profile.getBoolean("active"))) {
                reject(response, "Delivery agent account is inactive");
                return;
            }
            String authority = ROLE_AUTHORITIES.get(role);

            if (authority == null) {
                reject(response, "Invalid user role");
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            uid,
                            null,
                            List.of(new SimpleGrantedAuthority(authority))
                    );

            authentication.setDetails(decodedToken);

            SecurityContextHolder.getContext()
                    .setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            SecurityContextHolder.clearContext();
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Authentication was interrupted"
            );

        } catch (ExecutionException exception) {
            SecurityContextHolder.clearContext();
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Unable to load user profile"
            );

        } catch (Exception exception) {
            SecurityContextHolder.clearContext();
            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid or expired Firebase ID token"
            );
        }
    }

    private void reject(
            HttpServletResponse response,
            String message
    ) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_FORBIDDEN, message);
    }
}
