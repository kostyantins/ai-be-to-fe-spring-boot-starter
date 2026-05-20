package com.example.ai_be_to_fe_spring_boot_starter.autoconfigure;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the AI BE-to-FE Spring Boot starter.
 *
 * <p>Place this annotation on your {@code @SpringBootApplication} (or any
 * {@code @Configuration}) class to import {@link AiFeGeneratorAutoConfiguration}
 * and activate the GitHub webhook controller, AI code generator and pipeline
 * services contributed by the starter.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @SpringBootApplication
 * @EnableAiFeGenerator
 * public class OrdersServiceApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(OrdersServiceApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>You can still disable the starter at runtime by setting
 * {@code ai.fe-generator.enabled=false}.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AiFeGeneratorAutoConfiguration.class)
public @interface EnableAiFeGenerator {
}

