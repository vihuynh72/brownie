package io.github.vihuynh72.brownie.api.question;

import io.github.vihuynh72.brownie.core.question.QuestionRepository;
import io.github.vihuynh72.brownie.core.question.QuestionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class QuestionConfig {

    @Bean
    QuestionService questionService(QuestionRepository questionRepository) {
        return new QuestionService(questionRepository);
    }
}
