package org.simplejavamail.examples.patterns;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Resumes the untouched part of a nightly run without pretending that a rolled-back SQL chunk also rolled back SMTP. */
@Configuration
public class NightlyMailJob {

    @Bean
    Job nightly(JobRepository repository, Step submitStep) {
        return new JobBuilder("nightly-mail", repository)
                .start(submitStep)
                .build();
    }

    @Bean
    @StepScope
    ItemReader<String> readyMail(JobStore jobs, @Value("#{jobParameters['run']}") String run) {
        // The durable SMTP guard, not a stale list index, determines the remainder after restart. The demo seeds only five jobs.
        return new ListItemReader<>(jobs.ready().stream().filter(id -> id.equals(run) || id.startsWith(run + "_")).toList());
    }

    @Bean
    Step submitStep(JobRepository repository, PlatformTransactionManager transaction, ItemReader<String> readyMail, ItemWriter<String> submitMail) {
        return new StepBuilder("submit-mail", repository)
                .<String, String>chunk(1, transaction)
                .reader(readyMail)
                .writer(submitMail)
                .build();
    }

    @Bean
    @StepScope
    ItemWriter<String> submitMail(MailWork work, @Value("#{jobParameters['stopAfter']}") Long stopAfter) {
        AtomicInteger remainingBeforeInterruption = new AtomicInteger(stopAfter.intValue());
        return items -> {
            for (String id : items) {
                work.deliver(id);
                if (remainingBeforeInterruption.get() > 0 && remainingBeforeInterruption.decrementAndGet() == 0) {
                    throw new IllegalStateException("Deliberate demo interruption after committed SMTP evidence; restart the same run");
                }
            }
        };
    }

    @Bean
    PatternPorts.WorkTrigger batchTrigger(JobLauncher launcher, Job nightly) {
        return id -> launch(launcher, nightly, id, 0);
    }

    private Map<String, Object> launch(JobLauncher launcher, Job nightly, String id, int stopAfter) {
        // Only 'run' identifies the job instance. Changing stopAfter to zero restarts that same run with the demo fault disabled.
        JobParameters parameters = new JobParametersBuilder()
                .addString("run", id)
                .addLong("stopAfter", (long) stopAfter, false)
                .toJobParameters();
        try {
            return Map.of("batchStatus", launcher.run(nightly, parameters).getStatus().name());
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to run this demonstration batch", failure);
        }
    }

    @RestController
    public static class NightlyEndpoint {
        private final JobStore jobs;
        private final JobLauncher launcher;
        private final Job nightly;
        private final NightlyMailJob configuration;

        public NightlyEndpoint(JobStore jobs, JobLauncher launcher, Job nightly, NightlyMailJob configuration) {
            this.jobs = jobs;
            this.launcher = launcher;
            this.nightly = nightly;
            this.configuration = configuration;
        }

        @PostMapping("/nightly")
        public Map<String, Object> run(@RequestParam String prefix, @RequestParam(defaultValue = "0") int stopAfter) {
            for (int index = 0; index < 5; index++) {
                jobs.create(prefix + "_" + index, "happy");
            }
            return configuration.launch(launcher, nightly, prefix, stopAfter);
        }
    }
}
