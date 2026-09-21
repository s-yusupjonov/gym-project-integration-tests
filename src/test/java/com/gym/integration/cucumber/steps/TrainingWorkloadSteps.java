package com.gym.integration.cucumber.steps;

import com.gym.crm.domain.Training;
import com.gym.crm.repository.TrainingRepository;
import com.gym.integration.cucumber.GymCrmApiClient;
import com.gym.integration.cucumber.IntegrationTestEnvironment;
import com.gym.integration.cucumber.ScenarioContext;
import com.gym.integration.cucumber.WorkloadApiClient;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives gym-crm's training endpoints and observes the resulting effect (or deliberate lack of
 * one) on trainer-workload-service, polling because the only path between the two services is
 * asynchronous (a JMS message gym-crm publishes and trainer-workload-service consumes on its own
 * schedule).
 */
public class TrainingWorkloadSteps {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(300);

    // How long we give a rejected request to (not) show up as a workload change. gym-crm's
    // TrainingController validates/looks up the trainee and trainer BEFORE TrainingService.
    // addTraining ever runs, so for every negative scenario in this suite no JMS message is ever
    // published in the first place - there's no message to wait out. We still wait deliberately
    // rather than checking exactly once, so this stays a real (if bounded) observation instead of
    // an assumption, and stays robust if that call order ever changes.
    private static final Duration NEGATIVE_OBSERVATION_WINDOW = Duration.ofSeconds(3);

    private final GymCrmApiClient gymCrmApiClient;
    private final WorkloadApiClient workloadApiClient;
    private final ScenarioContext scenarioContext;

    public TrainingWorkloadSteps(GymCrmApiClient gymCrmApiClient, WorkloadApiClient workloadApiClient,
                                 ScenarioContext scenarioContext) {
        this.gymCrmApiClient = gymCrmApiClient;
        this.workloadApiClient = workloadApiClient;
        this.scenarioContext = scenarioContext;
    }

    @When("a training of {int} minutes is added for the trainee and trainer on a future date")
    public void aTrainingIsAddedForTheTraineeAndTrainer(int durationMinutes) {
        addTraining(scenarioContext.getTraineeUsername(), scenarioContext.getTrainerUsername(), durationMinutes);
    }

    @Given("a training of {int} minutes has been added for the trainee and trainer on a future date")
    public void aTrainingHasBeenAddedForTheTraineeAndTrainer(int durationMinutes) {
        addTraining(scenarioContext.getTraineeUsername(), scenarioContext.getTrainerUsername(), durationMinutes);
        assertThat(scenarioContext.getLastGymCrmResponse().getStatusCode().value())
                .as("failed to set up training fixture: %s", scenarioContext.getLastGymCrmResponse().getBody())
                .isEqualTo(200);
        captureLastTrainingId();
    }

    @When("a training is added for trainee username {string} and the registered trainer on a future date")
    public void aTrainingIsAddedForAMissingTrainee(String traineeUsername) {
        addTraining(traineeUsername, scenarioContext.getTrainerUsername(), 60);
    }

    @When("the training is cancelled")
    public void theTrainingIsCancelled() {
        if (scenarioContext.getLastTrainingId() == null) {
            captureLastTrainingId();
        }
        ResponseEntity<String> response = gymCrmApiClient.cancelTraining(
                scenarioContext.getLastTrainingId(), scenarioContext.getTraineeToken());
        scenarioContext.setLastGymCrmResponse(response);
    }

    @Then("gym-crm accepts the request with status {int}")
    public void gymCrmAcceptsTheRequestWithStatus(int expectedStatus) {
        assertGymCrmStatus(expectedStatus);
    }

    @Then("gym-crm rejects the request with status {int}")
    public void gymCrmRejectsTheRequestWithStatus(int expectedStatus) {
        assertGymCrmStatus(expectedStatus);
    }

    @Then("trainer-workload-service eventually reports {int} minutes of workload for the trainer that month")
    public void trainerWorkloadServiceEventuallyReportsMinutes(int expectedMinutes) {
        await().atMost(POLL_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            WorkloadApiClient.MonthWorkload workload = workloadApiClient.getMonthWorkload(
                    scenarioContext.getTrainerUsername(), scenarioContext.getLastTrainingDate());
            assertThat(workload.present())
                    .as("expected trainer-workload-service to have a recorded workload for trainer '%s' by now",
                            scenarioContext.getTrainerUsername())
                    .isTrue();
            assertThat(workload.trainingSummaryDuration()).isEqualTo(expectedMinutes);
        });
    }

    @Then("trainer-workload-service never records any workload for the trainer")
    public void trainerWorkloadServiceNeverRecordsWorkload() throws InterruptedException {
        Thread.sleep(NEGATIVE_OBSERVATION_WINDOW.toMillis());

        LocalDate observedDate =
                scenarioContext.getLastTrainingDate() != null ? scenarioContext.getLastTrainingDate() : LocalDate.now();
        WorkloadApiClient.MonthWorkload workload =
                workloadApiClient.getMonthWorkload(scenarioContext.getTrainerUsername(), observedDate);

        assertThat(workload.present())
                .as("trainer-workload-service unexpectedly recorded a workload for trainer '%s'",
                        scenarioContext.getTrainerUsername())
                .isFalse();
    }

    private void assertGymCrmStatus(int expectedStatus) {
        ResponseEntity<String> response = scenarioContext.getLastGymCrmResponse();
        assertThat(response).as("no gym-crm response was recorded for this scenario").isNotNull();
        assertThat(response.getStatusCode().value())
                .as("unexpected gym-crm status; body was: %s", response.getBody())
                .isEqualTo(expectedStatus);
    }

    private void addTraining(String traineeUsername, String trainerUsername, int durationMinutes) {
        String trainingName = "Integration Session " + System.nanoTime();
        LocalDate trainingDate = LocalDate.now().plusDays(14);

        ResponseEntity<String> response = gymCrmApiClient.addTraining(
                traineeUsername, trainerUsername, trainingName, trainingDate, durationMinutes,
                scenarioContext.getTraineeToken());

        scenarioContext.setLastGymCrmResponse(response);
        scenarioContext.setLastTrainingName(trainingName);
        scenarioContext.setLastTrainingDate(trainingDate);
        scenarioContext.setLastTrainingDuration(durationMinutes);
        scenarioContext.setLastTrainingId(null);
    }

    /**
     * gym-crm's "add training" endpoint returns 200 with an empty body (no id), so - exactly like
     * gym-crm's own cucumber tests do - we go straight to the TrainingRepository bean inside the
     * (real, in-process) gym-crm ApplicationContext this harness started, rather than adding a
     * lookup endpoint just for this test.
     */
    private void captureLastTrainingId() {
        TrainingRepository trainingRepository = IntegrationTestEnvironment.gymCrmBean(TrainingRepository.class);
        String traineeUsername = scenarioContext.getTraineeUsername();
        String trainingName = scenarioContext.getLastTrainingName();
        LocalDate trainingDate = scenarioContext.getLastTrainingDate();

        Long id = trainingRepository.findTraineeTrainings(traineeUsername, null, null, null, null).stream()
                .filter(training -> trainingName.equals(training.getTrainingName())
                        && trainingDate.equals(training.getTrainingDate()))
                .map(Training::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "could not locate fixture training '" + trainingName + "' after creation"));

        scenarioContext.setLastTrainingId(id);
    }
}
