package com.testpay.shield;

import com.testpay.shield.config.ShieldProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

/**
 * Entry point.
 * <ul>
 *   <li>{@code java -jar dispute-shield.jar} starts the web app (loads the trained model bundle).</li>
 *   <li>{@code java -jar dispute-shield.jar train --data=<dir> [--out=<bundle.json>]} trains on months 1-22
 *       and writes the model bundle + training report. No web server is started.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(ShieldProperties.class)
public class ShieldApplication {

    public static void main(String[] args) {
        boolean train = Arrays.asList(args).contains("train");
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ShieldApplication.class);
        if (train) {
            builder.web(WebApplicationType.NONE).profiles("train");
        }
        builder.run(args);
    }
}
