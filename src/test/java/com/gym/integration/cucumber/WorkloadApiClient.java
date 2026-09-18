package com.gym.integration.cucumber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;

/**
 * Thin REST client for trainer-workload-service's read API, authenticating with the internal
 * "caller=gym-crm" JWT the same way a real internal consumer would (see
 * {@link InternalJwtSupport}). gym-crm itself never calls this API; this client exists purely so
 * the test can observe the effect of the JMS message gym-crm published.
 */
public final class WorkloadApiClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final String baseUrl;

    public WorkloadApiClient() {
        this.baseUrl = IntegrationTestEnvironment.workloadBaseUrl();
    }

    /** The recorded minutes for a trainer in a given year/month, or empty if none is recorded (404). */
    public record MonthWorkload(boolean present, int trainingSummaryDuration) {

        static MonthWorkload absent() {
            return new MonthWorkload(false, 0);
        }

        static MonthWorkload of(int minutes) {
            return new MonthWorkload(true, minutes);
        }
    }

    public MonthWorkload getMonthWorkload(String trainerUsername, LocalDate trainingDate) {
        int year = trainingDate.getYear();
        int month = trainingDate.getMonthValue();
        String path = "/api/trainer-workloads/" + trainerUsername + "/" + year + "/" + month;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(InternalJwtSupport.validGymCrmToken());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().value() == 404) {
                return MonthWorkload.absent();
            }
            JsonNode json = objectMapper.readTree(response.getBody());
            return MonthWorkload.of(json.get("trainingSummaryDuration").asInt());
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                return MonthWorkload.absent();
            }
            throw new IllegalStateException(
                    "unexpected trainer-workload-service response: status=" + ex.getStatusCode()
                            + " body=" + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to query trainer-workload-service for " + path, ex);
        }
    }
}
