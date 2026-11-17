package com.ykm.common.common_lib;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CommonLibApplicationTests.TestConfig.class)
class CommonLibApplicationTests {

    @Configuration
    static class TestConfig {
    }

    @Test
    void contextLoads() {
    }
}
