package org.fiware.cosmos.orion.spark.connector.prediction

import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.fiware.cosmos.orion.spark.connector.{ContentType, HTTPMethod, NGSILDReceiver, OrionSink, OrionSinkObject}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.feature.{VectorAssembler}
import org.apache.spark.ml.regression.{DecisionTreeRegressor}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.DataStreamReader
import java.util.{Date, TimeZone}
import java.text.SimpleDateFormat
import org.apache.spark.sql.types.{StringType, DoubleType, StructField, StructType}
import scala.io.Source

import org.mongodb.scala.model.Filters.{equal, gt, and}
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Sorts

case class PredictionResponse(socketId: String, predictionId: String, predictionValue: Int, idStation: Int, weekday: Int, hour: Int) {
  override def toString :String = s"""{
  "socketId": { "value": "${socketId}", "type": "Property"},
  "predictionId": { "value":"${predictionId}", "type": "Property"},
  "predictionValue": { "value":${predictionValue}, "type": "Property"},
  "idStation": { "value":"${idStation}", "type": "Property"},
  "weekday": { "value":${weekday}, "type": "Property"},
  "hour": { "value": ${hour}, "type": "Property"}
  }""".trim()
}
case class PredictionRequest(id_estacion: Int, Ultima_medicion: Int, Seishora_anterior: Int, Nuevehora_anterior: Int, variacion_estaciones: Double, dia: Int, hora: Int, socketId: String, predictionId: String)

object PredictionJob {

  def readFile(filename: String): Seq[String] = {
    val bufferedSource = Source.fromFile(filename)
    val lines = (for (line <- bufferedSource.getLines()) yield line).toList
    bufferedSource.close
    return lines
  }

  final val variationStations = readFile("./prediction-job/array-load.txt")
  final val URL_CB = "http://orion:1026/ngsi-ld/v1/entities/urn:ngsi-ld:ResBarcelonaBikePrediction1/attrs"
  final val CONTENT_TYPE = ContentType.JSON
  final val METHOD = HTTPMethod.PATCH
  final val BASE_PATH = "./prediction-job"
  final val MONGO_USERNAME = System.getenv("MONGO_USERNAME")
  final val MONGO_PASSWORD = System.getenv("MONGO_PASSWORD")

    def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .appName("PredictingBikeBarcelona")
      .master("local[*]")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")
    
    println("STARTING BIKE BARCELONA....")
    
    val ssc = new StreamingContext(spark.sparkContext, Seconds(1))

    
    val dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))

    // Load model
    val model = PipelineModel.load(BASE_PATH+"/model")

    val eventStream = ssc.receiverStream(new NGSILDReceiver(9002))
    
    // Process event stream to get updated entities
    val processedDataStream = eventStream
      .flatMap(event => event.entities)
      .map(ent => {
        println(s"ENTITY RECEIVED: $ent")
        val idStation = ent.attrs("idStation")("value").toString.toInt
        val hour = ent.attrs("hour")("value").toString.toInt
        val weekday = ent.attrs("weekday")("value").toString.toInt
        val socketId = ent.attrs("socketId")("value").toString
        val predictionId = ent.attrs("predictionId")("value").toString
        val num = (idStation.toInt - 1 )
        val idVariationStation = num * 24 + hour
        val variationStation = variationStations(idVariationStation).toString.toDouble

        val dateNineHoursBefore = dateTimeFormatter.format(new Date(System.currentTimeMillis() - 3600 * 1000 *9))
        val dateSixHoursBefore = dateTimeFormatter.format(new Date(System.currentTimeMillis() - 3600 * 1000 *6))

        val mongoUri = s"mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@mongo:27017/bikes_barcelona.historical?authSource=admin"
        val mongoClient = MongoClients.create(mongoUri);
        val collection = mongoClient.getDatabase("bikes_barcelona").getCollection("historical")
        val filter1 = and(gt("update_date", dateNineHoursBefore), equal("station_id", idStation.toString))
        val filter2 = and(gt("update_date", dateSixHoursBefore), equal("station_id", idStation.toString))
        val docs1 = collection.find(filter1)
        val docs2 = collection.find(filter2)
        val lastMeasure = docs1.sort(Sorts.descending("update_date")).first().getString("num_bikes_available").toInt
        val nineHoursAgoMeasure = docs1.sort(Sorts.ascending("update_date")).first().getString("num_bikes_available").toInt
        val sixHoursAgoMeasure = docs2.sort(Sorts.ascending("update_date")).first().getString("num_bikes_available").toInt

        PredictionRequest(idStation, lastMeasure, sixHoursAgoMeasure, nineHoursAgoMeasure, variationStation, weekday, hour, socketId, predictionId)
      })

    // Feed each entity into the prediction model
    val predictionDataStream = processedDataStream
      .transform(rdd => {
        val df = spark.createDataFrame(rdd)
        val predictions = model
          .transform(df)
          .select("socketId","predictionId", "prediction", "id_estacion", "dia", "hora")

        predictions.toJavaRDD
    })
      .map(pred=> PredictionResponse(
        pred.get(0).toString,
        pred.get(1).toString,
        pred.get(2).toString.toFloat.round,
        pred.get(3).toString.toInt,
        pred.get(4).toString.toInt,
        pred.get(5).toString.toInt
      )
    )

    // Convert the output to an OrionSinkObject and send to Context Broker
    val sinkDataStream = predictionDataStream
      .map(res => OrionSinkObject(res.toString, URL_CB, CONTENT_TYPE, METHOD))

    // Add Orion Sink
    OrionSink.addSink(sinkDataStream)
    //sinkDataStream.print()
    //predictionDataStream.print()
    ssc.start()
    ssc.awaitTermination()
  }

}

 
  
