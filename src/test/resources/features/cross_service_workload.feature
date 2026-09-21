Feature: gym-crm to trainer-workload-service workload synchronization
  As trainer-workload-service
  I want to reflect trainings that are added or cancelled in gym-crm
  So that a trainer's recorded workload always matches their real schedule

  This exercises the one real integration point between the two services: gym-crm publishes a
  JMS message to the "workload.events.queue" ActiveMQ queue whenever a training is added or
  cancelled, and trainer-workload-service consumes that message asynchronously and serves the
  result over its own REST API. gym-crm never calls that REST API directly - nothing here is
  mocked on either side.

  Background:
    Given a registered trainee and a registered trainer exist in gym-crm

  Scenario: Adding a training increases the trainer's recorded workload
    When a training of 55 minutes is added for the trainee and trainer on a future date
    Then gym-crm accepts the request with status 200
    And trainer-workload-service eventually reports 55 minutes of workload for the trainer that month

  Scenario: Cancelling a training decreases the trainer's recorded workload
    Given a training of 40 minutes has been added for the trainee and trainer on a future date
    And trainer-workload-service eventually reports 40 minutes of workload for the trainer that month
    When the training is cancelled
    Then gym-crm accepts the request with status 200
    And trainer-workload-service eventually reports 0 minutes of workload for the trainer that month

  Scenario: A training gym-crm rejects for a non-existent trainee never reaches trainer-workload-service
    When a training is added for trainee username "no.such.trainee" and the registered trainer on a future date
    Then gym-crm rejects the request with status 404
    And trainer-workload-service never records any workload for the trainer

  Scenario: A training gym-crm rejects for an invalid duration never reaches trainer-workload-service
    When a training of -15 minutes is added for the trainee and trainer on a future date
    Then gym-crm rejects the request with status 400
    And trainer-workload-service never records any workload for the trainer
