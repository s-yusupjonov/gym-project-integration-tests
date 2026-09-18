package com.gym.integration.cucumber;

import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

/**
 * Per-scenario shared state, instantiated fresh for each scenario by cucumber-picocontainer and
 * constructor-injected into every step definition class. Mirrors the role gym-crm's own
 * {@code @ScenarioScope}-annotated {@code ScenarioContext} plays for its cucumber-spring tests.
 */
public class ScenarioContext {

    private String traineeUsername;
    private String traineePassword;
    private String traineeToken;

    private String trainerUsername;
    private String trainerFirstName;
    private String trainerLastName;

    private String lastTrainingName;
    private LocalDate lastTrainingDate;
    private Integer lastTrainingDuration;
    private Long lastTrainingId;

    private ResponseEntity<String> lastGymCrmResponse;

    public String getTraineeUsername() {
        return traineeUsername;
    }

    public void setTraineeUsername(String traineeUsername) {
        this.traineeUsername = traineeUsername;
    }

    public String getTraineePassword() {
        return traineePassword;
    }

    public void setTraineePassword(String traineePassword) {
        this.traineePassword = traineePassword;
    }

    public String getTraineeToken() {
        return traineeToken;
    }

    public void setTraineeToken(String traineeToken) {
        this.traineeToken = traineeToken;
    }

    public String getTrainerUsername() {
        return trainerUsername;
    }

    public void setTrainerUsername(String trainerUsername) {
        this.trainerUsername = trainerUsername;
    }

    public String getTrainerFirstName() {
        return trainerFirstName;
    }

    public void setTrainerFirstName(String trainerFirstName) {
        this.trainerFirstName = trainerFirstName;
    }

    public String getTrainerLastName() {
        return trainerLastName;
    }

    public void setTrainerLastName(String trainerLastName) {
        this.trainerLastName = trainerLastName;
    }

    public String getLastTrainingName() {
        return lastTrainingName;
    }

    public void setLastTrainingName(String lastTrainingName) {
        this.lastTrainingName = lastTrainingName;
    }

    public LocalDate getLastTrainingDate() {
        return lastTrainingDate;
    }

    public void setLastTrainingDate(LocalDate lastTrainingDate) {
        this.lastTrainingDate = lastTrainingDate;
    }

    public Integer getLastTrainingDuration() {
        return lastTrainingDuration;
    }

    public void setLastTrainingDuration(Integer lastTrainingDuration) {
        this.lastTrainingDuration = lastTrainingDuration;
    }

    public Long getLastTrainingId() {
        return lastTrainingId;
    }

    public void setLastTrainingId(Long lastTrainingId) {
        this.lastTrainingId = lastTrainingId;
    }

    public ResponseEntity<String> getLastGymCrmResponse() {
        return lastGymCrmResponse;
    }

    public void setLastGymCrmResponse(ResponseEntity<String> lastGymCrmResponse) {
        this.lastGymCrmResponse = lastGymCrmResponse;
    }
}
