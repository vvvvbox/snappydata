/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.internal

import io.snappydata.Property

import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.expressions.{And, Attribute, AttributeReference, EqualTo, Expression}
import org.apache.spark.sql.catalyst.plans.logical.{BinaryNode, Join, LogicalPlan, OverwriteOptions, Project}
import org.apache.spark.sql.catalyst.plans.{Inner, LeftAnti}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{DataType, LongType, StructType}
import org.apache.spark.sql.{AnalysisException, Dataset, Row, SnappySession, SparkSession}

/**
 * Helper object for PutInto operations for column tables.
 * This class takes the logical plans from SnappyParser
 * and converts it into another plan.
 */
object ColumnTableBulkOps {

  def transformPutPlan(sparkSession: SparkSession, originalPlan: PutIntoTable): LogicalPlan = {
    validateOp(originalPlan)
    val table = originalPlan.table
    val subQuery = originalPlan.child
    var transFormedPlan: LogicalPlan = originalPlan

    table.collectFirst {
      case LogicalRelation(mutable: BulkPutRelation, _, _) =>
        val putKeys = mutable.getPutKeys
        if (putKeys.isEmpty) {
          throw new AnalysisException(
            s"PutInto in a column table requires key column(s) but got empty string")
        }
        val condition = prepareCondition(sparkSession, table, subQuery, putKeys.get)

        val keyColumns = getKeyColumns(table)
        var updateSubQuery: LogicalPlan = Join(table, subQuery, Inner, condition)
        val updateColumns = table.output.filterNot(a => keyColumns.contains(a.name))
        val updateExpressions = subQuery.output.filterNot(a => keyColumns.contains(a.name))
        if (updateExpressions.isEmpty) {
          throw new AnalysisException(
            s"PutInto is attempted without any column which can be updated." +
                s" Provide some columns apart from key column(s)")
        }

        val cacheSize = ExternalStoreUtils.sizeAsBytes(
          Property.PutIntoInnerJoinCacheSize.get(sparkSession.sqlContext.conf),
          Property.PutIntoInnerJoinCacheSize.name, -1, Long.MaxValue)

        val updatePlan = Update(table, updateSubQuery, Seq.empty,
          updateColumns, updateExpressions)
        val updateDS = new Dataset(sparkSession, updatePlan, RowEncoder(updatePlan.schema))
        val analyzedUpdate = updateDS.queryExecution.analyzed.asInstanceOf[Update]
        updateSubQuery = analyzedUpdate.child

        val (doInsertJoin, isCached) = if (subQuery.statistics.sizeInBytes <= cacheSize) {
          val joinDS = new Dataset(sparkSession,
            updateSubQuery, RowEncoder(updateSubQuery.schema))
          joinDS.cache()
          (joinDS.count() > 0, true)
        } else (true, false)

        // Adding to context after the count operation, as count will clear the context object.
        if (isCached) {
          sparkSession.asInstanceOf[SnappySession].
              addContextObject(SnappySession.CACHED_PUTINTO_UPDATE_PLAN, updateSubQuery)
        }

        val insertChild = if (doInsertJoin) {
          Join(subQuery, updateSubQuery, LeftAnti, condition)
        } else subQuery
        val insertPlan = new Insert(table, Map.empty[String,
            Option[String]], Project(subQuery.output, insertChild),
          OverwriteOptions(enabled = false), ifNotExists = false)

        transFormedPlan = PutIntoColumnTable(table, insertPlan, analyzedUpdate)
      case _ => // Do nothing, original putInto plan is enough
    }
    transFormedPlan
  }

  def validateOp(originalPlan: PutIntoTable) {
    originalPlan match {
      case PutIntoTable(LogicalRelation(t: BulkPutRelation, _, _), query) =>
        val srcRelations = query.collect {
          case LogicalRelation(src: BaseRelation, _, _) => src
        }
        if (srcRelations.contains(t)) {
          throw Utils.analysisException(
            "Cannot put into table that is also being read from.")
        } else {
          // OK
        }
      case _ => // OK
    }
  }

  private def prepareCondition(sparkSession: SparkSession,
      table: LogicalPlan,
      child: LogicalPlan,
      columnNames: Seq[String]): Option[Expression] = {
    val analyzer = sparkSession.sessionState.analyzer
    val leftKeys = columnNames.map { keyName =>
      table.output.find(attr => analyzer.resolver(attr.name, keyName)).getOrElse {
        throw new AnalysisException(s"key column `$keyName` cannot be resolved on the left " +
            s"side of the operation. The left-side columns: [${
              table.
                  output.map(_.name).mkString(", ")
            }]")
      }
    }
    val rightKeys = columnNames.map { keyName =>
      child.output.find(attr => analyzer.resolver(attr.name, keyName)).getOrElse {
        throw new AnalysisException(s"USING column `$keyName` cannot be resolved on the right " +
            s"side of the operation. The right-side columns: [${
              child.
                  output.map(_.name).mkString(", ")
            }]")
      }
    }
    val joinPairs = leftKeys.zip(rightKeys)
    val newCondition = joinPairs.map(EqualTo.tupled).reduceOption(And)
    newCondition
  }

  def getKeyColumns(table: LogicalPlan): Seq[String] = {
    table.collectFirst {
      case LogicalRelation(mutable: MutableRelation, _, _) => mutable.getKeyColumns
    }.getOrElse(throw new AnalysisException(
      s"Update/Delete requires a MutableRelation but got $table"))

  }

  def transformDeletePlan(sparkSession: SparkSession,
      originalPlan: DeleteFromTable): LogicalPlan = {
    val table = originalPlan.table
    val subQuery = originalPlan.child
    var transFormedPlan: LogicalPlan = originalPlan

    table.collectFirst {
      case LogicalRelation(mutable: BulkPutRelation, _, _) =>
        val putKeys = mutable.getPutKeys
        if (putKeys.isEmpty) {
          throw new AnalysisException(
            s"DeleteFrom in a column table requires key column(s) but got empty string")
        }
        val condition = prepareCondition(sparkSession, table, subQuery, putKeys.get)
        val exists = Join(subQuery, table, Inner, condition)
        val deletePlan = Delete(table, exists, Nil)
        val deleteDs = new Dataset(sparkSession, deletePlan, RowEncoder(deletePlan.schema))
        transFormedPlan = deleteDs.queryExecution.analyzed.asInstanceOf[Delete]
      case lr@LogicalRelation(mutable: MutableRelation, _, _) =>
        val ks = mutable.getKeyColumns
        if (ks.isEmpty) {
          throw new AnalysisException(
            s"DeleteFrom in a table requires key column(s) but got empty string")
        }
        val condition = prepareCondition(sparkSession, table, subQuery, ks)
        val exists = Join(subQuery, table, Inner, condition)
        val deletePlan = Delete(table, exists, Nil)
        val deleteDs = new Dataset(sparkSession, deletePlan, RowEncoder(deletePlan.schema))
        transFormedPlan = deleteDs.queryExecution.analyzed.asInstanceOf[Delete]
    }
    transFormedPlan
  }

  def bulkInsertOrPut(rows: Seq[Row], sparkSession: SparkSession,
      schema: StructType, resolvedName: String, putInto: Boolean): Int = {
    val session = sparkSession.asInstanceOf[SnappySession]
    val sessionState = session.sessionState
    val tableIdent = sessionState.sqlParser.parseTableIdentifier(resolvedName)
    val encoder = RowEncoder(schema)
    val ds = session.internalCreateDataFrame(session.sparkContext.parallelize(
      rows.map(encoder.toRow)), schema)
    val plan = if (putInto) {
      PutIntoTable(
        table = UnresolvedRelation(tableIdent),
        child = ds.logicalPlan)
    } else {
      new Insert(
        table = UnresolvedRelation(tableIdent),
        partition = Map.empty[String, Option[String]],
        child = ds.logicalPlan,
        overwrite = OverwriteOptions(enabled = false),
        ifNotExists = false)
    }
    session.sessionState.executePlan(plan).executedPlan.executeCollect()
        // always expect to create a TableInsertExec
        .foldLeft(0)(_ + _.getInt(0))
  }
}

case class PutIntoColumnTable(table: LogicalPlan,
    insert: Insert, update: Update) extends BinaryNode {

  override lazy val output: Seq[Attribute] = AttributeReference(
    "count", LongType)() :: Nil

  override lazy val resolved: Boolean = childrenResolved &&
      update.output.zip(insert.output).forall {
        case (updateAttr, insertAttr) =>
          DataType.equalsIgnoreCompatibleNullability(updateAttr.dataType,
            insertAttr.dataType)
      }

  override def left: LogicalPlan = update

  override def right: LogicalPlan = insert
}
