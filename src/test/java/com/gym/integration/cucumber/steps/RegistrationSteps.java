package com.gym.integration.cucumber.steps;

import com.gym.integration.cucumber.GymCrmApiClient;
import com.gym.integration.cucumber.ScenarioContext;
import io.cucumber.java.en.Given;

/**
 * Registers a fresh, uniquely-named trainee and trainer in gym-crm for the scenario, and
 * authenticates the trainee (needed to call the trainings endpoints). Uniqueness matters because
 * the environment - and therefore gym-crm's H2 database and trainer-workload-service's Mongo
 * collection - is shared by every scenario in the suite (see {@link
 * com.gym.integration.cucumber.IntegrationTestEnvironment}).
 */
public class RegistrationSteps {

    private final GymCrmApiClient gymCrmApiClient;
    private final ScenarioContext scenarioContext;

    public RegistrationSteps(GymCrmApiClient gymCrmApiClient, ScenarioContext scenarioContext) {
        this.gymCrmApiClient = gymCrmApiClient;
        this.scenarioContext = scenarioContext;
    }

    @Given("a registered trainee and a registered trainer exist in gym-crm")
    public void aRegisteredTraineeAndTrainerExistInGymCrm() {
        long unique = System.nanoTime();

        GymCrmApiClient.Registration trainee = gymCrmApiClient.registerTrainee("Integration", "Trainee" + unique);
        scenarioContext.setTraineeUsername(trainee.username());
        scenarioContext.setTraineePassword(trainee.password());
        scenarioContext.setTraineeToken(gymCrmApiClient.login(trainee.username(), trainee.password()));

        String trainerFirstName = "Integration";
        String trainerLastName = "Trainer" + unique;
        // specializationId 1 -> CARDIO, seeded at startup by gym-crm's TrainingTypeSeeder; any
        // valid seeded id would do, this test doesn't assert on training type.
        GymCrmApiClient.Registration trainer = gymCrmApiClient.registerTrainer(trainerFirstName, trainerLastName, 1);
        scenarioContext.setTrainerUsername(trainer.username());
        scenarioContext.setTrainerFirstName(trainerFirstName);
        scenarioContext.setTrainerLastName(trainerLastName);
    }
}
