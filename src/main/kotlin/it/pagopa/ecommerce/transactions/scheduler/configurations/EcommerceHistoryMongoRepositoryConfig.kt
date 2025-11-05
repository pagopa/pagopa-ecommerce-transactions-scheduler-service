package it.pagopa.ecommerce.transactions.scheduler.configurations

import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories

@Configuration
@EnableReactiveMongoRepositories(
    basePackages = ["it.pagopa.ecommerce.transactions.scheduler.repositories.ecommercehistory"],
    reactiveMongoTemplateRef = "ecommerceHistoryReactiveMongoTemplate"
)
class EcommerceHistoryMongoRepositoryConfig {}
