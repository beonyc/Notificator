package com.example.notificator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "telegram.enabled=false")
class NotificatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
