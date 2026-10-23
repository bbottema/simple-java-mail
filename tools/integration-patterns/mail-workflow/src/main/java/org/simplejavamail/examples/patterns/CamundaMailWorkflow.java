package org.simplejavamail.examples.patterns;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/** Runs the same first-report process with BPMN jobs and a correlated Camunda message. */
@Configuration
@ConditionalOnProperty(name = "workflow.engine", havingValue = "camunda")
public class CamundaMailWorkflow {
    @Bean(destroyMethod = "close")
    ZeebeClient zeebe() {
        // Plaintext is restricted to the local Compose network, not a recommended connection to a hosted workflow service.
        ZeebeClient client = ZeebeClient.newClientBuilder()
                .gatewayAddress("workflow:26500")
                .usePlaintext()
                .build();
        client.newDeployResourceCommand()
                .addResourceFromClasspath("mail-feedback.bpmn")
                .send()
                .join();
        return client;
    }

    @Bean(destroyMethod = "close")
    JobWorker submitWorker(ZeebeClient client, MailWork work, JobStore evidence) {
        return client.newWorker()
                .jobType("submit-mail")
                .handler((jobs, job) -> {
                    String id = job.getVariablesAsMap().get("jobId").toString();
                    work.deliver(id);
                    // Completing this framework job does not mean every mailbox received the email. The BPMN gateway reads
                    // the separately committed submission state and sends held/uncertain work to reconciliation.
                    jobs.newCompleteCommand(job.getKey())
                            .variables(Map.of("submissionState", evidence.get(id).get("state")))
                            .send()
                            .join();
                })
                .open();
    }

    @Bean(destroyMethod = "close")
    JobWorker decisionWorker(ZeebeClient client, JobStore evidence) {
        return client.newWorker()
                .jobType("record-decision")
                .handler((jobs, job) -> {
                    Map<String, Object> variables = job.getVariablesAsMap();
                    evidence.event(variables.get("jobId").toString(), "WORKFLOW_DECISION", variables.get("action").toString());
                    jobs.newCompleteCommand(job.getKey())
                            .send()
                            .join();
                })
                .open();
    }

    @Bean
    PatternPorts.WorkTrigger camundaTrigger(ZeebeClient client) {
        return id -> client.newCreateInstanceCommand()
                .bpmnProcessId("mail-feedback")
                .latestVersion()
                .variables(Map.of("jobId", id))
                .send()
                .join();
    }

    @Bean
    PatternPorts.FeedbackSink camundaFeedback(ZeebeClient client) {
        return (id, action) -> client.newPublishMessageCommand()
                .messageName("delivery-report")
                .correlationKey(id)
                .timeToLive(Duration.ofHours(1))
                .variables(Map.of("action", action))
                .send()
                .join();
    }
}
