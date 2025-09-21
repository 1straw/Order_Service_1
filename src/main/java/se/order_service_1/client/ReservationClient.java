package se.order_service_1.client;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import se.order_service_1.dto.ReservationRequest;
import se.order_service_1.dto.ReservationResponse;
import se.order_service_1.exception.ExternalServiceException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationClient {

    private static final Logger log = LoggerFactory.getLogger(ReservationClient.class);
    private final RestTemplate restTemplate;

    @Value("${product.service.address}")
    private String productServiceAddress;

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken) {
            JwtAuthenticationToken jwtToken = (JwtAuthenticationToken) authentication;
            String token = jwtToken.getToken().getTokenValue();
            headers.set("Authorization", "Bearer " + token);
            log.debug("Added JWT token to request headers");
        } else {
            log.warn("No JWT token available in security context");
        }

        return headers;
    }

    public List<ReservationResponse> reserveProducts(ReservationRequest request) {
        try {
            String url = productServiceAddress + "/reservations";
            log.info("Reserving products at URL: {}", url);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<ReservationRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<ReservationResponse[]> response = restTemplate.postForEntity(
                    url, entity, ReservationResponse[].class);

            if (response.getBody() != null) {
                return Arrays.asList(response.getBody());
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to reserve products", e);
            throw new ExternalServiceException("Failed to reserve products: " + e.getMessage(), e);
        }
    }

    public void confirmReservations(Long orderId) {
        try {
            String url = productServiceAddress + "/reservations/confirm/" + orderId;
            log.info("Confirming reservations at URL: {}", url);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            restTemplate.postForEntity(url, entity, String.class);
        } catch (Exception e) {
            log.error("Failed to confirm reservations", e);
            throw new ExternalServiceException("Failed to confirm reservations: " + e.getMessage(), e);
        }
    }

    public void cancelReservations(Long orderId) {
        try {
            String url = productServiceAddress + "/reservations/cancel/" + orderId;
            log.info("Cancelling reservations at URL: {}", url);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            restTemplate.postForEntity(url, entity, String.class);
        } catch (Exception e) {
            log.error("Failed to cancel reservations", e);
            throw new ExternalServiceException("Failed to cancel reservations: " + e.getMessage(), e);
        }
    }

    public void cancelProductReservation(Long orderId, Long productId, Integer quantity) {
        try {
            String url = productServiceAddress + "/reservations/cancel/" + orderId +
                    "/product/" + productId + "/quantity/" + quantity;
            log.info("Cancelling product reservation at URL: {}", url);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            restTemplate.postForEntity(url, entity, String.class);
        } catch (Exception e) {
            log.error("Failed to cancel product reservation", e);
            throw new ExternalServiceException("Failed to cancel product reservation: " + e.getMessage(), e);
        }
    }
}