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
| CANCELLATION_REQUESTED  | ✅                     |
| CLOSURE_ERROR           | ✅                     |
| CLOSED                  | ✅                     |
| NOTIFIED_KO             | ✅                     |
| NOTIFICATION_ERROR      | ✅                     |
| NOTIFICATION_REQUESTED  | ✅                     |
| EXPIRED_NOT_AUTHORIZED  | ❌                     |
| CANCELED                | ❌                     |
| UNAUTHORIZED            | ❌                     |
| NOTIFIED_OK             | ❌                     |
| EXPIRED                 | ❌                     |
| REFUND_REQUESTED        | ❌                     |
| REFUND_ERROR            | ❌                     |
| REFUNDED                | ❌                     |
| CANCELLATION_EXPIRED    | ❌                     |

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

### Transactions to analyze time window

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

## Configuration

### Environment variables

These are all environment variables needed by the application:

| Variable name                                                | Description                                                                                                                                                                                                                                                                                         | type    | default |
|--------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|---------|
| MONGO_HOST                                                   | Mongo hostname                                                                                                                                                                                                                                                                                      | string  |         |
| MONGO_USERNAME                                               | Mongo username                                                                                                                                                                                                                                                                                      | string  |         |
| MONGO_PORT                                                   | Mongo port                                                                                                                                                                                                                                                                                          | int     |         |
| MONGO_SSL_ENABLED                                            | Mongo SSL enabled                                                                                                                                                                                                                                                                                   | boolean |         |
| ECOMMERCE_DATABASE_NAME                                      | Mongo ecommerce database name                                                                                                                                                                                                                                                                       | string  |         |
| TRANSACTION_EXPIRED_EVENTS_QUEUE_NAME                        | Transaction expired event queue name. This is the queue where transaction expired events will be sent                                                                                                                                                                                               | string  |         |
| TRANSIENT_QUEUES_TTL_SECONDS                                 | TTL to be used when sending events on transient queues                                                                                                                                                                                                                                              | number  | 7 days  |
| PENDING_TRANSACTIONS_SCHEDULE_CRON                           | Pending transactions batch execution chron expression                                                                                                                                                                                                                                               | string  |         |
| PENDING_TRANSACTIONS_WINDOWS_BATCH_EXECUTION_RATE_MULTIPLIER | Pending transactions execution rate multiplier (used for calculate transactions window, see [Transactions to analyze time window](#Transactions-to-analyze-time-window)                                                                                                                             | int     |         |
| PENDING_TRANSACTIONS_PARALLEL_EVENTS_TO_PROCESS              | Pending transactions parallel events to be processed                                                                                                                                                                                                                                                | int     |         |
| PENDING_TRANSACTIONS_MAX_DURATION_SECONDS                    | Single batch max duration in seconds. Indicate the max time to wait for a single batch iteration to be completed. If negative the execution-rate-inter-time/2 will be used (Ex: if batch is configured to be executed every 10 minutes max duration will be set to 5 minutes                        | int     | -1      |
| PENDING_TRANSACTIONS_SEND_PAYMENT_RESULT_TIMEOUT_SECONDS     | Timeout for a `sendPaymentResult` callback (`POST /user-receipts` on `transactions-service`) to be received. This timeout is evaluated for a transaction stuck in CLOSED status for which an OK authorization outcome has been received by Nodo and inhibits expiration if it is yet to be reached. | int     |         |

---

## Run locally with Docker

`docker build -t pagopa-ecommerce-transactions-scheduler-service .`

`docker run -p 8999:8080 pagopa-ecommerce-transactions-scheduler-service`

## Run locally with Maven

`mvn validate` used to perform ecommerce-commons library checkout from git repo and install through maven plugin

`mvn clean spring-boot:run` build and run service with spring locally

For testing purpose the commons reference can be changed from a specific release to a branch by changing the following
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

## Kubernetes deployment notes

By conventions all module are deployed as <<repo-name>> without '-'
Since for this module, applying the above rule, the name would result
into `pagopaecommercetransactionsschedulerservice-microservice-chart` that would lead to
a too long name with "beta-" prefix for blue deployments the full name has been overridden
with the following `pagopaecommercetxschedulerservice-microservice-chart`.
Any component such as deployment, pods, config maps, etc. can be searched with this reduced name
