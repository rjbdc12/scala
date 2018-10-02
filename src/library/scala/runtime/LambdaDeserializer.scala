/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.runtime

import java.lang.invoke._

/**
 * This class is only intended to be called by synthetic `$deserializeLambda$` method that the Scala 2.12
 * compiler will add to classes hosting lambdas.
 *
 * It is not intended to be consumed directly.
 */
object LambdaDeserializer {
  /**
   * Deserialize a lambda by calling `LambdaMetafactory.altMetafactory` to spin up a lambda class
   * and instantiating this class with the captured arguments.
   *
   * A cache may be provided to ensure that subsequent deserialization of the same lambda expression
   * is cheap, it amounts to a reflective call to the constructor of the previously created class.
   * However, deserialization of the same lambda expression is not guaranteed to use the same class,
   * concurrent deserialization of the same lambda expression may spin up more than one class.
   *
   * Assumptions:
   *  - No additional marker interfaces are required beyond `java.io.Serializable`. These are
   *    not stored in `SerializedLambda`, so we can't reconstitute them.
   *  - No additional bridge methods are passed to `altMetafactory`. Again, these are not stored.
   *
   * @param lookup      The factory for method handles. Must have access to the implementation method, the
   *                    functional interface class, and `java.io.Serializable`.
   * @param cache       A cache used to avoid spinning up a class for each deserialization of a given lambda. May be `null`
   * @param serialized  The lambda to deserialize. Note that this is typically created by the `readResolve`
   *                    member of the anonymous class created by `LambdaMetaFactory`.
   * @return            An instance of the functional interface
   */
  def deserializeLambda(lookup: MethodHandles.Lookup, cache: java.util.Map[String, MethodHandle],
                        targetMethodMap: java.util.Map[String, MethodHandle], serialized: SerializedLambda): AnyRef = {
    assert(targetMethodMap != null)
    def slashDot(name: String) = name.replaceAll("/", ".")
    val loader = lookup.lookupClass().getClassLoader
    val implClass = loader.loadClass(slashDot(serialized.getImplClass))
    val key = LambdaDeserialize.nameAndDescriptorKey(serialized.getImplMethodName, serialized.getImplMethodSignature)

    def makeCallSite: CallSite = {
      import serialized._
      def parseDescriptor(s: String) =
        MethodType.fromMethodDescriptorString(s, loader)

      val funcInterfaceSignature = parseDescriptor(getFunctionalInterfaceMethodSignature)
      val instantiated = parseDescriptor(getInstantiatedMethodType)
      val functionalInterfaceClass = loader.loadClass(slashDot(getFunctionalInterfaceClass))

      val implMethodSig = parseDescriptor(getImplMethodSignature)
      // Construct the invoked type from the impl method type. This is the type of a factory
      // that will be generated by the meta-factory. It is a method type, with param types
      // coming form the types of the captures, and return type being the functional interface.
      val invokedType: MethodType = {
        // 1. Add receiver for non-static impl methods
        val withReceiver = getImplMethodKind match {
          case MethodHandleInfo.REF_invokeStatic | MethodHandleInfo.REF_newInvokeSpecial =>
            implMethodSig
          case _ =>
            implMethodSig.insertParameterTypes(0, implClass)
        }
        // 2. Remove lambda parameters, leaving only captures. Note: the receiver may be a lambda parameter,
        //    such as in `Function<Object, String> s = Object::toString`
        val lambdaArity = funcInterfaceSignature.parameterCount()
        val from = withReceiver.parameterCount() - lambdaArity
        val to = withReceiver.parameterCount()

        // 3. Drop the lambda return type and replace with the functional interface.
        withReceiver.dropParameterTypes(from, to).changeReturnType(functionalInterfaceClass)
      }

      // Lookup the implementation method
      val implMethod: MethodHandle = if (targetMethodMap.containsKey(key)) {
        targetMethodMap.get(key)
      } else {
        throw new IllegalArgumentException("Illegal lambda deserialization")
      }

      val flags: Int = LambdaMetafactory.FLAG_SERIALIZABLE

      LambdaMetafactory.altMetafactory(
        lookup, getFunctionalInterfaceMethodName, invokedType,

        /* samMethodType          = */ funcInterfaceSignature,
        /* implMethod             = */ implMethod,
        /* instantiatedMethodType = */ instantiated,
        /* flags                  = */ flags.asInstanceOf[AnyRef]
      )
    }

    val factory: MethodHandle = if (cache == null) {
      makeCallSite.getTarget
    } else cache.synchronized{
      cache.get(key) match {
        case null =>
          val callSite = makeCallSite
          val temp = callSite.getTarget
          cache.put(key, temp)
          temp
        case target => target
      }
    }

    val captures = Array.tabulate(serialized.getCapturedArgCount)(n => serialized.getCapturedArg(n))
    factory.invokeWithArguments(captures: _*)
  }

  private[this] val JavaIOSerializable = {
    // We could actually omit this marker interface as LambdaMetaFactory will add it if
    // the FLAG_SERIALIZABLE is set and of the provided markers extend it. But the code
    // is cleaner if we uniformly add a single marker, so I'm leaving it in place.
    "java.io.Serializable"
  }
}
