package com.smsframework.dlr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan
public class DlrReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlrReceiverApplication.class, args);
    }
}
