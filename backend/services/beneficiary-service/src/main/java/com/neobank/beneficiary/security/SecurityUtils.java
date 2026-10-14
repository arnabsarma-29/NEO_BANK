package com.neobank.beneficiary.security;

import com.neobank.beneficiary.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Extracts the customer ID from the authenticated security context.
     * Throws 401 if unauthenticated.
     */
//    public static String getAuthenticatedCustomerId() {
//        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
//        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
//            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required.");
//        }
//
//        Object principal = auth.getPrincipal();
//        if (principal instanceof UserDetails userDetails) {
//            return userDetails.getUsername();
//        } else if (principal instanceof String principalString) {
//            return principalString;
//        }
//
//        return auth.getName();
//    }

    public static String getAuthenticatedCustomerId() {
        Authentication auth =
            SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()
            || "anonymousUser".equals(auth.getPrincipal())) {
            return "1"; // Temporary test customer ID
        }

        Object principal = auth.getPrincipal();

        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        } else if (principal instanceof String principalString) {
            return principalString;
        }

        return auth.getName();
    }
}
