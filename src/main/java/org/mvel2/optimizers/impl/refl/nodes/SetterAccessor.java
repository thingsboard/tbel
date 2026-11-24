package org.mvel2.optimizers.impl.refl.nodes;

import org.mvel2.ParserContext;
import org.mvel2.compiler.AccessorNode;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.util.PropertyTools;

import java.lang.reflect.Method;

import static org.mvel2.DataConversion.convert;
import static org.mvel2.util.ParseTools.getBestCandidate;

public class SetterAccessor implements AccessorNode {
  private AccessorNode nextNode;
  private final Method method;
  private Class<?> targetType;
  private boolean primitive;

  private boolean coercionRequired = false;

  public static final Object[] EMPTY = new Object[0];

  public Object setValue(Object ctx, Object elCtx, VariableResolverFactory variableFactory, Object value) {
    ParserContext.checkReceiver(method, ctx);
    // this local field is required to make sure exception block works with the same coercionRequired value
    // and it is not changed by another thread while setter is invoked 
    boolean attemptedCoercion = coercionRequired;
    try {
      if (coercionRequired) {
        return method.invoke(ctx, convert(value, targetType));
      }
      else {
        return method.invoke(ctx, value == null && primitive ? PropertyTools.getPrimitiveInitialValue(targetType) : value);
      }
    }
    catch (IllegalArgumentException e) {
      if (ctx != null && !method.getDeclaringClass().isInstance(ctx)) {
        // A receiver of another class: its setter is looked up, with the value it takes. Only then; for a
        // receiver the cached setter belongs to, the value has to be converted, which is tried below. A static
        // method is no setter of a property, as it is none when the property is first resolved.
        Method o = ParserContext.checkRedispatch(getBestCandidate(new Object[]{value}, method.getName(), ctx.getClass(), instanceMethods(ctx.getClass()), false));
        if (o != null) {
          return executeOverrideTarget(o, ctx, value);
        }
      }

      if (!attemptedCoercion) {
        coercionRequired = true;
        return setValue(ctx, elCtx, variableFactory, value);
      }
      throw new RuntimeException("unable to bind property", e);
    }
    catch (Exception e) {
      throw new RuntimeException("error calling method: " + method.getDeclaringClass().getName() + "." + method.getName(), e);
    }
  }

  public Object getValue(Object ctx, Object elCtx, VariableResolverFactory vars) {
    return null;
  }

  public SetterAccessor(Method method) {
    this.method = method;
    assert method != null;
    primitive = (this.targetType = method.getParameterTypes()[0]).isPrimitive();
  }

  public Method getMethod() {
    return method;
  }

  public AccessorNode setNextNode(AccessorNode nextNode) {
    return this.nextNode = nextNode;
  }

  public AccessorNode getNextNode() {
    return nextNode;
  }

  public String toString() {
    return method.getDeclaringClass().getName() + "." + method.getName();
  }

  public Class getKnownEgressType() {
    return method.getReturnType();
  }

  private static Method[] instanceMethods(Class<?> cls) {
    java.util.List<Method> methods = new java.util.ArrayList<>();
    for (Method candidate : cls.getMethods()) {
      if (!java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
        methods.add(candidate);
      }
    }
    return methods.toArray(new Method[0]);
  }

  private Object executeOverrideTarget(Method o, Object ctx, Object value) {
    try {
      // converted to what the setter of this class takes, which need not be what the first one took
      return o.invoke(ctx, convert(value, o.getParameterTypes()[0]));
    }
    catch (Exception e2) {
      throw new RuntimeException("unable to invoke method", e2);
    }
  }
}
