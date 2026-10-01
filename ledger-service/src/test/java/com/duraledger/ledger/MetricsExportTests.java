package com.duraledger.ledger;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Export is off by default, so every other test runs without the OTLP registry. That's how a protobuf
 * version clash (the GCP BOM's 4.33 runtime against OTLP's 4.34 generated code) passed every test and
 * then crashed the app as soon as an environment switched export on. This test switches it on.
 * Nothing listens on the URL: a failed push only logs, and must never stop the app.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "management.otlp.metrics.export.enabled=true",
        "management.otlp.metrics.export.url=http://localhost:9/v1/metrics"})
class MetricsExportTests {

    @Autowired ApplicationContext context;

    @Test
    void app_starts_with_otlp_export_switched_on() {
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).hasSize(1);
    }
}
