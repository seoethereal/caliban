package caliban.execution

import scala.collection.immutable.ListMap
import caliban.CalibanError.ExecutionError
import caliban.parsing.adt.ExecutableDefinition.{ FragmentDefinition, OperationDefinition }
import caliban.parsing.adt.OperationType.{ Mutation, Query, Subscription }
import caliban.parsing.adt.Selection.{ Field, FragmentSpread, InlineFragment }
import caliban.parsing.adt.{ Document, Selection }
import caliban.schema.ResolvedValue.{ ResolvedListValue, ResolvedObjectValue, ResolvedStreamValue }
import caliban.schema.ResponseValue.{ ListValue, NullValue, ObjectValue, StringValue }
import caliban.schema.RootSchema.Operation
import caliban.schema.{ ResolvedValue, ResponseValue, RootSchema }
import zio.{ IO, UIO }

object Executor {

  def executeRequest[Q, M, S](
    document: Document,
    schema: RootSchema[Q, M, S],
    operationName: Option[String]
  ): IO[ExecutionError, ResponseValue] = {
    val fragments = document.definitions.collect {
      case fragment: FragmentDefinition => fragment.name -> fragment
    }.toMap
    val operation = operationName match {
      case Some(name) =>
        document.definitions.collectFirst { case op: OperationDefinition if op.name.contains(name) => op }
          .toRight(s"Unknown operation $name.")
      case None =>
        document.definitions.collect { case op: OperationDefinition => op } match {
          case head :: Nil => Right(head)
          case _           => Left("Operation name is required.")
        }
    }
    IO.fromEither(operation).mapError(ExecutionError(_)).flatMap { op =>
      def executeOperation[A](x: Operation[A], parallel: Boolean): IO[ExecutionError, ResponseValue] =
        executeSelectionSet(x.schema.resolve(x.resolver, Map()), op.selectionSet, fragments, parallel)
      op.operationType match {
        case Query => executeOperation(schema.query, parallel = true)
        case Mutation =>
          schema.mutation match {
            case Some(m) => executeOperation(m, parallel = false)
            case None    => IO.fail(ExecutionError("Mutations are not supported on this schema"))
          }
        case Subscription =>
          schema.subscription match {
            case Some(m) => executeOperation(m, parallel = true)
            case None    => IO.fail(ExecutionError("Subscriptions are not supported on this schema"))
          }
      }
    }
  }

  private def executeSelectionSet(
    resolve: IO[ExecutionError, ResolvedValue],
    selectionSet: List[Selection],
    fragments: Map[String, FragmentDefinition],
    parallel: Boolean
  ): IO[ExecutionError, ResponseValue] =
    resolve.flatMap {
      case ResolvedObjectValue(objectName, fields) =>
        val mergedSelectionSet = mergeSelectionSet(selectionSet, objectName, fragments)
        val resolveFields = mergedSelectionSet.map {
          case Selection.Field(alias, name @ "__typename", _, _, _) =>
            UIO(alias.getOrElse(name) -> StringValue(objectName))
          case Selection.Field(alias, name, args, _, selectionSet) =>
            fields
              .get(name)
              .map(res => executeSelectionSet(res(args), selectionSet, fragments, parallel))
              .getOrElse(UIO.succeed(NullValue))
              .map((alias.getOrElse(name), _))
        }
        (if (parallel) IO.collectAllPar(resolveFields) else IO.collectAll(resolveFields)).map(ObjectValue)
      case ResolvedListValue(values) =>
        IO.collectAllPar(values.map(executeSelectionSet(_, selectionSet, fragments, parallel))).map(ListValue)
      case ResolvedStreamValue(stream) =>
        UIO(
          ResponseValue
            .StreamValue(stream.mapM(res => executeSelectionSet(UIO(res), selectionSet, fragments, parallel)))
        )
      case other: ResponseValue => UIO(other)
    }

  private def mergeSelectionSet(
    selectionSet: List[Selection],
    name: String,
    fragments: Map[String, FragmentDefinition]
  ): List[Field] = {
    val fields = selectionSet.flatMap {
      case f: Field => List(f)
      case InlineFragment(typeCondition, _, sel) =>
        val matching = typeCondition.fold(true)(_.name == name)
        if (matching) mergeSelectionSet(sel, name, fragments) else Nil
      case FragmentSpread(spreadName, _) =>
        fragments.get(spreadName) match {
          case Some(fragment) if fragment.typeCondition.name == name =>
            mergeSelectionSet(fragment.selectionSet, name, fragments)
          case _ => Nil
        }
    }
    fields
      .foldLeft(ListMap.empty[String, Field]) {
        case (result, field) =>
          result.updated(
            field.name,
            result
              .get(field.name)
              .fold(field)(f => f.copy(selectionSet = f.selectionSet ++ field.selectionSet))
          )
      }
      .values
      .toList
  }

}