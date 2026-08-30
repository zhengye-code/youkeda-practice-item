package com.claw.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClawAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClawAssistantApplication.class, args);
	}

}
