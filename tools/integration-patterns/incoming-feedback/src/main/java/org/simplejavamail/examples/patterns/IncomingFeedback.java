package org.simplejavamail.examples.patterns;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.http.dsl.Http;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sends interpreted delivery facts to a business HTTP endpoint through an ordinary Spring Integration flow. */
@Configuration
public class IncomingFeedback {
    @Bean
    PatternPorts.WorkTrigger incomingTrigger(MailWork work) {
        return work::deliver;
    }

    @Bean
    MessageChannel deliveryFeedback() {
        // This direct channel runs the HTTP handoff synchronously. It does not persist or retry undelivered callbacks.
        return new DirectChannel();
    }

    @Bean
    PatternPorts.FeedbackSink feedbackPublisher(MessageChannel deliveryFeedback) {
        return (id, action) -> deliveryFeedback.send(new GenericMessage<>(Map.of("jobId", id, "action", action)));
    }

    @Bean
    IntegrationFlow feedbackFlow() {
        return IntegrationFlow.from("deliveryFeedback")
                .transform(Transformers.toJson())
                .handle(Http.outboundGateway("http://localhost:8080/business-feedback")
                        .httpMethod(HttpMethod.POST)
                        .expectedResponseType(String.class))
                .channel("nullChannel")
                .get();
    }

    @RestController
    public static class BusinessFeedbackEndpoint {
        private final JobStore jobs;
        public BusinessFeedbackEndpoint(JobStore jobs) {
            this.jobs = jobs;
        }

        @PostMapping("/business-feedback")
        public String record(@RequestBody Map<String, String> projection) {
            jobs.event(projection.get("jobId"), "HTTP_BUSINESS_FEEDBACK", projection.get("action"));
            return "Recorded by the application, not SJM";
        }
    }
}
