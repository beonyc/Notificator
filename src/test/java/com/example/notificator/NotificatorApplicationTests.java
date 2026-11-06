package com.example.notificator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"telegram.enabled=false", "spring.datasource.url=jdbc:h2:mem:contexttest",
        "logging.file.name=build/test-logs/context.log"})
class NotificatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
