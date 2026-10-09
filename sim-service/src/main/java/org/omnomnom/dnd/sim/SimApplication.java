package org.omnomnom.dnd.sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimApplication.class, args);
    }
}
