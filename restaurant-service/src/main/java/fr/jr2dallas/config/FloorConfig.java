package fr.jr2dallas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.jr2dallas.domains.FloorLayout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class FloorConfig {

    @Bean
    public FloorLayout floorLayout(ObjectMapper objectMapper, RestaurantProperties props) throws IOException {
        try (InputStream in = new ClassPathResource("restaurant-layout.json").getInputStream()) {
            return new FloorLayout(objectMapper.readTree(in), props.floor().pass(), props.floor().waiterPosts());
        }
    }
}
