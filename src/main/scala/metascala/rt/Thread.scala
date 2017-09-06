package metascala
package rt

import scala.collection.mutable
import annotation.tailrec
import metascala.opcodes._
import Insn._
import Insn.Push
import Insn.InvokeStatic
import Insn.ReturnVal
import metascala.imm.{Desc, Sig, Type}
import metascala.natives.Bindings
import metascala.util._
import org.objectweb.asm.Opcodes

import scala.reflect.ClassTag

object Thread{
  trait VMInterface extends opcodes.SingleInsnSSAConverter.VMInterface{
    def insnLimit: Long
    def checkInitialized(cls: rt.Cls): Unit
    def threads: Seq[Thread]
    def offHeap: Array[Byte]
    def setOffHeapPointer(n: Long): Unit
    def offHeapPointer: Long
    def currentThread: Int
    def internedStrings: mutable.Map[String, WritableRef]
    def natives: Bindings
    def check(s: imm.Type, t: imm.Type): Boolean
    def indyCallSiteMap: mutable.Map[(rt.Method, Int, Int), WritableRef]
    def methodHandleMap: mutable.Map[WritableRef, rt.Method]
    def getTypeForTypeObj(addr: Int): imm.Type
  }
}

/**
 * A single thread within the Metascala VM.
 */
class Thread(val threadStack: mutable.ArrayStack[Frame] = mutable.ArrayStack())
            (implicit val vm: Thread.VMInterface) {
  thread =>

  private[this] var opCount = 0L

  def getOpCount = opCount

  def frame = threadStack.top

  val returnedVal = Array(0, 0)
  private[this] var insnCount = 0L

  def count = insnCount

  def indent = threadStack.length


  /**
    * Use a persistent scratch buffer to perform the local variable
    * re-arranging, to avoid having to re-allocate a new scratch buffer
    * each time.
    */
  var phiBuffer = new Array[Int](16)

  def doPhi(frame: Frame, oldBlock: Int, newBlock: Int) = {
    val phi = frame.method.code.blocks(newBlock).phi(oldBlock)
    if (phiBuffer.length < frame.locals.length) {
      phiBuffer = new Array[Int](frame.locals.length)
    }

    System.arraycopy(frame.locals, 0, phiBuffer, 0, frame.locals.length)

    if (vm.logger.active) vm.logger.logPhi(
      indent,
      vm.clsTable.clsIndex(frame.method.clsIndex).tpe.javaName,
      frame,
      phi
    )

    var i = 0
    var target = phi.length
    while (i < target) {
      val src = phi(i)
      if (src != -1) frame.locals(i) = phiBuffer(src)
      else frame.locals(i) = 0
      i += 1
    }

    target = frame.locals.length
    while (i < target) {
      frame.locals(i) = 0
      i += 1
    }

  }

  def jumpPhis(target: Int) = {
    doPhi(frame, frame.pc._1, target)
    frame.pc = (target, 0)
  }


  def advancePc(): Unit = {
    val pc = frame.pc
    val blocks = frame.method.code.blocks
    if (pc._2 + 1 < blocks(frame.pc._1).insns.length) {
      frame.pc = (pc._1, pc._2 + 1)
    } else if (pc._1 + 1 < blocks.length) {
      doPhi(frame, pc._1, pc._1 + 1)
      frame.pc = (pc._1 + 1, 0)
    }
  }

  def getActiveBlock() = {
    val code = frame.method.code
    var block = code.blocks(frame.pc._1)
    while (block.insns.length == 0) {
      jumpPhis(frame.pc._1 + 1)
      block = code.blocks(frame.pc._1)
    }
    block
  }

  def printType(x: Int) = {
    if (x == 0) "null"
    else if (vm.isObj(x)) Util.shortedJava(vm.obj(x).cls.tpe.javaName)
    else if (vm.isArr(x)) Util.shortedJava(vm.arr(x).tpe.javaName)
    else ???
  }
  final def step(): Unit = try {
    //  println(frame.pc)

    insnCount += 1
    val block = getActiveBlock()

    val node = block.insns(frame.pc._2)

    if (vm.logger.active) vm.logger.logStep(
      indent,
      vm.clsTable.clsIndex(frame.method.clsIndex).tpe.javaName,
      frame,
      node,
      block,
      printType
    )

    node match {
      case Push(target, prim, value) =>
        prim.write(value, Util.writer(frame.locals, target))
        advancePc()

      case PutStatic(src, clsIndex, index, prim) =>
        getPutStatic(src, clsIndex, index, prim, get = false)

      case GetStatic(src, clsIndex, index, prim) =>
        getPutStatic(src, clsIndex, index, prim, get = true)

      case PutField(src, obj, index, prim) =>
        getPutField(src, obj, index, prim, get = false)

      case GetField(src, obj, index, prim) =>
        getPutField(src, obj, index, prim, get = true)

      case BinaryBranch(symA, symB, target, func) =>
        if (func(frame.locals(symB), frame.locals(symA))) jumpPhis(target)
        else advancePc()

      case UnaryBranch(sym, target, func) =>
        if (func(frame.locals(sym))) jumpPhis(target)
        else advancePc()

      case Goto(target) => jumpPhis(target)

      case PutArray(src, index, array, prim) =>
        getPutArray(src, index, array, prim, get = false)

      case GetArray(dest, index, array, prim) =>
        getPutArray(dest, index, array, prim, get = true)

      case UnaryOp(src, psrc, dest, pout, func) =>
        unaryOp(src, psrc, dest, pout, func)

      case BinOp(a, pa, b, pb, dest, pout, func) =>

        binOp(a, pa, b, pb, dest, pout, func)

      case Ldc(target, index) =>
        ldc(target, index)

      case InvokeStatic(target, sources, clsIndex, mIndex, special) =>
        invokeStatic(target, sources, clsIndex, mIndex, special)

      case InvokeVirtual(target, sources, clsIndex, mIndex) =>
        invokeVirtual(target, sources, clsIndex, mIndex)

      case InvokeInterface(target, sources, sig) =>
        invokeInterface(target, sources, sig)

      case InvokeHandle(target, sources, sig, basic) =>
        invokeHandle(target, sources, sig, basic)

      case InvokeLink(target, sources, flag) =>
        invokeLink(target, sources, flag)


      case InvokeDynamic(target, srcs, name, desc0, bsTag, bsOwner, bsName, bsDesc0, bsArgs) =>
        invokeDynamic(target, srcs, name, desc0, bsTag, bsOwner, bsName, bsDesc0, bsArgs)
      case New(target, clsIndex) =>
        newInstance(target, clsIndex)

      case ArrayLength(src, dest) =>
        arrayLength(src, dest)

      case NewArray(src, dest, typeRef) =>
        newArray(src, dest, typeRef)

      case TableSwitch(src, min, max, default, targets) =>
        tableSwitch(src, min, max, default, targets)

      case LookupSwitch(src, default, keys, targets) =>
        lookupSwitch(src, default, keys, targets)

      case CheckCast(src, dest, desc) =>
        checkCast(src, dest, desc)

      case InstanceOf(src, dest, desc) =>
        instanceOf(src, dest, desc)

      case MultiANewArray(desc, symbol, dims) =>
        multiANewArray(desc, symbol, dims)

      case AThrow(src) => throwException(vm.obj(frame.locals(src)))

      case ReturnVal(sym) => returnVal(frame.method.sig.desc.ret.size, sym)
    }

    opCount += 1
    if (opCount > vm.insnLimit) {
      throw new Exception("Ran out of instructions! Limit: " + vm.insnLimit)
    }

  } catch {
    case e: Throwable if !e.isInstanceOf[WrappedVmException] =>
      val newEx = new InternalVmException(e)
      newEx.setStackTrace(trace)
      throw newEx
  }

  def lookupMethodHandle(methodHandleLookup: rt.Obj,
                         tag: Int,
                         ownerName: String,
                         methodName: String,
                         desc: imm.Desc)(implicit r: Allocator) = {
    tag match {
      case Opcodes.H_INVOKESTATIC =>

        val mRef = vm.resolveDirectRef(
          "java.lang.invoke.MethodHandles$Lookup",
          imm.Sig("findStatic",
            imm.Desc(
              Agg(
                "java.lang.Class",
                "java.lang.String",
                "java.lang.invoke.MethodType"
              ),
              "java.lang.invoke.MethodHandle"
            )
          )
        ).get

        val args = new ArrayFiller(mRef.localsSize)
        args.append(methodHandleLookup.address())
        args.append(vm.typeObjCache(ownerName)())
        args.append(Virtualizer.toVirtObj(methodName))
        args.append(getMethodType(desc))

        invoke0(mRef, args.arr)

        returnedVal(0)
    }
  }

  def getMethodType(d: imm.Desc)(implicit r: Allocator) = {
    val mRef = vm.resolveDirectRef(
      "java.lang.invoke.MethodType",
      imm.Sig.read(
        "methodType(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;"
      )
    ).get

    val args = new ArrayFiller(mRef.localsSize)
    args.append(vm.typeObjCache(d.ret).apply())
    args.append(r.newArr("java.lang.Class", d.args.map(vm.typeObjCache)).address())
    invoke0(mRef, args.arr)

    returnedVal(0)
  }

  private def invokeDynamic(target: Int,
                            srcs: Agg[Int],
                            name: String,
                            desc0: String,
                            bsTag: Int,
                            bsOwner: String,
                            bsName: String,
                            bsDesc0: String,
                            bsArgs: Seq[AnyRef]) = vm.alloc { implicit r =>

    val indyCallsite = vm.indyCallSiteMap.get((frame.method, frame.pc._1, frame.pc._2)) match {
      case Some(x) => x
      case None =>
        val methodHandleLookup =
          r.newObj("java.lang.invoke.MethodHandles$Lookup",
            "lookupClass" -> vm.typeObjCache(frame.runningClass.tpe),
            "allowedModes" -> Ref.Raw(-1 /* MethodHandles$Lookup.TRUSTED */)
          )


        val desc = imm.Desc.read(desc0)


        val methodType = getMethodType(desc)

        val bsDesc = imm.Desc.read(bsDesc0)

        val bsMethodHandle = lookupMethodHandle(methodHandleLookup, bsTag, bsOwner, bsName, bsDesc)
        //        val mRef = vm.resolveDirectRef(bsOwner, imm.Sig(bsName, bsDesc)).get
        val staticArguments = mutable.Buffer.empty[Int]
        staticArguments.append(methodHandleLookup.address())
        staticArguments.append(Virtualizer.toVirtObj(name))
        staticArguments.append(methodType)

        bsArgs.foreach {
          case x: org.objectweb.asm.Type =>
            staticArguments.append(getMethodType(imm.Desc.read(x.getDescriptor)))
          case x: org.objectweb.asm.Handle =>
            staticArguments.append(lookupMethodHandle(methodHandleLookup, x.getTag, x.getOwner, x.getName, imm.Desc.read(x.getDesc)))

          // All these virtualize to the boxed versions of their values, because
          // they end up getting put into a array of java.lang.Objects
          case x: java.lang.Byte => Virtualizer.toVirtObj(x: AnyRef)
          case x: java.lang.Character => Virtualizer.toVirtObj(x: AnyRef)
          case x: java.lang.Short => Virtualizer.toVirtObj(x: AnyRef)
          case x: java.lang.Integer => Virtualizer.toVirtObj(x: AnyRef)
          case x: java.lang.Float => Virtualizer.toVirtObj(x: AnyRef)
          case x: java.lang.Long => Virtualizer.toVirtObj(x: AnyRef)
          case x: java.lang.Double => Virtualizer.toVirtObj(x: AnyRef)

        }

        //        pprint.log((mRef, staticArguments.arr))
        //        invoke0(mRef, staticArguments.arr)
        //        val ref = new Ref.UnsafeManual(returnedVal(0))
        //        vm.indyCallSiteMap((frame.method, frame.pc._1, frame.pc._2)) = ref
        //        ref


        // In fact, the JVM does not directly invoke the bootstrap method.
        // Instead, the JVM calls a HotSpot-specific method
        // `MethodHandleNatives.linkCallSite` with the resolved bootstrap
        // specifier information. (Other JVM implementations do not necessarily
        // use this handshake.)
        //
        // https://wiki.openjdk.java.net/display/HotSpot/Method+handles+and+invokedynamic


        val appendixResult = r.newArr("java.lang.Object", 1)
        val mRef = vm.resolveDirectRef("java.lang.invoke.MethodHandleNatives",
          imm.Sig.read("linkCallSite(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/invoke/MemberName;")
        ).get
        val linkCallSiteArgs = new ArrayFiller(mRef.localsSize)
        val staticArgumentsArr = r.newArr("java.lang.Object", staticArguments.map(Ref.Raw))
        linkCallSiteArgs.append(vm.typeObjCache(frame.runningClass.tpe)() /*callerObj*/)
        linkCallSiteArgs.append(bsMethodHandle /*bootstrapMethodObj*/)
        linkCallSiteArgs.append(Virtualizer.toVirtObj(name) /*nameObj*/)
        linkCallSiteArgs.append(methodType /*typeObj*/)
        linkCallSiteArgs.append(staticArgumentsArr.address() /*staticArguments*/)
        linkCallSiteArgs.append(appendixResult.address() /*appendixResult*/)
        println("invoking in...")
        invoke0(mRef, linkCallSiteArgs.arr)
        //        pprint.log(vm.obj(returnedVal(0)).cls)
        ???
      /*
      static MemberName linkCallSite(Object callerObj,
                                     Object bootstrapMethodObj,
                                     Object nameObj, Object typeObj,
                                     Object staticArguments,
                                     Object[] appendixResult)
       */

    }
    //    val methodHandleAddress = vm.obj(indyCallsite.apply()).apply("target")
    //    pprint.log(methodHandleAddress)
    //    pprint.log(vm.obj(methodHandleAddress).cls.tpe.javaName)
    //    pprint.log(vm.obj(methodHandleAddress).cls.methods.map(_.toString), height=9999)
    ???
  }


  private def unaryOp(src: Int, psrc: Type.Prim[Any], dest: Int, pout: Type.Prim[Any], func: Any => Any) = {
    pout.write(func(psrc.read(Util.reader(frame.locals, src))), Util.writer(frame.locals, dest))
    advancePc()
  }

  private def binOp(a: Int, pa: Type.Prim[Any], b: Int, pb: Type.Prim[Any], dest: Int, pout: Type.Prim[Any], func: (Any, Any) => Any) = {
    val va = pa.read(Util.reader(frame.locals, a))
    val vb = pb.read(Util.reader(frame.locals, b))
    val out = func(va, vb)

    pout.write(out, Util.writer(frame.locals, dest))
    advancePc()
  }

  private def ldc(target: Int, index: Int) = {
    frame.locals(target) = vm.interned(index)()
    advancePc()
  }

  private def invokeStatic(target: Int, sources: Agg[Int], clsIndex: Int, mIndex: Int, special: Boolean) = {
    val cls = vm.clsTable.clsIndex(clsIndex)
    vm.checkInitialized(cls)
    invokeBase(
      sources,
      target,
      if (special) cls.vTable(mIndex)
      else cls.staticTable(mIndex),
      thisCell = special
    )
  }

  def boxIt(tpe: imm.Type.Prim[_], reader: () => Int)(implicit r: Allocator) = {
    import imm.Type.Prim._
    tpe match{
      case Z => r.newObj("java.lang.Boolean", "value" -> new Ref.Raw(if (Z.read(reader)) 1 else 0)).address
      case B => r.newObj("java.lang.Byte", "value" -> new Ref.Raw(reader())).address
      case S => r.newObj("java.lang.Short", "value" -> new Ref.Raw(reader())).address
      case C => r.newObj("java.lang.Character", "value" -> new Ref.Raw(reader())).address
      case I => r.newObj("java.lang.Integer", "value" -> new Ref.Raw(reader())).address
      case F => r.newObj("java.lang.Float", "value" -> new Ref.Raw(reader())).address
      case J =>
        val obj = r.newObj("java.lang.Long").address
        vm.obj(obj()).members(0) = reader()
        vm.obj(obj()).members(1) = reader()
        obj
      case D =>
        val obj = r.newObj("java.lang.Double").address
        vm.obj(obj()).members(0) = reader()
        vm.obj(obj()).members(1) = reader()
        obj
    }
  }
  def adaptBoxiness(targetType: Type,
                    target: mutable.Seq[Int],
                    targetOffset: Int,
                    sourceType: Type,
                    source: mutable.Seq[Int],
                    sourceOffset: Int) = {
    val value = source(sourceOffset)
    (targetType, sourceType) match{
      case (primField: imm.Type.Prim[_], boxedArg: imm.Type.Ref) =>
        target(targetOffset) = vm.obj(value).members(0)

        if (primField.size == 2){
          target(targetOffset + 1) = vm.obj(value).members(1)
        }
      case (boxedField: imm.Type.Ref, primArg: imm.Type.Prim[_]) =>
        target(targetOffset) = vm.alloc { implicit r =>
          boxIt(primArg, {
            var i = sourceOffset
            () => {
              val res = source(i)
              i += 1
              res
            }
          }).apply()
        }
      case (boxedField: imm.Type.Ref, boxedArg: imm.Type.Ref) =>
        target(targetOffset) = value
      case (primField: imm.Type.Prim[_], primArg: imm.Type.Prim[_]) =>
        Util.blit(
          source,
          sourceOffset,
          target,
          targetOffset,
          primField.size,
          false
        )

    }
  }
  private def invokeLink(target: Int, sources: Agg[Int], flag: String) = {
    println("Running invokeLink")
    flag match{
      case "linkToVirtual" => ???
      case "linkToStatic" =>
        val memberName = frame.locals(sources.last)
        val owner = vm.getTypeForTypeObj(vm.obj(memberName).apply("clazz"))

        val name = Virtualizer.toRealObj[String](vm.obj(memberName).apply("name"))(bindingsInterface, implicitly)
        val desc = {
          val tpeAddr = vm.obj(memberName).apply("type")
          val rtype = vm.getTypeForTypeObj(vm.obj(tpeAddr).apply("rtype"))


          val ptypes =
            for(ptype <- vm.arr(vm.obj(tpeAddr).apply("ptypes")))
              yield vm.getTypeForTypeObj(ptype)

          imm.Desc(Agg.from(ptypes), rtype)
        }
        val method = vm.resolveDirectRef(owner.asInstanceOf[imm.Type.Cls], imm.Sig(name, desc)).get
        invokeBase(sources.take(sources.length-1), target, method, false)
      case "linkToSpecial" => ???
      case "linkToInterface" => ???
    }
  }
  private def invokeHandle(target: Int, sources: Agg[Int], sig: Sig, basic: Boolean) = {
    println("Running invokeHandle")
    val argZero = frame.locals(sources(0))
    if (argZero == 0)throwExWithTrace("java.lang.NullPointerException", "null")
    else{

      // https://wiki.openjdk.java.net/display/HotSpot/Method+handles+and+invokedynamic
      //
      // """
      // Thus, in the steady state, a hot method handle is executed without
      // the lambda form interpreter. The low-level JVM steps are as follows:
      // - Fetch MethodHandle.form.
      // - Fetch LambdaForm.vmentry.
      // - Fetch MemberName.vmtarget, a hidden Method* pointer.
      // - Fetch Method::from_compiled_entry.
      // - Jump to optimized code.
      // """
      val adapted =
        if (basic) {
          argZero
        } else vm.alloc { implicit r =>
          println("Adapting...")
          val asTypeMethodRef = vm.clsTable("java.lang.invoke.MethodHandle")
            .methods
            .find(_.sig.name == "asType")
            .get

          val args = new ArrayFiller(asTypeMethodRef.localsSize)
          args.append(argZero)
          args.append(getMethodType(sig.desc))


          invoke0(asTypeMethodRef, args.arr)

          returnedVal(0)
        }

      val form = vm.obj(adapted).apply("form")
      val lformVmEntry = vm.obj(form).apply("vmentry")
      val adaptedMethod = vm.methodHandleMap
        .find(_._1.apply() == lformVmEntry)
        .get
        ._2
        .asInstanceOf[ClsMethod]


      invokeBase(sources, target, adaptedMethod, !adaptedMethod.static)
      frame.locals(0) = adapted

    }
  }


  private def invokeInterface(target: Int, sources: Agg[Int], sig: Sig) = {
    val argZero = frame.locals(sources(0))
    if (argZero == 0)throwExWithTrace("java.lang.NullPointerException", "null")
    else invokeBase(
      sources,
      target,
      mRef = vm.obj(argZero).cls.lookupInterfaceMethod(sig),
      thisCell = true
    )
  }

  private def invokeVirtual(target: Int, sources: Agg[Int], clsIndex: Int, mIndex: Int) = {
    val argZero = frame.locals(sources(0))
    if (argZero == 0)throwExWithTrace("java.lang.NullPointerException", "null")
    else invokeBase(
      sources,
      target,
      mRef = {
        val cls =
          if (vm.isObj(argZero)) vm.obj(argZero).cls
          else if (vm.isArr(argZero)) vm.clsTable("java.lang.Object")
          else ???

        cls.vTable(mIndex)
      },
      thisCell = true
    )
  }


  private def newInstance(target: Int, clsIndex: Int) = {
    val cls = vm.clsTable.clsIndex(clsIndex)
    vm.checkInitialized(cls)
    val obj = vm.alloc(_.newObj(cls.tpe))
    frame.locals(target) = obj.address()
    advancePc()
  }

  private def arrayLength(src: Int, dest: Int) = {
    frame.locals(dest) = vm.arr(frame.locals(src)).arrayLength
    advancePc()
  }

  private def newArray(src: Int, dest: Int, typeRef: Type) = {
    val newArray = vm.alloc(_.newArr(typeRef, frame.locals(src)))
    frame.locals(dest) = newArray.address()
    advancePc()
  }

  private def lookupSwitch(src: Int, default: Int, keys: Agg[Int], targets: Agg[Int]) = {
    var done = false
    var i = 0
    while(!done && i < keys.length){
      val k = keys(i)
      val t = targets(i)
      if (frame.locals(src) == k && !done){
        jumpPhis(t)
        done = true
      }
      i += 1
    }
    if (!done) jumpPhis(default)
  }
  private def tableSwitch(src: Int, min: Int, max: Int, default: Int, targets: Agg[Int]) = {
    val value = frame.locals(src)
    if (value < min || value > max) jumpPhis(default)
    else jumpPhis(targets(value - min))
  }

  private def checkCast(src: Int, dest: Int, desc: Type) = {
    frame.locals(src) match {
      case top
        if (vm.isArr(top) && !vm.check(vm.arr(top).tpe, desc))
          || (vm.isObj(top) && !vm.check(vm.obj(top).tpe, desc)) =>

        throwExWithTrace(
          "java.lang.ClassCastException",
          if (vm.isObj(top)) vm.obj(top).tpe.toString
          else if (vm.isArr(top)) vm.arr(top).tpe.toString
          else "null"
        )
      case _ =>
        frame.locals(dest) = frame.locals(src)
        advancePc()
    }
  }

  private def instanceOf(src: Int, dest: Int, desc: Type) = {
    frame.locals(dest) = frame.locals(src) match {
      case 0 => 0
      case top if vm.isArr(top) && !vm.check(vm.arr(top).tpe, desc) => 0
      case top if vm.isObj(top) && !vm.check(vm.obj(top).tpe, desc) => 0
      case _ => 1
    }
    advancePc()
  }

  private def multiANewArray(desc: Type, symbol: Int, dims: Seq[Int]) = {
    def rec(dims: List[Int], tpe: Type): Int = {

      (dims, tpe) match {
        case (size :: Nil, imm.Type.Arr(innerType)) =>
          vm.alloc(_.newArr(innerType, size)).address()

        case (size :: tail, imm.Type.Arr(innerType: Type.Ref)) =>
          val newArr = vm.alloc(_.newArr(innerType, size))
          for (i <- 0 until size) {
            newArr(i) = rec(tail, innerType)
          }
          newArr.address()


      }
    }

    val dimValues = dims.map(frame.locals).toList

    val array = rec(dimValues, desc)
    frame.locals(symbol) = array
    advancePc()
  }

  def invokeBase(sources: Agg[Int],
                 target: Int,
                 mRef: rt.Method,
                 thisCell: Boolean) = {


    if (mRef.sig.name == "constantPlaceholder"){
      pprint.log(frame.locals(sources(0)))
      pprint.log(vm.obj(frame.locals(sources(0))).cls)
      if (frame.locals(sources(1)) == 0){
        throw new Exception("Cannot call constantPlaceholder with a null argument!s")
      }
      pprint.log(frame.locals(sources(1)))
      pprint.log(vm.obj(frame.locals(sources(1))).cls)
    }
    val args = new ArrayFiller(mRef.localsSize)

    val thisCellOffset = if (thisCell) {
      args.append(frame.locals(sources(0)))
      1
    } else {
      0
    }

    val mArgs = mRef.sig.desc.args
    for (i <- 0 until mArgs.length) {
      val sourceOffset = sources(i + thisCellOffset)
      args.append(frame.locals(sourceOffset))
      mArgs(i).size match {
        case 1 =>
        case 2 => args.append(frame.locals(sourceOffset + 1))
      }
    }

    prepInvoke(mRef, args.arr, Util.writer(frame.locals, target), threadStack.length)
  }

  def getPutField(src: Int, obj: Int, index: Int, prim: Type, get: Boolean) = {

    if (frame.locals(obj) == 0) throwExWithTrace("java.lang.NullPointerException", "null")
    else{
      Util.blit(vm.obj(frame.locals(obj)).members, index, frame.locals, src, prim.size, flip = !get)
      advancePc()
    }
  }

  def getPutStatic(target: Int, clsIndex: Int, index: Int, prim: Type, get: Boolean) = {
    val cls = vm.clsTable.clsIndex(clsIndex)
    vm.checkInitialized(cls)
    Util.blit(new rt.Arr(cls.statics)(vm), index, frame.locals, target, prim.size, flip = !get)
    advancePc()
  }

  def getPutArray(target: Int,
                  index: Int,
                  array: Int,
                  prim: Type,
                  get: Boolean) = {
    val arr = vm.arr(frame.locals(array))
    if (0 <= frame.locals(index) && frame.locals(index) < arr.arrayLength){
      Util.blit(arr, frame.locals(index) * prim.size, frame.locals, target, prim.size, flip = !get)
      advancePc()
    }else{
      throwExWithTrace("java.lang.ArrayIndexOutOfBoundsException", frame.locals(index).toString)
    }
  }

  def trace = {

    threadStack.map( f =>
      new StackTraceElement(
        f.runningClass.tpe.javaName,
        f.method.sig.name,
        f.runningClass.sourceFile.getOrElse("<unknown file>"),
        try f.method.code.blocks(f.pc._1).lines(f.pc._2)
        catch{case _:Throwable => 0 }
      )
    ).toArray
  }

  def returnVal(size: Int, index: Int) = {
    size match{
      case 0 =>
      case 1 => frame.returnTo(frame.locals(index))
      case 2 =>
        frame.returnTo(frame.locals(index))
        frame.returnTo(frame.locals(index + 1))
    }

    val endedFrame = this.threadStack.pop()
    if (endedFrame.indexOfFrameWhosePCToIncrement == threadStack.length) advancePc()
  }

  final def throwExWithTrace(clsName: String, detailMessage: String) = {

    throwException(
      vm.alloc( implicit r =>
        r.newObj(clsName,
          "stackTrace" -> r.register(Virtualizer.toVirtObj(trace)),
          "detailMessage" -> r.register(Virtualizer.toVirtObj(detailMessage))
        )
      )
    )
  }

  @tailrec final def throwException(ex: Obj, print: Boolean = true): Unit = {
    import math.Ordering.Implicits._

    if (print) {
      val msg = Virtualizer.toRealObj[String](ex.apply("detailMessage"))(bindingsInterface, implicitly)
      vm.logger.logException(ex.cls.tpe, msg, trace)
    }

    threadStack.headOption match{
      case Some(frame)=>
        val handler =
          frame.method.code.tryCatches.find{handler =>
            // Inclusive start index, exclusive end index.
            //
            // This is due to the way many bytecode instructions do not survive
            // during the SSA transform (e.g. swap, dup, all the *load ops).
            // As a result, the handler's start and end indices are actually
            // the start and end index of the *next* surviving instruction.
            // The next surviving instruction after `start` is indeed covered
            // by the try-catch range, but the next surviving instruction after
            // `end` is *not*. Hence the comparison with `start` is an inclusive
            // `<=`, but the comparison with `end` is an exclusive `>`
            (handler.start <= frame.pc) &&
            (handler.end > frame.pc) &&
            (handler.blockType.isEmpty ||
            handler.blockType.exists(ex.cls.typeAncestry))
          }

        handler match{
          case None =>
            threadStack.pop()
            throwException(ex, false)
          case Some(TryCatchBlock(start, end, handler, dest, blockType)) =>
            frame.locals(dest) = ex.address()
            frame.pc = (handler, 0)

        }
      case None =>
        throw new UncaughtVmException(
          Virtualizer.toRealObj[Throwable](ex.address())(bindingsInterface, implicitly)
        )
    }
  }
  val bindingsInterface = new Bindings.Interface{
    def throwExWithTrace(clsName: String, detailMessage: String) = {
      thread.throwExWithTrace(clsName, detailMessage)
    }

    def invoke0(cls: Type.Cls, sig: Sig, args: Agg[Any]) = thread.invoke0(cls, sig, args)
    def invoke1(cls: Type.Cls, sig: Sig, args: Agg[Int]) = thread.invoke1(cls, sig, args)

    def returnedVal = thread.returnedVal

    def alloc[T](func: rt.Allocator => T) = vm.alloc(func)

    def invokeRun(a: Int) = {
      val pa = obj(a)
      val mRef = vm.resolveDirectRef(pa.cls.tpe, pa.cls.methods.find(_.sig.name == "run").get.sig).get
      var x = 0
      val args = new Array[Int](mRef.localsSize)
      args(0) = pa.address()
      threads(0).invoke0(mRef, args)

      threads(0).returnedVal(0)
    }
    def newInstance(constr: Int, argArr: Int): Int = alloc{r =>
      val cls = r.obj(constr).apply("clazz")
      val name = toRealObj[String](r.obj(cls).apply("name")).replace('.', '/')
      val newObj = alloc { implicit r =>
        r.newObj(name).address()
      }

      val descStr = toRealObj[String](r.obj(constr).apply("signature"))

      val mRef = vm.clsTable(name).method(
        "<init>",
        Desc.read(descStr)
      ).get

      val args = new Array[Int](mRef.localsSize)
      args(0) = newObj
      arr(argArr).copyToArray(args, 1)
      thread.invoke0(mRef, args)


      newObj
    }

    def typeObjCache = vm.typeObjCache

    def threads = vm.threads

    def offHeap = vm.offHeap
    def setOffHeapPointer(n: Long) = vm.setOffHeapPointer(n)
    def offHeapPointer = vm.offHeapPointer

    def runningClassName(n: Int) = threadStack(n).runningClass.tpe.javaName

    def threadStackLength = threadStack.length

    def internedStrings = vm.internedStrings

    def theUnsafe = vm.theUnsafe

    def toRealObj[T](x: Int)(implicit ct: ClassTag[T]) = Virtualizer.toRealObj(x)(this, ct)

    def toVirtObj(x: Any)(implicit registrar: rt.Allocator) = vm.obj(Virtualizer.toVirtObj(x))

    def trace = thread.trace

    def currentThread = vm.currentThread

    implicit def clsTable = vm.clsTable

    def heap = vm.heap

    def obj(address: Int) = vm.obj(address)

    def arr(address: Int) = vm.arr(address)

    def isArr(address: Int) = vm.isArr(address)

    def isObj(address: Int) = vm.isObj(address)

    def natives = vm.natives

    def lookupNatives(lookupName: imm.Type.Cls, lookupSig: imm.Sig) =
      vm.natives.trapped.find{case rt.NativeMethod(cls, sig, static, func) =>
        (lookupName == cls) && sig == lookupSig
      }

    def methodHandleMap = vm.methodHandleMap

    def checkInitialized(cls: rt.Cls) = vm.checkInitialized(cls)

    def getTypeForTypeObj(addr: Int) = vm.getTypeForTypeObj(addr)
  }

  final def prepInvoke(mRef: rt.Method,
                       args: Array[Int],
                       returnTo: Int => Unit,
                       advanceParentPC: Int) = {
//    println(indent + "PrepInvoke " + mRef + " with " + args)
    assert(args.length == mRef.localsSize)
    mRef match{
      case rt.NativeMethod(cls, sig, static, op) =>
        try op(bindingsInterface, Util.reader(args, 0), returnTo)
        finally if (threadStack.length == advanceParentPC) {
          advancePc()
        }
      case m @ rt.ClsMethod(clsIndex, methodIndex, sig, static, codethunk) =>



        val startFrame = new Frame(
          runningClass = vm.clsTable.clsIndex(clsIndex),
          method = m,
          returnTo = returnTo,
          locals = args,
          indexOfFrameWhosePCToIncrement = advanceParentPC
        )

        //log(indent + "locals " + startFrame.locals)
        threadStack.push(startFrame)
    }
  }

  final def prepInvoke(tpe: imm.Type,
                       sig: imm.Sig,
                       argValues: Agg[Any],
                       returnTo: Int => Unit)
                       : Unit = {

    val mRef = vm.resolveDirectRef(tpe.asInstanceOf[imm.Type.Cls], sig).get
    val args = new ArrayFiller(mRef.localsSize)

    vm.alloc{ implicit r =>
      for(x <- argValues){
        Virtualizer.pushVirtual(x, args.append)
      }

      prepInvoke(mRef, args.arr, returnTo, -1)
    }

  }

  def invoke(mRef: rt.Method, args: Array[Int]): Any = {
    invoke0(mRef, args)
    Virtualizer.popVirtual(mRef.sig.desc.ret, Util.reader(returnedVal, 0))(bindingsInterface)
  }

  def invoke0(mRef: rt.Method, args: Array[Int]): Unit = {
    val startHeight = threadStack.length
    prepInvoke(mRef, args, Util.writer(returnedVal, 0), -1)

    while(threadStack.length != startHeight) step()
  }

  def invoke(cls: imm.Type.Cls, sig: imm.Sig, args: Agg[Any]): Any = {
    invoke0(cls, sig, args)
    Virtualizer.popVirtual(sig.desc.ret, Util.reader(returnedVal, 0))(bindingsInterface)
  }

  def invoke0(cls: imm.Type.Cls, sig: imm.Sig, args: Agg[Any]): Unit = {
    val startHeight = threadStack.length
    prepInvoke(cls, sig, args, Util.writer(returnedVal, 0))

    while(threadStack.length != startHeight) step()
  }

  def invoke1(cls: imm.Type.Cls, sig: imm.Sig, args: Agg[Int]): Unit = {
    val startHeight = threadStack.length
    prepInvoke(cls, sig, args, Util.writer(returnedVal, 0))

    while(threadStack.length != startHeight) step()
  }
}

