package com.gym.integration.cucumber;

import com.gym.crm.Application;
import com.gym.workload.TrainerWorkloadServiceApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.activemq.ActiveMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;

public final class IntegrationTestEnvironment {

    private static final Logger log =
            LoggerFactory.getLogger(IntegrationTestEnvironment.class);

    private static final String ACTIVEMQ_IMAGE =
            "apache/activemq-classic:6.1.8";

    private static final String MONGODB_IMAGE =
            "mongo:7.0";

    private static final int ACTIVEMQ_OPENWIRE_PORT = 61616;

    private static final Object LOCK = new Object();

    private static volatile boolean started = false;

    private static ActiveMQContainer activeMqContainer;
    private static MongoDBContainer mongoDbContainer;

    private static ConfigurableApplicationContext gymCrmContext;
    private static ConfigurableApplicationContext workloadContext;

    private static String gymCrmBaseUrl;
    private static String workloadBaseUrl;

    private IntegrationTestEnvironment() {
    }

    public static void start() {
        if (started) {
            return;
        }

        synchronized (LOCK) {
            if (started) {
                return;
            }

            log.info("Starting shared ActiveMQ + MongoDB Testcontainers...");

            activeMqContainer = new ActiveMQContainer(ACTIVEMQ_IMAGE);
            activeMqContainer.start();

            String activeMqBrokerUrl =
                    "tcp://"
                            + activeMqContainer.getHost()
                            + ":"
                            + activeMqContainer.getMappedPort(
                            ACTIVEMQ_OPENWIRE_PORT
                    );

            mongoDbContainer = new MongoDBContainer(MONGODB_IMAGE);
            mongoDbContainer.start();

            String mongoUri =
                    mongoDbContainer.getReplicaSetUrl(
                            "trainer_workload_it"
                    );

            log.info("Booting gym-crm (Application) in-process...");

            gymCrmContext =
                    new SpringApplicationBuilder(Application.class)
                            .web(WebApplicationType.SERVLET)
                            .run(
                                    "--spring.application.name=gym-crm-it",
                                    "--spring.profiles.active=it",
                                    "--server.port=0",
                                    "--server.servlet.context-path=/gym-crm",

                                    "--spring.datasource.url=jdbc:h2:mem:it-gymcrm-"
                                            + System.nanoTime()
                                            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                                    "--spring.datasource.driver-class-name=org.h2.Driver",
                                    "--spring.datasource.username=sa",
                                    "--spring.datasource.password=",
                                    "--spring.jpa.hibernate.ddl-auto=create-drop",

                                    "--eureka.client.enabled=false",
                                    "--eureka.client.register-with-eureka=false",
                                    "--eureka.client.fetch-registry=false",
                                    "--spring.cloud.discovery.enabled=false",

                                    "--spring.activemq.broker-url="
                                            + activeMqBrokerUrl,
                                    "--spring.activemq.user="
                                            + ActiveMQCredentials.USER,
                                    "--spring.activemq.password="
                                            + ActiveMQCredentials.PASSWORD,
                                    "--spring.jms.template.delivery-mode=persistent",

                                    "--activemq.queue.workload-events="
                                            + QueueNames.WORKLOAD_EVENTS_QUEUE,

                                    "--management.endpoints.web.exposure.include=health",

                                    "--logging.level.com.gym.crm=INFO",

                                    "--spring.autoconfigure.exclude="
                                            + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                                            + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
                                            + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration"
                            );

            gymCrmBaseUrl = baseUrlOf(gymCrmContext);

            log.info(
                    "Booting trainer-workload-service "
                            + "(TrainerWorkloadServiceApplication) in-process..."
            );

            workloadContext =
                    new SpringApplicationBuilder(
                            TrainerWorkloadServiceApplication.class
                    )
                            .web(WebApplicationType.SERVLET)
                            .run(
                                    "--spring.application.name=trainer-workload-service-it",
                                    "--spring.profiles.active=it",
                                    "--server.port=0",

                                    /*
                                     * gym-crm and trainer-workload-service are both
                                     * present on the integration-test classpath.
                                     * Explicitly clear gym-crm's context path here.
                                     */
                                    "--server.servlet.context-path=",

                                    "--eureka.client.enabled=false",
                                    "--eureka.client.register-with-eureka=false",
                                    "--eureka.client.fetch-registry=false",
                                    "--spring.cloud.discovery.enabled=false",

                                    "--spring.activemq.broker-url="
                                            + activeMqBrokerUrl,
                                    "--spring.activemq.user="
                                            + ActiveMQCredentials.USER,
                                    "--spring.activemq.password="
                                            + ActiveMQCredentials.PASSWORD,

                                    "--activemq.queue.workload-events="
                                            + QueueNames.WORKLOAD_EVENTS_QUEUE,

                                    "--activemq.queue.workload-events-dlq="
                                            + QueueNames.WORKLOAD_EVENTS_DLQ,

                                    "--spring.data.mongodb.uri="
                                            + mongoUri,

                                    "--internal-auth.secret="
                                            + InternalJwtSupport.SECRET,

                                    "--internal-auth.allowed-callers="
                                            + InternalJwtSupport.ALLOWED_CALLER,

                                    "--management.endpoints.web.exposure.include=health",

                                    "--logging.level.com.gym.workload=INFO",

                                    /*
                                     * gym-crm brings JPA/PostgreSQL/H2 onto the same
                                     * integration-test classpath. Do not let those
                                     * dependencies create a DataSource in the
                                     * MongoDB-based workload service.
                                     */
                                    "--spring.autoconfigure.exclude="
                                            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
                            );

            workloadBaseUrl = baseUrlOf(workloadContext);

            log.info(
                    "Environment ready: gym-crm={} trainer-workload-service={}",
                    gymCrmBaseUrl,
                    workloadBaseUrl
            );

            Runtime.getRuntime().addShutdownHook(
                    new Thread(IntegrationTestEnvironment::stop)
            );

            started = true;
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (!started) {
                return;
            }

            closeQuietly(gymCrmContext);
            closeQuietly(workloadContext);

            stopQuietly(activeMqContainer);
            stopQuietly(mongoDbContainer);

            gymCrmContext = null;
            workloadContext = null;

            activeMqContainer = null;
            mongoDbContainer = null;

            gymCrmBaseUrl = null;
            workloadBaseUrl = null;

            started = false;
        }
    }

    public static String gymCrmBaseUrl() {
        start();
        return gymCrmBaseUrl;
    }

    public static String workloadBaseUrl() {
        start();
        return workloadBaseUrl;
    }

    public static <T> T gymCrmBean(Class<T> type) {
        start();
        return gymCrmContext.getBean(type);
    }

    private static String baseUrlOf(
            ConfigurableApplicationContext context
    ) {
        int port =
                ((WebServerApplicationContext) context)
                        .getWebServer()
                        .getPort();

        String contextPath =
                context.getEnvironment()
                        .getProperty(
                                "server.servlet.context-path",
                                ""
                        );

        return "http://localhost:" + port + contextPath;
    }

    private static void closeQuietly(
            ConfigurableApplicationContext context
    ) {
        try {
            if (context != null) {
                context.close();
            }
        } catch (Exception ex) {
            log.warn(
                    "Failed to close application context during shutdown",
                    ex
            );
        }
    }

    private static void stopQuietly(
            GenericContainer<?> container
    ) {
        try {
            if (container != null) {
                container.stop();
            }
        } catch (Exception ex) {
            log.warn(
                    "Failed to stop container during shutdown",
                    ex
            );
        }
    }

    static final class ActiveMQCredentials {

        static final String USER = "admin";
        static final String PASSWORD = "admin";

        private ActiveMQCredentials() {
        }
    }

    static final class QueueNames {

        static final String WORKLOAD_EVENTS_QUEUE =
                "workload.events.queue";

        static final String WORKLOAD_EVENTS_DLQ =
                "workload.events.dlq";

        private QueueNames() {
        }
    }
}