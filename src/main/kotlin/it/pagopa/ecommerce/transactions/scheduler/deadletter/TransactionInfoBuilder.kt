package it.pagopa.ecommerce.transactions.scheduler.deadletter

import it.pagopa.ecommerce.transactions.scheduler.models.dto.TransactionResultDto
import it.pagopa.ecommerce.transactions.scheduler.repositories.TransactionsEventStoreRepository
import org.springframework.beans.factory.annotation.Autowired
import reactor.core.publisher.Mono

class TransactionInfoBuilder(
    @Autowired private val transactionsEventStoreRepository: TransactionsEventStoreRepository<Any>
) {

    fun getTransactionInfoByTransactionId(
        transactionId: String
    ): Mono<TransactionResultDto> {
        val events =
            Mono.just(transactionId).flatMapMany {
                transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(
                    transactionId
                )
            }

        return events
            .reduce(
                it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent
            )
            .cast(it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction::class.java)
            .flatMap { baseTransaction ->
                events.collectList().map { Pair(baseTransaction, it) }
            }
            .map { (baseTransaction, events) ->
                baseTransactionToTransactionInfoDto(baseTransaction, events)
            }
    }

}