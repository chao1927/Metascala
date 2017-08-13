package metascala.natives


import metascala.imm.Sig
import metascala.rt.Obj
import metascala.util.{Agg, Ref}
import metascala.{imm, rt}

import scala.collection.mutable
import scala.reflect.ClassTag
object Bindings{
  trait Interface extends Obj.VMInterface{
    def invoke(cls: imm.Type.Cls, sig: Sig, args: Agg[Any]): Unit

    def returnedVal: Array[Int]
    def alloc[T](func: Obj.Registrar => T): T
    def typeObjCache: mutable.HashMap[imm.Type, Ref]
    def offHeap: Array[Byte]
    def setOffHeapPointer(n: Long): Unit
    def offHeapPointer: Long
    def arr(address: Int): rt.Arr
    def runningClassName(n: Int): String // vt.threadStack(n).runningClass.name
    def threadStackLength: Int // vt.threadStack.length
    def internedStrings: mutable.Map[String, Int]
    def toRealObj[T](x: Int)(implicit ct: ClassTag[T]): T
    def toVirtObj(x: Any)(implicit registrar: Obj.Registrar): rt.Obj
    def trace: Array[StackTraceElement]
    def currentThread: Int
    def invokeRun(a: Int): Int
    def newInstance(constr: Int, argArr: Int): Int
  }
}
trait Bindings{


  val fileLoader: String => Option[Array[Byte]]



  val trapped: Seq[rt.Method]
}