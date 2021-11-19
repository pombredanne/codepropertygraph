package io.shiftleft.semanticcpg.langv2

import overflowdb.traversal.RepeatStepIterator


class AnyTraversal[I, TM <: TypeMultiplexer](val trav: TM#IT[I]) extends AnyVal {

  def cast[A]: TM#CCOneToOne[A] = {
    trav.asInstanceOf[TM#CCOneToOne[A]]
  }

  def map[O](f: I => O)(implicit ops: TravOps[TM]): TM#CCOneToOne[O] = {
    ops.oneToOne(trav)(f)
  }

  def filter(f: I => Boolean)(implicit ops: TravOps[TM]): TM#CCOneToBoolean[I] = {
    ops.oneToBoolean(trav)(f)
  }

  def flatMap[O](f: I => Iterator[O])(implicit ops: TravOps[TM]): TM#CCOneToMany[O] = {
    ops.oneToMany(trav)(f)
  }

  // We dont use ClassTag and instead use our own IsInstanceOfOps for performance reasons.
  def collectAll[T](implicit ops: TravOps[TM], isInstanceOfOps: IsInstanceOfOps[T]): TM#CCOneToBoolean[T] = {
    ops.oneToBoolean(trav)(isInstanceOfOps).asInstanceOf[TM#CCOneToBoolean[T]]
  }

  def rMap[O <: I](f: I => O, g: RepeatBehaviourBuilder[I] => RepeatBehaviourBuilder[I] = identity)(
      implicit ops: TravOps[TM]): TM#CCOneToMany[I] = {
    val behaviour = g(new RepeatBehaviourBuilder()).build
    ops.oneToMany(trav)(i => new RepeatStepIterator(i, f.andThen(Iterator.single), behaviour))
  }

  def rFlatMap[O <: I](f: I => Iterator[O], g: RepeatBehaviourBuilder[I] => RepeatBehaviourBuilder[I] = identity)(
      implicit ops: TravOps[TM]): TM#CCOneToMany[I] = {
    val behaviour = g(new RepeatBehaviourBuilder()).build
    ops.oneToMany(trav)(i => new RepeatStepIterator(i, f, behaviour))
  }

  @deprecated("Use rFlatMap instead")
  def repeat[O <: I](f: I => Iterator[O], g: RepeatBehaviourBuilder[I] => RepeatBehaviourBuilder[I] = identity)(
      implicit ops: TravOps[TM]): TM#CCOneToMany[I] = {
    rFlatMap(f, g)
  }

  @deprecated("Calls to this function can be omitted")
  def l: TM#IT[I] = {
    trav
  }

}