package it.pagopa.ecommerce.transactions.scheduler.exceptions

import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument

class LockNotAcquiredException(jobName: String, exclusiveLockDocument: ExclusiveLockDocument) :
    RuntimeException(
        "Lock not acquired for job with name: [$jobName] and locking key: [${exclusiveLockDocument.id()}]"
    )
