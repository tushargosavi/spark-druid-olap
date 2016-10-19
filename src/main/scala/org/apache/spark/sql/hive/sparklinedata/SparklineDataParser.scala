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

package org.apache.spark.sql.hive.sparklinedata

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.{SQLContext, SparkSession}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.sparklinedata.commands.{ClearMetadata, ExplainDruidRewrite}
import org.apache.spark.sql.util.PlanUtil

class SPLParser(sparkSession: SparkSession,
                baseParser : ParserInterface,
                moduleParserExtensions : Seq[SparklineDataParser] = Nil,
                moduleParserTransforms : Seq[RuleExecutor[LogicalPlan]] = Nil
                          ) extends ParserInterface {
  val parsers = {
    if (moduleParserExtensions.isEmpty) {
      Seq(new SparklineDruidCommandsParser(sparkSession))
    } else {
      moduleParserExtensions
    }
  }

  override def parsePlan(sqlText: String): LogicalPlan = {
    val parsedPlan = parsers.foldRight(None:Option[LogicalPlan]) {
      case (p, None) => p.parse2(sqlText)
      case (_, Some(lP)) => Some(lP)
    }.getOrElse(baseParser.parsePlan(sqlText))

    moduleParserTransforms.foldRight(parsedPlan){
      case (rE, lP) => rE.execute(lP)
    }
  }

  def parseExpression(sqlText: String): Expression =
    baseParser.parseExpression(sqlText)

  def parseTableIdentifier(sqlText: String): TableIdentifier =
  baseParser.parseTableIdentifier(sqlText)
}

abstract class SparklineDataParser extends AbstractSparkSQLParser {

  def parse2(input: String): Option[LogicalPlan]
}

class SparklineDruidCommandsParser(sparkSession: SparkSession) extends SparklineDataParser {

  protected val CLEAR = Keyword("CLEAR")
  protected val DRUID = Keyword("DRUID")
  protected val CACHE = Keyword("CACHE")
  protected val DRUIDDATASOURCE = Keyword("DRUIDDATASOURCE")
  protected val ON = Keyword("ON")
  protected val EXECUTE = Keyword("EXECUTE")
  protected val QUERY = Keyword("QUERY")
  protected val USING = Keyword("USING")
  protected val HISTORICAL = Keyword("HISTORICAL")
  protected val EXPLAIN = Keyword("EXPLAIN")
  protected val REWRITE = Keyword("REWRITE")

  def parse2(input: String): Option[LogicalPlan] = synchronized {
    // Initialize the Keywords.
    initLexical
    phrase(start)(new lexical.Scanner(input)) match {
      case Success(plan, _) => Some(plan)
      case failureOrError => None
    }
  }

  protected override lazy val start: Parser[LogicalPlan] =
    clearDruidCache | execDruidQuery | explainDruidRewrite

  protected lazy val clearDruidCache: Parser[LogicalPlan] =
    CLEAR ~> DRUID ~> CACHE ~> opt(ident) ^^ {
      case id => ClearMetadata(id)
    }

  protected lazy val execDruidQuery : Parser[LogicalPlan] =
    (ON ~> DRUIDDATASOURCE ~> ident) ~ (USING ~> HISTORICAL).? ~
      (EXECUTE ~> opt(QUERY) ~> restInput) ^^ {
      case ds ~ hs ~ query => {
        PlanUtil.logicalPlan(ds, query, hs.isDefined)(sparkSession.sqlContext)
      }
    }

  protected lazy val explainDruidRewrite: Parser[LogicalPlan] =
    EXPLAIN ~> DRUID ~> REWRITE ~> restInput ^^ {
      case sql => ExplainDruidRewrite(sql)
    }

}