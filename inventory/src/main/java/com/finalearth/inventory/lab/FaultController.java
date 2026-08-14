package com.finalearth.inventory.lab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Reads and sets this replica's fault mode.
 *
 * <p>Only registered when {@code lab.fault-injection.enabled} is true — see
 * {@link LabFaultConfig}. It has no authentication of any kind, which is exactly why it
 * is behind a flag: anyone who can reach it can take the service down.
 */
@RestController
@RequestMapping("/api/lab/fault")
public class FaultController {

    private static final Logger log = LoggerFactory.getLogger(FaultController.class);

    private final FaultState faultState;
    private final String instanceId;

    public FaultController(FaultState faultState) {
        this.faultState = faultState;
        this.instanceId = System.getenv().getOrDefault("HOSTNAME", "local");
    }

    public record FaultRequest(String mode, Long delayMs, Integer failureRate) {}

    public record FaultView(String instance, String mode, long delayMs, int failureRate) {}

    @GetMapping
    public FaultView current() {
        return view(faultState.get());
    }

    @PostMapping
    public ResponseEntity<FaultView> set(@RequestBody FaultRequest req) {
        FaultState.Mode mode;
        try {
            mode = FaultState.Mode.valueOf(req.mode().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().build();
        }

        FaultState.Settings next = new FaultState.Settings(
                mode,
                req.delayMs() == null ? 0L : req.delayMs(),
                req.failureRate() == null ? 100 : req.failureRate());

        faultState.set(next);
        log.warn("[fault-injection] {} switched to mode={} delayMs={} failureRate={}",
                instanceId, next.mode(), next.delayMs(), next.failureRate());
        return ResponseEntity.ok(view(next));
    }

    @DeleteMapping
    public FaultView reset() {
        faultState.reset();
        log.warn("[fault-injection] {} reset to OK", instanceId);
        return view(faultState.get());
    }

    private FaultView view(FaultState.Settings s) {
        return new FaultView(instanceId, s.mode().name(), s.delayMs(), s.failureRate());
    }
}
