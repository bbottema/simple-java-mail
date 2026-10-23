package org.simplejavamail.examples.patterns;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Sends through a guarded activity, then lets Temporal wait for the first delivery report and record a business decision. */
@Configuration
@ConditionalOnProperty(name = "workflow.engine", havingValue = "temporal", matchIfMissing = true)
public class TemporalMailWorkflow {
    private static final String QUEUE = "mail-feedback";

    @WorkflowInterface
    public interface FeedbackWorkflow {
        @WorkflowMethod
        String run(String id);

        @SignalMethod
        void deliveryReport(String action);
    }

    @ActivityInterface
    public interface MailActivities {
        String submit(String id);
        void recordDecision(String id, String action);
    }

    public static class FeedbackWorkflowImpl implements FeedbackWorkflow {
        private String firstReportedAction;
        // Workflow code is replayed; network and SQL work belong in activities. This small example disables activity retries
        // and uses a durable SMTP guard, rather than allowing a framework retry to choose a mail replay policy for us.
        private final MailActivities activities = Workflow.newActivityStub(MailActivities.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(1)
                        .build())
                .build());

        @Override
        public String run(String id) {
            if (!"SUBMITTED".equals(activities.submit(id))) {
                return "MANUAL_REVIEW";
            }
            if (!Workflow.await(Duration.ofHours(1), () -> firstReportedAction != null)) {
                return "NO_REPORT_YET";
            }

            // This process deliberately completes after the first report, including DELAYED. It does not wait for final delivery.
            activities.recordDecision(id, firstReportedAction);
            return firstReportedAction;
        }

        @Override
        public void deliveryReport(String action) {
            if (firstReportedAction == null) {
                firstReportedAction = action;
            }
        }
    }

    public static class MailActivitiesImpl implements MailActivities {
        private final MailWork work;
        private final JobStore jobs;
        public MailActivitiesImpl(MailWork work, JobStore jobs) {
            this.work = work;
            this.jobs = jobs;
        }

        @Override
        public String submit(String id) {
            work.deliver(id);
            return jobs.get(id).get("state").toString();
        }

        @Override
        public void recordDecision(String id, String action) {
            jobs.event(id, "WORKFLOW_DECISION", action);
        }
    }

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalService() {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget("workflow:7233")
                .build());
    }

    @Bean
    WorkflowClient temporalClient(WorkflowServiceStubs service) {
        return WorkflowClient.newInstance(service);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    WorkerFactory temporalWorkers(WorkflowClient client, MailWork work, JobStore jobs) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FeedbackWorkflowImpl.class);
        worker.registerActivitiesImplementations(new MailActivitiesImpl(work, jobs));
        return factory;
    }

    @Bean
    PatternPorts.WorkTrigger temporalTrigger(WorkflowClient client) {
        return id -> WorkflowClient.start(client.newWorkflowStub(FeedbackWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(id)
                        .setTaskQueue(QUEUE)
                        .build())::run, id);
    }

    @Bean
    PatternPorts.FeedbackSink temporalFeedback(WorkflowClient client) {
        return (id, action) -> client.newWorkflowStub(FeedbackWorkflow.class, id).deliveryReport(action);
    }
}
