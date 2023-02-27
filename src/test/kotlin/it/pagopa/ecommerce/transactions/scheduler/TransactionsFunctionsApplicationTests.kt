package it.pagopa.ecommerce.transactions.scheduler

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(locations = ["classpath:application-tests.properties"])
class TransactionsFunctionsApplicationTests {
    @Test
    fun contextLoads() {
    }
}
