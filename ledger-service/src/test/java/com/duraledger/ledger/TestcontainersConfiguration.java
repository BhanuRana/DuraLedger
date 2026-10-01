package com.duraledger.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.gcloud.PubSubEmulatorContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

	@Bean
	PubSubEmulatorContainer pubSubEmulator() {
		return new PubSubEmulatorContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"));
	}

	// Spring Cloud GCP has no @ServiceConnection support, so point it at the container explicitly.
	@Bean
	DynamicPropertyRegistrar pubSubEmulatorProperties(PubSubEmulatorContainer emulator) {
		return registry -> registry.add("spring.cloud.gcp.pubsub.emulator-host", emulator::getEmulatorEndpoint);
	}

}
