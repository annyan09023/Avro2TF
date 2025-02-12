package com.linkedin.avro2tf.jobs

import scala.collection.mutable

import com.databricks.spark.avro._
import com.linkedin.avro2tf.configs.TensorMetadata
import com.linkedin.avro2tf.constants.Constants
import com.linkedin.avro2tf.helpers.Avro2TFJobHelper
import com.linkedin.avro2tf.jobs.Avro2TF.SparseVector
import com.linkedin.avro2tf.parsers.{PrepRankingDataParams, PrepRankingDataParamsParser}
import com.linkedin.avro2tf.utils._
import io.circe.generic.auto._
import io.circe.parser.{decode => jsondecode}
import io.circe.syntax._
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{ArrayType, DoubleType, FloatType, IntegerType, LongType, StringType, StructType, DataType => ADataType}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Prepare ranking tensor data after Avro2TF job.
 * The output data should be ready be consumed by TensorflowIn and tf.ranking
 */
object PrepRankingData {
  val logger: Logger = LoggerFactory.getLogger(getClass.getName)

  /**
   * The main function to perform PrepRankingData job
   *
   * @param spark The spark session
   * @param params The prepare ranking data parameters specified by user
   */
  def run(spark: SparkSession, params: PrepRankingDataParams): Unit = {

    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    val metadata = readMetadata(fs, params.inputMetadataPath)

    require(metadata.contains(Constants.FEATURES), "Cannot find features section in metadata.")
    require(
      metadata.contains(Constants.LABELS) && metadata(Constants.LABELS).size == 1,
      "Cannot find labels section in metadata or length is not equal to 1.")

    require(fs.exists(new Path(params.inputDataPath)), s"Cannot find ${params.inputDataPath} on hdfs.")
    prepareRanking(spark, params.inputDataPath, params, metadata)
  }

  /**
   * Reading tensor_metadata.json from the Avro2TF job
   *
   * @param fs The file system
   * @param path The tensor_metadata.json file path string
   * @return The map of tensor metadata for labels and features
   */
  private def readMetadata(fs: FileSystem, path: String): Map[String, Seq[TensorMetadata]] = {
    import com.linkedin.avro2tf.configs.JsonCodecs._

    val metaDataPath = new Path(path)
    require(fs.exists(metaDataPath), "Cannot find the tensor_metadata.json file!")
    val metaDataJson = IOUtils.readContentFromHDFS(fs, metaDataPath)

    jsondecode[Map[String, Seq[TensorMetadata]]](metaDataJson) match {
      case Left(e) => throw e
      case Right(m) => m
    }
  }

  /**
   * Prepare ranking data based on the user configuration
   *
   * @param spark The spark session
   * @param inputPath The input data path
   * @param params The user input parameter
   * @param metadata The metadata from Avro2TF job
   */
  def prepareRanking(
    spark: SparkSession,
    inputPath: String,
    params: PrepRankingDataParams,
    metadata: Map[String, Seq[TensorMetadata]]): Unit = {

    // read data
    val df = IOUtils.readAvro(spark, inputPath).drop(params.dropColumns.getOrElse(Seq.empty): _*)

    // three types of columns: qid column, content features columns, query feature columns
    val groupIdCols = params.groupIdList.map(col)
    val (documentFeaturesMetadata, queryFeaturesMetadata) = (metadata(Constants.FEATURES) ++ metadata(Constants.LABELS))
      .partition(_.isDocumentFeature)
    val queryFeatures = queryFeaturesMetadata.map(_.name)
    logger.info(s"Query feature list: ${queryFeatures.mkString(", ")}.")

    val label = metadata(Constants.LABELS).head.name
    logger.info(s"Label field: $label.")

    val documentFeatures = documentFeaturesMetadata.map(_.name).filter(x => !params.groupIdList.contains(x))
    logger.info(s"Content feature list: ${documentFeatures.mkString(", ")}.")

    val selectedColumnNames = (documentFeatures ++ queryFeatures).distinct.map(col)
    val selectDf = df.select(groupIdCols ++ selectedColumnNames: _*)

    // group by qid
    // content features - collect_list
    // query features - first row
    val aggExpr = documentFeatures.map(x => collect_list(col(x)).alias(x)) ++
      queryFeatures.map(x => first(col(x)).alias(x))
    val groupDf = selectDf
      .groupBy(groupIdCols: _*)
      .agg(aggExpr.head, aggExpr.tail: _*)

    // truncate the list of each group to user specified size
    // TODO: padding of the features if each group has different length
    // For sparse tensor variable length is acceptable but for dense tensor padding is needed.
    var truncateDf = groupDf
    documentFeatures.foreach { it =>
      val isLabel = it.equals(label)
      truncateDf = truncateDf
        .withColumn(
          it,
          takeFirstN(
            selectDf.schema(it).dataType,
            params.groupListMaxSize,
            isLabel,
            params.labelPaddingValue,
            params.featurePaddingValue)(col(it)))
    }

    // flatten indices of sparse tensors
    val documentFeaturesWithSpVec = documentFeatures.filter(
      it =>
        CommonUtils.isArrayOfSparseVector(truncateDf.schema(it).dataType))

    documentFeaturesWithSpVec.foreach { it =>
      truncateDf = truncateDf.withColumn(it, flattenSparseVectorArray(col(it)))
    }

    // write to disk
    val repartitionDf = Avro2TFJobHelper
      .repartitionData(truncateDf, params.numOutputFiles, params.enableShuffle)
    repartitionDf.write.mode(SaveMode.Overwrite).avro(params.outputDataPath)

    // update tensor_metadata.json, need to happen after above avro data write
    if (params.executionMode == TrainingMode.training) {
      updateMetadata(spark, params, documentFeatures, metadata)
    }
  }

  /**
   * Update metadata shape information after grouping
   *
   * @param spark The spark session
   * @param params The job params
   * @param documentFeatures The list of content features
   * @param metadata The metadata
   */
  private def updateMetadata(
    spark: SparkSession,
    params: PrepRankingDataParams,
    documentFeatures: Seq[String],
    metadata: Map[String, Seq[TensorMetadata]]
  ): Unit = {
    import com.linkedin.avro2tf.configs.JsonCodecs._

    // update shape information in tensor_metadata
    val updateMetadata = metadata.mapValues { tensorMetaDatas =>
      tensorMetaDatas.filter(x => !params.groupIdList.contains(x.name)).map { x =>
        if (documentFeatures.contains(x.name)) {
          x.copy(shape = Array(params.groupListMaxSize) ++ x.shape)
        } else {
          x
        }
      }
    }
    val updateMetadataJson = updateMetadata.asJson.toString
    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    IOUtils
      .writeContentToHDFS(
        fs,
        new Path(params.outputMetadataPath, Constants.TENSOR_METADATA_FILE_NAME),
        updateMetadataJson,
        overwrite = true)

    // write content feature list
    IOUtils.writeContentToHDFS(
      fs,
      new Path(params.outputMetadataPath, Constants.CONTENT_FEATURE_LIST),
      documentFeatures.mkString("\n"),
      overwrite = true
    )
  }

  /**
   * udf to flatten the 2d sparse tensor to 1d.
   */
  private val flattenSparseVectorArray =
    udf(
      (arrayOfSp: Seq[Row]) => {

        val indices = mutable.ArrayBuffer[Long]()
        val values = mutable.ArrayBuffer[Float]()
        arrayOfSp.zipWithIndex.map { case (sp, index) =>
          indices ++= sp.getAs[Seq[Long]](Constants.INDICES).flatMap(x => Seq(index, x))
          values ++= sp.getAs[Seq[Float]](Constants.VALUES)
        }
        SparseVector(indices, values)
      })

  /**
   * From data schema to get correct udf
   *
   * @param dtype The input data schema
   * @param groupListMaxSize The number of records taken from array
   * @param isLabel Whether the column is a label field
   * @param labelPaddingValue The label padding value
   * @param featurePaddingValue The feature padding value
   * @return The udf does the taking first n items from array in a column
   */
  private def takeFirstN(
    dtype: ADataType,
    groupListMaxSize: Int,
    isLabel: Boolean = false,
    labelPaddingValue: Double = -1.0d,
    featurePaddingValue: Double = 0.0d) = {

    def takeFunc[T](paddingValue: T) = (array: Seq[T]) => {
      val filledArray = if (array.size >= groupListMaxSize) {
        array
      } else {
        array ++ Seq.fill(groupListMaxSize - array.size)(paddingValue)
      }
      filledArray.take(groupListMaxSize)
    }

    def takeArrayFunc[T] = (array: Seq[Seq[T]]) => array.take(groupListMaxSize)

    def takeSpFunc = (array: Seq[SparseVector]) => array.take(groupListMaxSize)

    val padValue = if (isLabel) {
      labelPaddingValue
    } else {
      featurePaddingValue
    }

    dtype match {
      case _: FloatType => udf(takeFunc[Float](padValue.toFloat))
      case _: DoubleType => udf(takeFunc[Double](padValue.toDouble))
      case _: IntegerType => udf(takeFunc[Int](padValue.toInt))
      case _: LongType => udf(takeFunc[Long](padValue.toLong))
      case _: StringType => udf(takeFunc[String](""))
      case _: StructType => udf(takeSpFunc)
      case ArrayType(FloatType, _) => udf(takeArrayFunc[Float])
      case ArrayType(DoubleType, _) => udf(takeArrayFunc[Double])
      case ArrayType(IntegerType, _) => udf(takeArrayFunc[Int])
      case ArrayType(LongType, _) => udf(takeArrayFunc[Long])
      case ArrayType(StringType, _) => udf(takeArrayFunc[String])
      case _ => throw new UnsupportedOperationException(s"Cannot support type: $dtype")
    }
  }

  /**
   * Main logic
   *
   * @param args
   */
  def main(args: Array[String]): Unit = {

    val params = PrepRankingDataParamsParser.parse(args)
    val ss = SparkSession.builder().appName(getClass.getName).getOrCreate()

    try {
      run(ss, params)
    } finally {
      ss.stop()
    }
  }
}
