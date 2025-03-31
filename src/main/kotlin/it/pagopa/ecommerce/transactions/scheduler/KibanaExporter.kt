package it.pagopa.ecommerce.transactions.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


enum class Export(
    val exportFileNameTemplate: String,
    val queryFormatterFunction: (startDate: OffsetDateTime, endDate: OffsetDateTime, searchAfter: String?) -> String,
    val csvHeader: String,
    val exportStartDate: OffsetDateTime = OffsetDateTime.parse("2025-03-01T00:00:00.000+01:00"),
    val exportEndDate: OffsetDateTime = OffsetDateTime.parse("2025-03-27T00:00:00.000+01:00"),
    val recordWriterFunction: (bufferedWriter: BufferedWriter, responseFields: JsonNode) -> Unit
) {
    AVAILABILITY(
        exportFileNameTemplate = "export-availability-%s.csv",
        queryFormatterFunction = { startDate, endDate, searchAfter ->
            """
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
        }, csvHeader = "TIMESTAMP;URI;HTTP_STATUS_CODE",
        recordWriterFunction = { bufferedWriter, responseNode ->
            val timestamp =
                OffsetDateTime.parse(responseNode["@timestamp"].first()?.textValue())
                    .atZoneSameInstant(ZoneId.of("+01:00"))
            bufferedWriter.write(
                "${timestamp};${
                    responseNode["url.original"].first()?.textValue()
                };${responseNode["http.response.status_code"]?.first()?.intValue()?.toString() ?: "-"}"
            )
        }
    ),
    EXTERNAL_REQUESTS(
        exportFileNameTemplate = "export-external-%s.csv",
        queryFormatterFunction = { startDate, endDate, searchAfter ->
            """
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
        },
        csvHeader = "TIMESTAMP;API_NAME;DURATION(MS);NPG_CORRELATION_ID",
        recordWriterFunction = { bufferedWriter, responseNode ->
            val timestamp =
                OffsetDateTime.parse(responseNode["@timestamp"].first()?.textValue())
                    .atZoneSameInstant(ZoneId.of("+01:00"))
            bufferedWriter.write(
                "${timestamp};${
                    responseNode["span.name"].first().textValue()
                };${
                    responseNode["span.duration.us"].first().longValue().div(1000)
                };${responseNode["labels.npg_correlation_id"].first().textValue()}"
            )
        }
    ),
    INTERNAL_REQUESTS(
        exportFileNameTemplate = "export-internal-%s.csv",
        queryFormatterFunction = { startDate, endDate, searchAfter ->
            """
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
        },
        csvHeader = "TIMESTAMP;API_NAME;DURATION(MS);NPG_CORRELATION_ID",
        recordWriterFunction = { bufferedWriter, responseNode ->
            val timestamp =
                OffsetDateTime.parse(responseNode["@timestamp"].first()?.textValue())
                    .atZoneSameInstant(ZoneId.of("+01:00"))
            bufferedWriter.write(
                "${timestamp};${
                    responseNode["span.name"].first().textValue()
                };${
                    responseNode["span.duration.us"].first().longValue().div(1000)
                };${responseNode["labels.npg_correlation_id"].first().textValue()}"
            )
        }
    ),


}

fun main() {
    Export
        .values()
        .forEach { export ->
            println(
                """
                Export for $export started with following parameters:
                export from: ${export.exportStartDate} to ${export.exportEndDate}
                csv header: ${export.csvHeader}
                export file name template: [${export.exportFileNameTemplate}]
                query: ${export.queryFormatterFunction}
                """".trimIndent()
            )
            val daysDiff = IntRange(
                start = 1,
                endInclusive = Duration.between(export.exportStartDate, export.exportEndDate).toDays().toInt()
            )
            daysDiff.forEach { day ->
                val exportDir = File("./$export/")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }
                val startSearchDate = export.exportStartDate + Duration.ofDays((day - 1).toLong())
                val exportFile = File(
                    "./$export/${
                        export.exportFileNameTemplate.format(
                            startSearchDate.format(
                                DateTimeFormatter.ISO_LOCAL_DATE
                            )
                        )
                    }"
                )
                val fos = BufferedWriter(FileWriter(exportFile))
                val csvHeader = export.csvHeader
                fos.write(csvHeader)
                fos.newLine()
                fos.use { bufferedWriter ->
                    findAllRecordsInADay(day, bufferedWriter, export)
                }

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


fun findAllRecordsInADay(dayOfMonth: Int, bufferedWriter: BufferedWriter, export: Export) {
    val startSearchDate = export.exportStartDate + Duration.ofDays((dayOfMonth - 1).toLong())
    val endSearchDate = startSearchDate + Duration.ofDays(1)
    var continueSearch = true
    var searchAfter: String? = null

    while (continueSearch) {
        val searchAfterConversion = searchAfter.let { Instant.ofEpochSecond((it?.toLong()?.div(1000)) ?: 0) }
        println("Processing items for time interval $startSearchDate - $endSearchDate, search after: $searchAfterConversion")
        val (processed, lastItemSort) = processResults(
            postRequest(
                startDate = startSearchDate,
                endDate = endSearchDate,
                searchAfter = searchAfter,
                export = export
            ),
            bufferedWriter,
            export
        )
        searchAfter = lastItemSort
        continueSearch = processed > 0
    }
}

fun processResults(response: String, bufferedWriter: BufferedWriter, export: Export): Pair<Int, String?> {
    var processed = 0
    val objectMapper = ObjectMapper()


    val jsonNode = objectMapper.readTree(response)
    val hits = jsonNode["hits"]["hits"]


    hits.forEach {
        val fields = it["fields"]
        export.recordWriterFunction(bufferedWriter, fields)
        bufferedWriter.newLine()
        processed++
    }
    val lastHit = hits.lastOrNull()
    val lastSortField = lastHit?.get("sort")?.first()?.longValue()?.toString()
    return Pair(processed, lastSortField)
}


fun postRequest(startDate: OffsetDateTime, endDate: OffsetDateTime, searchAfter: String?, export: Export): String {
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
        .bodyValue(export.queryFormatterFunction(startDate, endDate, searchAfter))
        .header("Content-Type", "application/json")
        .header("kbn-xsrf", "report")
        .retrieve()
        .bodyToMono(String::class.java)
        //.delayElement(Duration.ofSeconds(1))
        .block()!!


}