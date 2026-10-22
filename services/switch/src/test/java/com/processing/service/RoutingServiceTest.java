package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RoutingServiceTest {
    private final RoutingService service = new RoutingService(new SwitchProperties(
        "test", Map.of("400000", "ISS001"), null, null, null, null, null, null));

    @ParameterizedTest
    @ValueSource(strings = {"400000", "4000001234567890"})
    void shouldResolveIssuerWhenBinIsKnown(String pan) {
        // Act
        String issuer = service.getIssuerIdByPan(pan);
        // Assert
        assertThat(issuer).isEqualTo("ISS001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"40000", "999999", "9999991234567890"})
    void shouldRejectPanWhenBinCannotBeResolved(String pan) {
        // Act / Assert
        assertThatThrownBy(() -> service.getIssuerIdByPan(pan)).isInstanceOf(UnknownBinException.class);
    }
}
