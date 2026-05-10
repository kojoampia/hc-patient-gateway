package net.jojoaddison.web.rest;

import net.jojoaddison.broker.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/patient-gateway-kafka")
public class PatientGatewayKafkaResource {

    private static final String PRODUCER_BINDING_NAME = "binding-out-0";

    private final Logger log = LoggerFactory.getLogger(PatientGatewayKafkaResource.class);
    private final KafkaConsumer kafkaConsumer;
    private final StreamBridge streamBridge;

    public PatientGatewayKafkaResource(StreamBridge streamBridge, KafkaConsumer kafkaConsumer) {
        this.streamBridge = streamBridge;
        this.kafkaConsumer = kafkaConsumer;
    }

    @PostMapping("/publish")
    public Mono<ResponseEntity<Void>> publish(@RequestParam("message") String message) {
        log.debug("REST request the message : {} to send to Kafka topic", message);
        streamBridge.send(PRODUCER_BINDING_NAME, message);
        return Mono.just(ResponseEntity.noContent().build());
    }

    @GetMapping("/consume")
    public Flux<String> consume() {
        log.debug("REST request to consume records from Kafka topics");
        return this.kafkaConsumer.getFlux();
    }
}
