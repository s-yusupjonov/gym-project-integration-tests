package com.gym.integration.cucumber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin REST client for gym-crm's public API, used exactly the way an external caller (or
 * gym-crm's own Cucumber tests) would use it - over real HTTP, against the random port picked
 * for the in-process instance started by {@link IntegrationTestEnvironment}.
 */
public final class GymCrmApiClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final String baseUrl;

    public GymCrmApiClient() {
        this.baseUrl = IntegrationTestEnvironment.gymCrmBaseUrl();
    }

    public record Registration(String username, String password) {
    }

    public Registration registerTrainee(String firstName, String lastName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);

        ResponseEntity<String> response = post("/api/trainees", body, null);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException(
                    "failed to register fixture trainee: status=" + response.getStatusCode()
                            + " body=" + response.getBody());
        }
        return toRegistration(response.getBody());
    }

    public Registration registerTrainer(String firstName, String lastName, int specializationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("specializationId", specializationId);

        ResponseEntity<String> response = post("/api/trainers", body, null);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException(
                    "failed to register fixture trainer: status=" + response.getStatusCode()
                            + " body=" + response.getBody());
        }
        return toRegistration(response.getBody());
    }

    public String login(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);

        ResponseEntity<String> response = post("/api/login", body, null);
        if (response.getStatusCode().value() != 200) {
            throw new IllegalStateException(
                    "failed to authenticate fixture user '" + username + "': status=" + response.getStatusCode()
                            + " body=" + response.getBody());
        }
        return readTree(response.getBody()).get("token").asText();
    }

    public ResponseEntity<String> addTraining(String traineeUsername, String trainerUsername, String trainingName,
                                       LocalDate trainingDate, int trainingDuration, String bearerToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traineeUsername", traineeUsername);
        body.put("trainerUsername", trainerUsername);
        body.put("trainingName", trainingName);
        body.put("trainingDate", trainingDate.toString());
        body.put("trainingDuration", trainingDuration);

        return post("/api/trainings", body, bearerToken);
    }

    public ResponseEntity<String> cancelTraining(long trainingId, String bearerToken) {
        return exchange(HttpMethod.DELETE, "/api/trainings/" + trainingId, null, bearerToken);
    }

    private Registration toRegistration(String body) {
        JsonNode json = readTree(body);
        return new Registration(json.get("username").asText(), json.get("password").asText());
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("could not parse gym-crm response body: " + body, ex);
        }
    }

    private ResponseEntity<String> post(String path, Object body, String bearerToken) {
        return exchange(HttpMethod.POST, path, body, bearerToken);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        try {
            return restTemplate.exchange(baseUrl + path, method, entity, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            // RestTemplate throws on 4xx/5xx by default; every step here wants to assert on the
            // status code itself (including the rejection scenarios), so surface it as a normal
            // response instead of an exception - mirrors gym-crm's own TestApiClient, which gets
            // this behaviour for free from TestRestTemplate.
            return ResponseEntity.status(ex.getStatusCode()).headers(ex.getResponseHeaders())
                    .body(ex.getResponseBodyAsString());
        }
    }
}
