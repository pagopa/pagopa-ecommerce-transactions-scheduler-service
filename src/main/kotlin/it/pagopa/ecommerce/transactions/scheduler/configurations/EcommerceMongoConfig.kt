package it.pagopa.ecommerce.transactions.scheduler.configurations

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.ReactiveMongoTemplate

@Configuration
class EcommerceMongoConfig() {
    @Bean(name = ["ecommerceReactiveMongoClient"])
    fun ecommerceReactiveMongoClient(
        @Value("\${mongodb.uri}") uri: String,
    ): MongoClient {
        val connectionString = ConnectionString(uri)
        val settings = MongoClientSettings.builder().applyConnectionString(connectionString).build()
        return MongoClients.create(settings)
    }

    @Bean(name = ["ecommerceReactiveMongoTemplate"])
    fun ecommerceReactiveMongoTemplate(
        @Qualifier("ecommerceReactiveMongoClient") mongoClient: MongoClient,
        @Value("\${mongodb.ecommerce.database}") database: String
    ): ReactiveMongoTemplate {
        return ReactiveMongoTemplate(mongoClient, database)
    }

    @Bean(name = ["ecommerceHistoryReactiveMongoTemplate"])
    fun ecommerceHistoryReactiveMongoTemplate(
        @Qualifier("ecommerceReactiveMongoClient") mongoClient: MongoClient,
        @Value("\${mongodb.ecommerce_history.database}") database: String
    ): ReactiveMongoTemplate {
        return ReactiveMongoTemplate(mongoClient, database)
    }
}
