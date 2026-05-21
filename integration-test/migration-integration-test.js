/*
 *  This script is intended to be executed in the mongosh of the mongo container during
 *  the integration test executed in the code-review-pipelines.
 *  To test it in local use this bash script:
 *     #!/bin/bash
 *     #sleep 15
 *     echo "Starting integration testing of the migration job..."
 * 
 *     docker cp migration-integration-test.js mongodb:/tmp/migration-integration-test.js
 * 
 *     # Executing the script and check the result
 *     docker exec mongodb mongosh -u admin -p password --authenticationDatabase admin /tmp/migration-integration-test.js
 *  
 *     # Check of the exit code of the script
 *     if [ $? -ne 0 ]; then
 *       echo "Integration test of migration job failed! Check the logs for more details ..."
 *       exit 1
 *     fi
 */


console.log("Starting integration test...")

// The service will find only wallet older than 9 months to move in the history db
let notValidDateString = new Date().toISOString();

// Add testing event to the db
let eventList = [
    {
      "_id": "a5ded083-5d8d-4771-8215-d01c1346fzz1",
      "transactionId": "075f6031-005c-4927-9a3d-e613d60cafba",
      "creationDate": "2023-03-29T16:44:14.959647218Z[Etc/UTC]",
      "data": {
        "email": {
          "data": "b282accf-995f-4211-939c-1ba0b4f3f255"
        },
        "paymentNotices": [
          {
            "paymentToken": "3d82a804063f46ed99dfa9e4a235774d",
            "rptId": "77777777777302001241098804227",
            "description": "TARI/TEFA 2021",
            "amount": 1000,
            "isAllCCP": false
          }
        ],
        "clientId": "CHECKOUT",
        "paymentTokenValiditySeconds": 0
      },
      "eventCode": "TRANSACTION_ACTIVATED_EVENT",
      "_class": "it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent"
    },
    {
      "_id": "a5ded083-5d8d-4771-8215-d01c1346fzz2",
      "transactionId": "522ab4f9-8d96-4bc7-a028-f0699d1baea2",
      "creationDate": "2023-03-29T16:44:15.020618605Z[Etc/UTC]",
      "data": {
        "email": {
          "data": "b282accf-995f-4211-939c-1ba0b4f3f255"
        },
        "paymentNotices": [
          {
            "paymentToken": "3d82a804063f46ed99dfa9e4a235774d",
            "rptId": "77777777777302001210146638735",
            "description": "TARI/TEFA 2021",
            "amount": 1000,
            "isAllCCP": false
          }
        ],
        "clientId": "CHECKOUT",
        "paymentTokenValiditySeconds": 0
      },
      "eventCode": "TRANSACTION_ACTIVATED_EVENT",
      "_class": "it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent"
    },
    {
      "_id": "a5ded083-5d8d-4771-8215-d01c1346fzz3",
      "transactionId": "da487724-1078-4f20-b095-ff45793e758b",
      "creationDate": "2023-03-29T16:44:15.119048976Z[Etc/UTC]",
      "data": {
        "email": {
          "data": "b282accf-995f-4211-939c-1ba0b4f3f255"
        },
        "paymentNotices": [
          {
            "paymentToken": "3d82a804063f46ed99dfa9e4a235774d",
            "rptId": "77777777777302001250013262570",
            "description": "TARI/TEFA 2021",
            "amount": 1000,
            "isAllCCP": false
          }
        ],
        "clientId": "CHECKOUT",
        "paymentTokenValiditySeconds": 0
      },
      "eventCode": "TRANSACTION_ACTIVATED_EVENT",
      "_class": "it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent"
    },
    // Should not be migrate: too recent
    {
     "_id": "a5ded083-5d8d-4771-8215-d01c1346fzz4",
     "transactionId": "zz587724-1078-4f20-b095-ff45793e758b",
     "creationDate": notValidDateString,
     "data": {
       "email": {
         "data": "b282accf-995f-4211-939c-1ba0b4f3f255"
       },
       "paymentNotices": [
         {
           "paymentToken": "3d82a804063f46ed99dfa9e4a235774d",
           "rptId": "77777777777302001250013262570",
           "description": "TARI/TEFA 2021",
           "amount": 1000,
           "isAllCCP": false
         }
       ],
       "clientId": "CHECKOUT",
       "paymentTokenValiditySeconds": 0
     },
     "eventCode": "TRANSACTION_ACTIVATED_EVENT",
     "_class": "it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent"
   }
]



console.log("Insert testing data into ecommerce.eventstore and ecommerce-history.eventstore");
//connect to Mongo DB
dbHistory = db.getSiblingDB("ecommerce-history");
dbHistory.getCollection('eventstore').insertMany([eventList[0],eventList[1]])
db = db.getSiblingDB("ecommerce")
db.getCollection('eventstore').insertMany([eventList[1],eventList[2],eventList[3]])

// Wait until the scheduler do the job and then check the result
console.log("Wait until the migration job is executed...")
sleep(20000);

if(!assertMigration(eventList, db, dbHistory)){
    console.log("Integration test failed!");
    quit(1);
}else{
    console.log("Integration test completed SUCCESSFULLY!");
}



// Check that the inserted data are moved from ecommerce.eventstore to ecommerce-history.eventstore
function assertMigration(eventList, dbCollection, dbHistoryCollection){
    let docsIdDbArray = dbCollection.getCollection('eventstore').find().toArray().map(e => e._id);
    let docsIdDbHistoryArray = dbHistoryCollection.getCollection('eventstore').find().toArray().map(e => e._id);

    // Check the transaction that were not in the ecommerce-history now are migrated by the script
    let documentAreMigrated = eventList.slice(0,eventList.length-1).map(e => e._id).reduce(
        (accumulator, currentIdValue) => accumulator && docsIdDbHistoryArray.includes(currentIdValue),
        true,
    );

    // Check that the recent event was not migrated
    let recentEvent = eventList[eventList.length-1]
    let tooRecentDocumentNotMigrated = !docsIdDbHistoryArray.includes(recentEvent._id)

    console.log("Test assert: ",documentAreMigrated && tooRecentDocumentNotMigrated)

    return documentAreMigrated && tooRecentDocumentNotMigrated;
}


