package edu.nyu.realtimebd.analytics.uber

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.DataFrame
import org.apache.spark.ml.PipelineStage
import org.apache.spark.ml.feature.StringIndexer
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.feature.OneHotEncoder
import org.apache.spark.mllib.regression.LinearRegressionWithSGD
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.linalg.{ Vector, Vectors }
import org.apache.spark.sql.Row;
import org.apache.spark.ml.tuning.CrossValidator
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.mllib.evaluation.RegressionMetrics
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.StructType
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.sql.DataFrame
import org.apache.spark.ml.tuning.CrossValidatorModel
import org.apache.spark.ml.PipelineModel
import org.apache.spark.sql.types.IntegerType
import org.apache.hadoop.mapred.InvalidInputException
import scala.collection.mutable.ListBuffer
import org.apache.spark.sql.types.FloatType
import edu.nyu.realtimebd.analytics.uber.domain.UberDomain.UberParams
import org.apache.spark.ml.regression.LinearRegressionModel

object UberAnalytics {
  val sparkConf = new SparkConf().setAppName("Uber-ANALYSIS").setMaster("local")
  val sc = new SparkContext(sparkConf)
  val sqlContext = new SQLContext(sc)
  val hiveCtxt = new HiveContext(sc)
  var df: DataFrame = _
  def initializeDataFrame(query: String): DataFrame = {
    //cache the dataframe
    if (df == null) {
      df = hiveCtxt.sql(query).na.drop().cache()
    }
    return df
  }
  def preprocessFeatures(df: DataFrame): DataFrame = {
    val stringColumns = Array("name")
    var indexModel: PipelineModel = null;
    var oneHotModel: PipelineModel = null;
    try {
      indexModel = sc.objectFile[PipelineModel]("uber.model.indexModel").first()

    } catch {
      case e: InvalidInputException => println()
    }
    if (indexModel == null) {
      val stringIndexTransformer: Array[PipelineStage] = stringColumns.map(
        cname => new StringIndexer().setInputCol(cname).setOutputCol(s"${cname}_index"))
      val indexedPipeline = new Pipeline().setStages(stringIndexTransformer)
      indexModel = indexedPipeline.fit(df)
      sc.parallelize(Seq(indexModel), 1).saveAsObjectFile("uber.model.indexModel")

    }

    var df_indexed = indexModel.transform(df)
    stringColumns.foreach { x => df_indexed = df_indexed.drop(x) }
    val indexedColumns = df_indexed.columns.filter(colName => colName.contains("_index"))
    val oneHotEncodedColumns = indexedColumns
    try {
      oneHotModel = sc.objectFile[PipelineModel]("uber.model.onehot").first()
    } catch {
      case e: InvalidInputException => println()
    }

    if (oneHotModel == null) {
      val oneHotTransformer: Array[PipelineStage] = oneHotEncodedColumns.map { cname =>
        new OneHotEncoder().
          setInputCol(cname).setOutputCol(s"${cname}_vect")
      }
      val oneHotPipeline = new Pipeline().setStages(oneHotTransformer)
      oneHotModel = oneHotPipeline.fit(df_indexed)

      sc.parallelize(Seq(oneHotModel), 1).saveAsObjectFile("uber.model.onehot")
    }

    df_indexed = oneHotModel.transform(df_indexed)
    indexedColumns.foreach { colName => df_indexed = df_indexed.drop(colName) }
    df_indexed
  }

  def buildUberPriceAnalysisModel(query: String) {

    initializeDataFrame(query)
    var df_indexed = preprocessFeatures(df)
    val df_splitData: Array[DataFrame] = df_indexed.randomSplit(Array(0.7, 0.3), 11l)
    val trainData = df_splitData(0)
    val testData = df_splitData(1)

    val testData_x = testData.drop("cost")
    val testData_y = testData.select("cost")
    val columnsToTransform = trainData.drop("cost").columns

    val vectorAssembler = new VectorAssembler().
      setInputCols(columnsToTransform).setOutputCol("features")
    columnsToTransform.foreach { x => println(x) }
    val trainDataTemp = vectorAssembler.transform(trainData).withColumnRenamed("cost", "label")
    val testDataTemp = vectorAssembler.transform(testData_x)
    val trainDataFin = trainDataTemp.select("features", "label")
    val testDataFin = testDataTemp.select("features")
    val linearRegression = new LinearRegression()

    val paramGridMap = new ParamGridBuilder()
      .addGrid(linearRegression.maxIter, Array(10, 100, 1000))
      .addGrid(linearRegression.regParam, Array(0.1, 0.01, 0.001, 1, 10)).build()
    //5 fold cross validation         
    val cv = new CrossValidator().setEstimator(linearRegression).
      setEvaluator(new RegressionEvaluator()).setEstimatorParamMaps(paramGridMap).setNumFolds(5)
    //Fit the model
    val model = cv.fit(trainDataFin)
    val modelResult = model.transform(testDataFin)
    model.bestModel.params.foreach { x => print(x.name, " ", x.toString()) }
    val predictionAndLabels = modelResult.map(r => r.getAs[Double]("prediction")).zip(testData_y.map(R => R.getAs[Double](0)))
    val regressionMetrics = new RegressionMetrics(predictionAndLabels)
    //Print the results
    println(s"R-Squared= ${regressionMetrics.r2}")
    println(s"Explained Variance=${regressionMetrics.explainedVariance}")
    println(s"MAE= ${regressionMetrics.meanAbsoluteError}")
    sc.parallelize(Seq(model), 1).saveAsObjectFile("uber.model")
    val lrModel = model.bestModel.asInstanceOf[LinearRegressionModel]
    println(lrModel.explainParams())
    println(lrModel.weights)
  }

  def predictFare(list: ListBuffer[UberParams]): DataFrame = {
    var uberModel: CrossValidatorModel = null;
    try {
      uberModel = sc.objectFile[CrossValidatorModel]("uber.model").first()
    } catch {
      case e: InvalidInputException => println()
    }
    if (uberModel == null) {
      buildUberPriceAnalysisModel("""select duration as duration , 
    displayname as name,
    surge_multiplier , distance, hour(currentdate) as hour, minute(currentdate) as minute, second(currentdate) as second, 
    (lowestimate+highestimate)/2 as cost  from uber  where  displayName like 'UberBLACK' or displayName like 'uberX' and lowestimate >0 and highestimate >0""")
    }
    uberModel = sc.objectFile[CrossValidatorModel]("uber.model").first()
    var schema = StructType(Array(
      StructField("duration", IntegerType, true),
      StructField("name", StringType, true),
      StructField("surge_multiplier", FloatType, true),
      StructField("distance", FloatType, true),
      StructField("hour", IntegerType, true),
      StructField("minute", IntegerType, true),
      StructField("second", IntegerType, true)))
    var rows: ListBuffer[Row] = new ListBuffer
    list.foreach(x => rows += Row(x.duration, x.name, x.surgeMultiplier, x.distance, x.hour, x.minute, x.hour))
    val row = sc.parallelize(rows)
    var dfStructure = sqlContext.createDataFrame(row, schema)
    var preprocessed = preprocessFeatures(dfStructure)
    preprocessed.describe().show()
    val vectorAssembler = new VectorAssembler().
      setInputCols(preprocessed.columns).setOutputCol("features")
    preprocessed = vectorAssembler.transform(preprocessed)
    var results = uberModel.transform(preprocessed)
    return results
  }

}