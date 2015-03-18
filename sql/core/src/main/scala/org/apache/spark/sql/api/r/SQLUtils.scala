/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.api.r

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

import org.apache.spark.api.java.{JavaRDD, JavaSparkContext}
import org.apache.spark.api.r.SerDe
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.{Alias, Expression, NamedExpression}
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.sql.{Column, DataFrame, GroupedData, Row, SQLContext, SaveMode}

private[r] object SQLUtils {
  def createSQLContext(jsc: JavaSparkContext): SQLContext = {
    new SQLContext(jsc)
  }

  def getJavaSparkContext(sqlCtx: SQLContext): JavaSparkContext = {
    new JavaSparkContext(sqlCtx.sparkContext)
  }

  def toSeq[T](arr: Array[T]): Seq[T] = {
    arr.toSeq
  }

  def createDF(rdd: RDD[Array[Byte]], schemaString: String, sqlContext: SQLContext): DataFrame = {
    val schema = DataType.fromJson(schemaString).asInstanceOf[StructType]
    val num = schema.fields.size
    val rowRDD = rdd.map(bytesToRow)
    sqlContext.createDataFrame(rowRDD, schema)
  }

  // A helper to include grouping columns in Agg()
  def aggWithGrouping(gd: GroupedData, exprs: Column*): DataFrame = {
    val aggExprs = exprs.map { col =>
      col.expr match {
        case expr: NamedExpression => expr
        case expr: Expression => Alias(expr, expr.simpleString)()
      }
    }
    // TODO(davies): use internal API
    val toDF = gd.getClass.getDeclaredMethods.filter(f => f.getName == "toDF").head
    toDF.setAccessible(true)
    toDF.invoke(gd, aggExprs).asInstanceOf[DataFrame]
  }

  def dfToRowRDD(df: DataFrame): JavaRDD[Array[Byte]] = {
    df.map(r => rowToRBytes(r))
  }

  private[this] def bytesToRow(bytes: Array[Byte]): Row = {
    val bis = new ByteArrayInputStream(bytes)
    val dis = new DataInputStream(bis)
    val num = SerDe.readInt(dis)
    Row.fromSeq((0 until num).map { i =>
      SerDe.readObject(dis)
    }.toSeq)
  }

  private[this] def rowToRBytes(row: Row): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val dos = new DataOutputStream(bos)

    SerDe.writeInt(dos, row.length)
    (0 until row.length).map { idx =>
      val obj: Object = row(idx).asInstanceOf[Object]
      SerDe.writeObject(dos, obj)
    }
    bos.toByteArray()
  }

  def dfToCols(df: DataFrame): Array[Array[Byte]] = {
    // localDF is Array[Row]
    val localDF = df.collect()
    val numCols = df.columns.length
    // dfCols is Array[Array[Any]]
    val dfCols = convertRowsToColumns(localDF, numCols)

    dfCols.map { col =>
      colToRBytes(col)
    } 
  }

  def convertRowsToColumns(localDF: Array[Row], numCols: Int): Array[Array[Any]] = {
    (0 until numCols).map { colIdx =>
      localDF.map { row =>
        row(colIdx)
      }
    }.toArray
  }

  def colToRBytes(col: Array[Any]): Array[Byte] = {
    val numRows = col.length
    val bos = new ByteArrayOutputStream()
    val dos = new DataOutputStream(bos)
    
    SerDe.writeInt(dos, numRows)

    col.map { item =>
      val obj: Object = item.asInstanceOf[Object]
      SerDe.writeObject(dos, obj)
    }
    bos.toByteArray()
  }

  def saveMode(mode: String): SaveMode = {
    mode match {
      case "append" => SaveMode.Append
      case "overwrite" => SaveMode.Overwrite
      case "error" => SaveMode.ErrorIfExists
      case "ignore" => SaveMode.Ignore
    }
  }
}
