package io.github.atelci.callrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CallRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(CallRouterApplication.class, args);
    }
}
