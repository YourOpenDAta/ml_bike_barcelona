curl -v  orion:1026/ngsi-ld/v1/subscriptions/ -s -S -H 'Content-Type: application/ld+json' -d @- <<EOF
{
  "description": "A subscription to get request predictions for bikes in Barcelona",
  "type": "Subscription",
  "entities": [{
    "id": "urn:ngsi-ld:ReqBarcelonaBikePrediction1",
    "type": "ReqBarcelonaBikePrediction"
    }],
  "watchedAttributes": [
      "predictionId",
      "socketId",
      "idStation",
      "hour",
      "weekday"
    ],
  "notification": {
    "endpoint": {
      "uri": "http://spark-master-bike-barcelona:9002",
      "accept": "application/json"
    }
  },
    "@context": [
        "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld"
    ]
}
EOF

