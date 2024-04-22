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
| CLOSURE_REQUESTED       | ✅                     |
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
| MONGO_PORT                                                   | Port used for connecting to MongoDB instance                                                                                                                                                                                                                                                        | string  |         |
| MONGO_MIN_POOL_SIZE                                          | Min amount of connections to be retained into connection pool. See docs *                                                                                                                                                                                                                           | string  |         |
| MONGO_MAX_POOL_SIZE                                          | Max amount of connections to be retained into connection pool.See docs *                                                                                                                                                                                                                            | string  |         |
| MONGO_MAX_IDLE_TIMEOUT_MS                                    | Max timeout after which an idle connection is killed in milliseconds. See docs *                                                                                                                                                                                                                    | string  |         |
| MONGO_CONNECTION_TIMEOUT_MS                                  | Max time to wait for a connection to be opened. See docs *                                                                                                                                                                                                                                          | string  |         |
| MONGO_SOCKET_TIMEOUT_MS                                      | Max time to wait for a command send or receive before timing out. See docs *                                                                                                                                                                                                                        | string  |         |
| MONGO_SERVER_SELECTION_TIMEOUT_MS                            | Max time to wait for a server to be selected while performing a communication with Mongo in milliseconds. See docs *                                                                                                                                                                                | string  |         |
| MONGO_WAITING_QUEUE_MS                                       | Max time a thread has to wait for a connection to be available in milliseconds. See docs *                                                                                                                                                                                                          | string  |         |
| MONGO_HEARTBEAT_FREQUENCY_MS                                 | Hearth beat frequency in milliseconds. This is an hello command that is sent periodically on each active connection to perform an health check. See docs *                                                                                                                                          | string  |         |
| MONGO_REPLICA_SET_OPTION                                     | The replica set connection string option valued with the name of the replica set. See docs *                                                                                                                                                                                                        | string  |         |
| ECOMMERCE_DATABASE_NAME                                      | Mongo ecommerce database name                                                                                                                                                                                                                                                                       | string  |         |
| TRANSACTION_EXPIRED_EVENTS_QUEUE_NAME                        | Transaction expired event queue name. This is the queue where transaction expired events will be sent                                                                                                                                                                                               | string  |         |
| QUEUE_TRANSIENT_CONNECTION_STRING                            | Transient queues connection string                                                                                                                                                                                                                                                                  | string  |         |
| TRANSIENT_QUEUES_TTL_SECONDS                                 | TTL to be used when sending events on transient queues                                                                                                                                                                                                                                              | number  | 7 days  |
| PENDING_TRANSACTIONS_SCHEDULE_CRON                           | Pending transactions batch execution chron expression                                                                                                                                                                                                                                               | string  |         |
| PENDING_TRANSACTIONS_WINDOWS_BATCH_EXECUTION_RATE_MULTIPLIER | Pending transactions execution rate multiplier (used for calculate transactions window, see [Transactions to analyze time window](#Transactions-to-analyze-time-window)                                                                                                                             | int     |         |
| PENDING_TRANSACTIONS_PARALLEL_EVENTS_TO_PROCESS              | Pending transactions parallel events to be processed                                                                                                                                                                                                                                                | int     |         |
| PENDING_TRANSACTIONS_MAX_DURATION_SECONDS                    | Single batch max duration in seconds. Indicate the max time to wait for a single batch iteration to be completed. If negative the execution-rate-inter-time/2 will be used (Ex: if batch is configured to be executed every 10 minutes max duration will be set to 5 minutes                        | int     | -1      |
| PENDING_TRANSACTIONS_SEND_PAYMENT_RESULT_TIMEOUT_SECONDS     | Timeout for a `sendPaymentResult` callback (`POST /user-receipts` on `transactions-service`) to be received. This timeout is evaluated for a transaction stuck in CLOSED status for which an OK authorization outcome has been received by Nodo and inhibits expiration if it is yet to be reached. | int     |         |
| PENDING_TRANSACTIONS_MAX_TRANSACTIONS_PER_PAGE               | Max transactions to be fetched by paginated query                                                                                                                                                                                                                                                   | int     |         |
| PENDING_TRANSACTIONS_PAGE_ANALYSIS_DELAY_SECONDS             | Delay for the next pending transaction page to be analyzed (in seconds). This parameter in pair with `PENDING_TRANSACTIONS_MAX_TRANSACTIONS_PER_PAGE` define the max batch tps                                                                                                                      | int     |         |
| DEAD_LETTER_LISTENER_TRANSACTION_FIXED_DELAY_MILLIS          | Transaction dead letter queue poll delay in milliseconds                                                                                                                                                                                                                                            | int     |         |
| DEAD_LETTER_LISTENER_TRANSACTION_MAX_MESSAGE_PER_POLL        | Transaction dead letter queue max messages to retrieve per poll                                                                                                                                                                                                                                     | int     |         |
| DEAD_LETTER_LISTENER_TRANSACTION_QUEUE_NAME                  | Transaction dead letter queue name                                                                                                                                                                                                                                                                  | string  |         |
| DEAD_LETTER_LISTENER_NOTIFICATION_FIXED_DELAY_MILLIS         | Notification dead letter queue poll delay in milliseconds                                                                                                                                                                                                                                           | int     |         |
| DEAD_LETTER_LISTENER_NOTIFICATION_MAX_MESSAGE_PER_POLL       | Notification dead letter queue max messages to retrieve per poll                                                                                                                                                                                                                                    | int     |         |
| DEAD_LETTER_LISTENER_NOTIFICATION_QUEUE_NAME                 | Notification dead letter queue name                                                                                                                                                                                                                                                                 | string  |         |
| ECOMMERCE_STORAGE_DEAD_LETTER_QUEUE_ACCOUNT_NAME             | Dead letter storage account name                                                                                                                                                                                                                                                                    | string  |         |
| ECOMMERCE_STORAGE_DEAD_LETTER_QUEUE_ENDPOINT                 | Dead letter storage account endpoint                                                                                                                                                                                                                                                                | string  |         |
| ECOMMERCE_STORAGE_DEAD_LETTER_QUEUE_KEY                      | Dead letter storage account key                                                                                                                                                                                                                                                                     | string  |         |

(*): for Mongo connection string options
see [docs](https://www.mongodb.com/docs/drivers/java/sync/v4.3/fundamentals/connection/connection-options/#connection-options)

### Pending transaction TPS configuration

Pending transaction are processed at a configurable TPS (transactions per seconds) rate.
This can be done changing the below parameters:

- `PENDING_TRANSACTIONS_MAX_TRANSACTIONS_PER_PAGE` parameter allow to specify how many transactions to retrieve for each
page -> chunk size
- `PENDING_TRANSACTIONS_PAGE_ANALYSIS_DELAY_SECONDS` parameter allow to specify time to be waited between the next page
to be analyzed -> chunk rate

Example of configurations:

| Max transaction per page | Delay between pages | Max TPS |
|--------------------------|---------------------|---------|
| 1                        | 1                   | 1       |
| 5                        | 1                   | 5       |
| 5                        | 2                   | 2,5     |

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

## CI

Repo has Github workflow and actions that trigger Azure devops deploy pipeline once a PR is merged on main branch.

In order to properly set version bump parameters for call Azure devops deploy pipelines will be check for the following
tags presence during PR analysis:

| Tag                | Semantic versioning scope | Meaning                                                           |
|--------------------|---------------------------|-------------------------------------------------------------------|
| patch              | Application version       | Patch-bump application version into pom.xml and Chart app version |
| minor              | Application version       | Minor-bump application version into pom.xml and Chart app version |
| major              | Application version       | Major-bump application version into pom.xml and Chart app version |
| ignore-for-release | Application version       | Ignore application version bump                                   |
| chart-patch        | Chart version             | Patch-bump Chart version                                          |
| chart-minor        | Chart version             | Minor-bump Chart version                                          |
| chart-major        | Chart version             | Major-bump Chart version                                          |
| skip-release       | Any                       | The release will be skipped altogether                            |

For the check to be successfully passed only one of the `Application version` labels and only ones of
the `Chart version` labels must be contemporary present for a given PR or the `skip-release` for skipping release step
