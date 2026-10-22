package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LoggerClientTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final LoggerClient client = new LoggerClient(rabbit);
    private final TransactionRequest transaction = mock(TransactionRequest.class);

    @Test
    void shouldPublishTransactionWhenBrokerAcceptsMessage() {
        // Act
        boolean logged = client.log(transaction);
        // Assert
        assertThat(logged).isTrue();
        verify(rabbit).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReportFailureWhenBrokerRejectsMessage() {
        // Arrange
        doThrow(new AmqpException("offline")).when(rabbit)
            .convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
        // Act
        boolean logged = client.log(transaction);
        // Assert
        assertThat(logged).isFalse();
    }
}
