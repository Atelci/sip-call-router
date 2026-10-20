package io.github.atelci.callrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // LogTailer polls the log file
public class CallRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(CallRouterApplication.class, args);
    }
}
