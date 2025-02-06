package it.pagopa.ecommerce.transactions.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.integration.config.EnableIntegration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import java.io.BufferedWriter
import java.io.FileWriter
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@SpringBootApplication
@EnableIntegration
@EnableScheduling
class TransactionsSchedulerApplication

val logger: Logger = LoggerFactory.getLogger(TransactionsSchedulerApplication::class.java)
fun main(args: Array<String>) {
    runApplication<TransactionsSchedulerApplication>(*args)
    val daysDiff = IntRange(start = 31, endInclusive = Duration.between(startDate, endDate).toDays().toInt())
    daysDiff.forEach { day ->
        val fos = BufferedWriter(FileWriter("export-availability-$day.csv"))
        //fos.write("TIMESTAMP;API_NAME;DURATION(MS);NPG_CORRELATION_ID")
        fos.write("TIMESTAMP;URI;HTTP_STATUS_CODE")
        fos.newLine()
        fos.use { bufferedWriter ->
            findAllRecordsInADay(day, bufferedWriter)
        }

    }


}

val webClient = WebClient
    .builder()
    .filter(
        ExchangeFilterFunctions
            .basicAuthentication("elastic", System.getenv("KIBANA_PROD_PWD"))
    )
    .exchangeStrategies(
        ExchangeStrategies
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(25 * 1024 * 1024) }
            .build()
    )
    .baseUrl("https://weuprod.kibana.internal.platform.pagopa.it")
    .build()

val startDate: OffsetDateTime = OffsetDateTime.parse("2025-01-01T00:00:00.000+01:00")
val endDate: OffsetDateTime = OffsetDateTime.parse("2025-02-01T00:00:00.000+01:00")


fun findAllRecordsInADay(dayOfMonth: Int, bufferedWriter: BufferedWriter) {
    val startSearchDate = startDate + Duration.ofDays((dayOfMonth - 1).toLong())
    val endSearchDate = startSearchDate + Duration.ofDays(1)
    var continueSearch = true
    var searchAfter: String? = null

    while (continueSearch) {
        val searchAfterConversion = searchAfter.let { Instant.ofEpochSecond((it?.toLong()?.div(1000)) ?: 0) }
        logger.info("Processing items for time interval $startSearchDate - $endSearchDate, search after: $searchAfterConversion")
        val (processed, lastItemSort) = processResults(
            postRequest(
                startDate = startSearchDate,
                endDate = endSearchDate,
                searchAfter = searchAfter,
            ),
            bufferedWriter
        )
        searchAfter = lastItemSort
        continueSearch = processed > 0
    }
}

fun processResults(response: String, bufferedWriter: BufferedWriter): Pair<Int, String?> {
    var processed = 0
    val objectMapper = ObjectMapper()


    val jsonNode = objectMapper.readTree(response)
    val hits = jsonNode["hits"]["hits"]


    hits.forEach {
        val fields = it["fields"]
        val timestamp =
            OffsetDateTime.parse(fields["@timestamp"].first()?.textValue()).atZoneSameInstant(ZoneId.of("+01:00"))
        /*bufferedWriter.write(
            "${timestamp};${
                fields["span.name"].first().textValue()
            };${
                fields["span.duration.us"].first().longValue().div(1000)
            };${fields["labels.npg_correlation_id"].first().textValue()}"
        )*/
        bufferedWriter.write(
            "${timestamp};${
                fields["url.original"].first()?.textValue()
            };${fields["http.response.status_code"]?.first()?.intValue()?.toString() ?: "-"}"
        )
        bufferedWriter.newLine()
        processed++
    }
    val lastHit = hits.lastOrNull()
    val lastSortField = lastHit?.get("sort")?.first()?.longValue()?.toString()
    return Pair(processed, lastSortField)
}


fun postRequest(startDate: OffsetDateTime, endDate: OffsetDateTime, searchAfter: String?): String {
    val bodyQueryAvailability = """
        {
          "sort": [
            {
              "@timestamp": {
                "order": "asc",
                "unmapped_type": "boolean"
              }
            }
          ],
          "fields": [
            {
              "field": "@timestamp",
              "format": "strict_date_optional_time"
            },
            {
              "field": "url.original"
            },
            {
              "field": "http.response.status_code"
            }
          ],
          "size": 1000,
          "query": {
            "bool": {
              "must": [],
              "filter": [
                {
                  "bool": {
                    "should": [
                      {
                        "wildcard": {
                          "url.original": {
                            "value": "*xpay.nexigroup.com*"
                          }
                        }
                      }
                    ],
                    "minimum_should_match": 1
                  }
                },
                {
                  "range": {
                    "@timestamp": {
                      "format": "strict_date_optional_time",
                      "gte": "${startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))}",
                      "lte": "${endDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))}"
                    }
                  }
                }
              ],
              "should": [],
              "must_not": []
            }
          }
          ${
        if (searchAfter != null) {
            ",\"search_after\": [$searchAfter]"
        } else {
            ""
        }
    }
        }
    """.trimIndent()
    val bodyQueryExternalRequests = """
        {
          "sort": [
            {
              "@timestamp": {
                "order": "asc",
                "unmapped_type": "boolean"
              }
            }
          ],
           "fields": [
            {
              "field": "@timestamp",
              "format": "strict_date_optional_time"
            },
             {
              "field": "labels.npg_correlation_id"
            },
             {
              "field": "span.name"
            }, 
            {
              "field": "span.duration.us"
            }
        
          ],
          "size": 1000,
          "query": {
            "bool": {
              "must": [],
              "filter": [
                {
                  "bool": {
                    "should": [
                      {
                "bool": {
                  "should": [
                    {
                      "term": {
                        "span.name": {
                          "value": "NpgClient#confirmPayment"
                        }
                      }
                    }
                  ],
                  "minimum_should_match": 1
                }
              },
              {
                "bool": {
                  "should": [
                    {
                      "term": {
                        "span.name": {
                          "value": "NpgClient#refundPayment"
                          }
                        }
                      }
                    ],
                    "minimum_should_match": 1
                  }
              }
            ],
            "minimum_should_match": 1
          }
                },
                {
                  "range": {
                    "@timestamp": {
                      "format": "strict_date_optional_time",
                      "gte": "${startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))}",
                      "lte": "${endDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))}"
                    }
                  }
                }
              ],
              "should": [],
              "must_not": []
            }
          }
          ${
        if (searchAfter != null) {
            ",\"search_after\": [$searchAfter]"
        } else {
            ""
        }
    }
        }
    """.trimIndent()
    val bodyQueryInternalRequest = """
        {
          "sort": [
            {
              "@timestamp": {
                "order": "asc",
                "unmapped_type": "boolean"
              }
            }
          ],
          "fields": [
            {
              "field": "@timestamp",
              "format": "strict_date_optional_time"
            },
            {
              "field": "labels.npg_correlation_id"
            },
             {
              "field": "span.name"
            },
            {
              "field": "span.duration.us"
            }
          ],
          "size": 1000,
          "query": {
            "bool": {
              "must": [],
                "filter": [
                        {
                          "bool": {
                            "should": [
                              {
                                "bool": {
                                  "should": [
                                    {
                                      "term": {
                                        "span.name": {
                                          "value": "NpgClient#buildForm"
                                        }
                                      }
                                    }
                                  ],
                                  "minimum_should_match": 1
                                }
                              },
                              {
                                "bool": {
                                  "should": [
                                    {
                                      "term": {
                                        "span.name": {
                                          "value": "NpgClient#getCardData"
                                        }
                                      }
                                    }
                                  ],
                                  "minimum_should_match": 1
                                }
                              },
                              {
                                "bool": {
                                  "should": [
                                    {
                                      "term": {
                                        "span.name": {
                                          "value": "NpgClient#getState"
                                        }
                                      }
                                    }
                                  ],
                                  "minimum_should_match": 1
                                }
                              },
                              {
                                "bool": {
                                  "should": [
                                    {
                                      "term": {
                                        "span.name": {
                                          "value": "NpgClient#getOrder"
                                        }
                                      }
                                    }
                                  ],
                                  "minimum_should_match": 1
                                }
                              }
                            ],
                            "minimum_should_match": 1
                          }
                        },
                        {
                          "range": {
                    "@timestamp": {
                      "format": "strict_date_optional_time",
                      "gte": "${startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))}",
                      "lte": "${endDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))}"
                    }
                  }
                }
              ],
              "should": [],
              "must_not": []
            }
          }
          ${
        if (searchAfter != null) {
            ",\"search_after\": [$searchAfter]"
        } else {
            ""
        }
    }
        }
    """.trimIndent()
    return webClient
        .post()
        .uri {
            val uri = it
                .path("kibana/s/ecommerce/api/console/proxy")
                .queryParam("path", "traces-apm*,apm-*,logs-apm*,apm-*,metrics-apm*,apm-*/_search")
                .queryParam("method", "POST")
                .build()
            uri
        }
        .bodyValue(bodyQueryAvailability)
        .header("Content-Type", "application/json")
        .header("kbn-xsrf", "report")
        .retrieve()
        .bodyToMono(String::class.java)
        //.delayElement(Duration.ofSeconds(1))
        .block()!!


}