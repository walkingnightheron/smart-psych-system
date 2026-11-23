package com.psych.assessment.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter STD_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.serializers(new LocalDateTimeSerializer(STD_FMT));
            builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                @Override
                public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    String text = p.getText();
                    if (text == null || text.isBlank()) {
                        return null;
                    }
                    String trimmed = text.trim();
                    try {
                        return LocalDateTime.parse(trimmed, STD_FMT);
                    } catch (DateTimeParseException ignored) {
                    }
                    try {
                        return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                    }
                    return LocalDateTime.parse(trimmed, SHORT_FMT);
                }
            });
        };
    }
}
