package it.pagopa.ecommerce.transactions.scheduler.repositories.ecommerce

import it.pagopa.ecommerce.commons.documents.DeadLetterEvent
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository interface DeadLetterEventRepository : ReactiveCrudRepository<DeadLetterEvent, String> {}
