package org.simplejavamail.examples.patterns;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Gives committed mail jobs to JobRunr. The SQL guard, not JobRunr redelivery, decides whether SMTP may run. */
@Configuration
public class OutboxJobs {
    private final JobStore intents;
    private final JobScheduler scheduler;
    private final MailWork work;

    public OutboxJobs(JobStore intents, JobScheduler scheduler, MailWork work) {
        this.intents = intents;
        this.scheduler = scheduler;
        this.work = work;
    }

    @Bean
    PatternPorts.WorkTrigger trigger() {
        return this::enqueue;
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchCommittedIntents() {
        intents.ready().forEach(this::enqueue);
    }

    private void enqueue(String id) {
        // Polling repairs a failed handoff after the HTTP request committed its intent. A stable scheduler ID keeps repeated
        // scans from creating unrelated jobs; MailWork's separately committed guard still protects the actual SMTP call.
        scheduler.enqueue(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)), () -> work.deliver(id));
    }
}
