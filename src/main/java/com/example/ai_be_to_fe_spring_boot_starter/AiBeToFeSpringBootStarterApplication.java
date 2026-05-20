package com.example.ai_be_to_fe_spring_boot_starter;

import com.example.ai_be_to_fe_spring_boot_starter.autoconfigure.EnableAiFeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAiFeGenerator
public class AiBeToFeSpringBootStarterApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiBeToFeSpringBootStarterApplication.class, args);
	}

}
