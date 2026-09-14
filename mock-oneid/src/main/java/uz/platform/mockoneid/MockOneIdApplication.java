package uz.platform.mockoneid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MockOneIdProperties.class)
public class MockOneIdApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockOneIdApplication.class, args);
    }
}
