package com.github.hgdcoder.utils.concurrent.threadpool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomThreadPoolConfigTest {

    @Test
    void shouldBuildValidConfig() {
        CustomThreadPoolConfig config = CustomThreadPoolConfig.builder()
                .corePoolSize(2)
                .maximumPoolSize(4)
                .queueCapacity(16)
                .build();

        assertEquals(2, config.getCorePoolSize());
        assertEquals(4, config.getMaximumPoolSize());
        assertEquals(16, config.getQueueCapacity());
    }

    @Test
    void shouldRejectInvalidPoolSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CustomThreadPoolConfig.builder()
                        .corePoolSize(4)
                        .maximumPoolSize(2)
                        .build()
        );
    }
}
