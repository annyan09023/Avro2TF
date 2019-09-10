package com.linkedin.avro2tf.jobs

import com.linkedin.avro2tf.helpers.{HashingTransformer, TextTokenizationTransformer}
import com.linkedin.avro2tf.parsers.Avro2TFParams
import org.apache.spark.sql.DataFrame

/**
 * The Feature Transformation job transforms features that will be converted to tensors and later used in training.
 *
 */
object FeatureTransformation {

  /**
   * The main function to perform Feature Transformation job
   *
   * @param dataFrame Input data Spark DataFrame
   * @param params Avro2TF parameters specified by user
   * @return A Spark DataFrame
   */
  def run(dataFrame: DataFrame, params: Avro2TFParams): DataFrame = {

    // Text feature tokenization
    val tokenizedDataFrame = TextTokenizationTransformer.tokenizeTextFeature(dataFrame, params)

    // Hashing Transformation
    HashingTransformer.hashTransform(tokenizedDataFrame, params)
  }
}