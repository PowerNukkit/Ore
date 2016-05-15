package db.action

import db.action.ModelFilter.{IdFilter, unwrapFilter}
import db.impl.OrePostgresDriver.api._
import db.{Model, ModelService, ModelTable}
import slick.lifted.ColumnOrdered

/**
  * Provides simple, synchronous, access to a ModelTable.
  */
class ModelAccess[T <: ModelTable[M], M <: Model](service: ModelService,
                                                  modelClass: Class[M],
                                                  baseFilter: ModelFilter[T, M] = ModelFilter[T, M]()) {

  val baseQuery: ModelActions[T, M] = service.registrar.getActionsByModel[T, M](modelClass)

  /**
    * Returns the model with the specified ID.
    *
    * @param id   ID to lookup
    * @return     Model with ID or None if not found
    */
  def get(id: Int): Option[M] = service.await(baseQuery.get(id, this.baseFilter.fn)).get

  /**
    * Returns all the [[Model]]s in the set.
    *
    * @return All models in set
    */
  def all: Set[M] = service.await(baseQuery.filter(this.baseFilter)).get.toSet

  /**
    * Returns the size of this set.
    *
    * @return Size of set
    */
  def size: Int = service.await(baseQuery count this.baseFilter).get

  /**
    * Returns true if this set is empty.
    *
    * @return True if set is empty
    */
  def isEmpty: Boolean = this.size == 0

  /**
    * Returns true if this set is not empty.
    *
    * @return True if not empty
    */
  def nonEmpty: Boolean = this.size > 0

  /**
    * Returns true if this set contains the specified model.
    *
    * @param model Model to look for
    * @return True if contained in set
    */
  def contains(model: M): Boolean = service.await(baseQuery count (this.baseFilter +&& IdFilter(model.id.get))).get > 0

  /**
    * Returns true if any models match the specified filter.
    *
    * @param filter Filter to use
    * @return       True if any model matches
    */
  def exists(filter: T => Rep[Boolean]) = service.await(baseQuery count (this.baseFilter && filter)).get > 0

  /**
    * Adds a new model to it's table.
    *
    * @param model Model to add
    * @return New model
    */
  def add(model: M): M = service.await(baseQuery insert model).get

  /**
    * Removes the specified model from this set if it is contained.
    *
    * @param model Model to remove
    */
  def remove(model: M) = service.await(baseQuery delete model).get

  /**
    * Removes all the models from this set matching the given filter.
    *
    * @param filter Filter to use
    */
  def removeAll(filter: T => Rep[Boolean] = _ => true)
  = service.await(baseQuery deleteWhere (this.baseFilter && filter))

  /**
    * Returns the first model matching the specified filter.
    *
    * @param filter Filter to use
    * @return       Model matching filter, if any
    */
  def find(filter: T => Rep[Boolean]): Option[M] = service.await(baseQuery.find(this.baseFilter && filter)).get

  /**
    * Returns a sorted Seq by the specified [[ColumnOrdered]].
    *
    * @param ordering Model ordering
    * @param filter   Filter to use
    * @param limit    Amount to take
    * @param offset   Amount to drop
    * @return         Sorted models
    */
  def sorted(ordering: T => ColumnOrdered[_], filter: T => Rep[Boolean] = null,
             limit: Int = -1, offset: Int = -1): Seq[M]
  = service.await(baseQuery.collect(this.baseFilter && filter, ordering, limit, offset)).get

  /**
    * Filters this set by the given function.
    *
    * @param filter Filter to use
    * @param limit  Amount to take
    * @param offset Amount to drop
    * @return       Filtered models
    */
  def filter(filter: T => Rep[Boolean], limit: Int = -1, offset: Int = -1): Seq[M]
  = service.await(baseQuery.filter(filter, limit, offset)).get

  /**
    * Filters this set by the opposite of the given function.
    *
    * @param filter Filter to use
    * @param limit  Amount to take
    * @param offset Amount to drop
    * @return       Filtered models
    */
  def filterNot(filter: T => Rep[Boolean], limit: Int = -1, offset: Int = -1): Seq[M]
  = this.filter(!filter(_), limit, offset)

  /**
    * Returns a Seq of this set.
    *
    * @return Seq of set
    */
  def toSeq: Seq[M] = this.all.toSeq

}
