# pagopa-ecommerce-transactions-scheduler-service

This is a PagoPA scheduler service containing all batches that perform scheduled operations on transactions.

## PendingTransactionBatch

Scheduled batch that analyze all transaction in a given time slot and send `TransactionExpiredEvent`
to the queue for all transactions that are in a non-final state.
Here a description of when expiration event is sent based on the transaction status:

| Transaction status      | Expiration event sent |
|-------------------------|-----------------------|
| ACTIVATED               | ✅                     |
| AUTHORIZATION_REQUESTED | ✅                     |
| AUTHORIZATION_COMPLETED | ✅                     |
| CLOSURE_ERROR           | ✅                     |
| CLOSED                  | ✅                     |
| EXPIRED_NOT_AUTHORIZED  | ❌                     |
| CANCELED                | ❌                     |
| UNAUTHORIZED            | ❌                     |
| NOTIFIED                | ❌                     |
| EXPIRED                 | ❌                     |
| REFUNDED                | ❌                     |

For each transaction found in one of those statuses  `TransactionExpiredEvent` and transaction view document status is
updated to `EXPIRED`.

In case of multiple events to be sent events queue visibility timeout is calculated so that all events are spread across
the batch execution inter-time

Example: given the batch execution time of 1 hour and 5 events to be sent then each event will have a visibility
timeout inter-time of 12 minutes, so:

| Event | Visibility timeout |
|-------|--------------------|
| E1    | now + 12 minute    |
| E2    | now + 24 minute    |
| E3    | now + 36 minute    |
| E4    | now + 48 minute    |
| E5    | now + 60 minute    |

This mechanism prevents the module that read those events to be flooded in case of too many events to send.

Once started the batch analyze all transactions in a given time windows calculated from the batch execution rate and the
value of the `PENDING_TRANSACTIONS_WINDOWS_BATCH_EXECUTION_RATE_MULTIPLIER` parameter.

Example:

- Batch execution cron expression values with: 0 0 */1 * * * (the batch will be executed every hour)
- batch execution rate multiplier valued with 2

| Batch execution time | Lower transaction time | Lower transaction time |
|----------------------|------------------------|------------------------|
| 10:00:00             | 07:00:00               | 09:00:00               |
| 11:00:00             | 08:00:00               | 10:00:00               |

This mechanism permits to recover any batch missing executions.
Setting this parameter to 1 will disable this mechanism and any execution will analyze only the transactions made during
the previous time slot.


---

## Run locally with Docker

`docker build -t pagopa-ecommerce-transactions-scheduler-service .`

`docker run -p 8999:80 pagopa-ecommerce-transactions-scheduler-service`

### Test

`curl http://localhost:8999/example`

## Run locally with Maven

`mvn validate` used to perform ecommerce-commons library checkout from git repo and install through maven plugin

`mvn clean spring-boot:run` build and run service with spring locally

For testing purpose the commons reference can be change from a specific release to a branch by changing the following
configurations tags inside `pom.xml`:

FROM:

```xml

<configuration>
    ...
    <scmVersionType>tag</scmVersionType>
    <scmVersion>${pagopa-ecommerce-commons.version}</scmVersion>
</configuration>
```

TO:

```xml

<configuration>
    ...
    <scmVersionType>branch</scmVersionType>
    <scmVersion>name-of-a-specific-branch-to-link</scmVersion>
</configuration>
```

updating also the commons library version to the one of the specific branch

## Code formatting

Code formatting checks are automatically performed during build phase.
If the code is not well formatted an error is raised blocking the maven build.

Helpful commands:

```sh
mvn spotless:check # --> used to perform format checks
mvn spotless:apply # --> used to format all misformatted files
```
