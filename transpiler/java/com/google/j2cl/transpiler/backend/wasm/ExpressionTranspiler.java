/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.wasm;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.j2cl.transpiler.ast.TypeDescriptors.isPrimitiveVoid;
import static com.google.j2cl.transpiler.backend.wasm.GenerationEnvironment.getWasmTypeForPrimitive;
import static java.lang.String.format;

import com.google.common.collect.Iterables;
import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.ArrayAccess;
import com.google.j2cl.transpiler.ast.ArrayLength;
import com.google.j2cl.transpiler.ast.BinaryExpression;
import com.google.j2cl.transpiler.ast.BinaryOperator;
import com.google.j2cl.transpiler.ast.BooleanLiteral;
import com.google.j2cl.transpiler.ast.CastExpression;
import com.google.j2cl.transpiler.ast.ConditionalExpression;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.ExpressionWithComment;
import com.google.j2cl.transpiler.ast.FieldAccess;
import com.google.j2cl.transpiler.ast.FieldDescriptor;
import com.google.j2cl.transpiler.ast.InstanceOfExpression;
import com.google.j2cl.transpiler.ast.MethodCall;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.MultiExpression;
import com.google.j2cl.transpiler.ast.NewArray;
import com.google.j2cl.transpiler.ast.NewInstance;
import com.google.j2cl.transpiler.ast.NullLiteral;
import com.google.j2cl.transpiler.ast.NumberLiteral;
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor;
import com.google.j2cl.transpiler.ast.PrimitiveTypes;
import com.google.j2cl.transpiler.ast.SuperReference;
import com.google.j2cl.transpiler.ast.ThisReference;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;
import com.google.j2cl.transpiler.ast.UnaryExpression;
import com.google.j2cl.transpiler.ast.VariableDeclarationExpression;
import com.google.j2cl.transpiler.ast.VariableDeclarationFragment;
import com.google.j2cl.transpiler.ast.VariableReference;
import com.google.j2cl.transpiler.backend.common.SourceBuilder;
import java.util.List;

/**
 * Transforms expressions into WASM code.
 *
 * <p>As is typical in stack based VMs, expressions evaluate leaving the result in the stack.
 */
final class ExpressionTranspiler {

  public static void renderWithUnusedResult(
      Expression expression, SourceBuilder sourceBuilder, GenerationEnvironment environment) {
    if (returnsVoid(expression)) {
      render(expression, sourceBuilder, environment);
    } else {
      sourceBuilder.append("(drop ");
      render(expression, sourceBuilder, environment);
      sourceBuilder.append(")");
    }
  }

  public static void render(
      Expression expression,
      final SourceBuilder sourceBuilder,
      final GenerationEnvironment environment) {

    new AbstractVisitor() {
      @Override
      public boolean enterBooleanLiteral(BooleanLiteral booleanLiteral) {
        sourceBuilder.append("(i32.const " + (booleanLiteral.getValue() ? "1" : "0") + ")");
        return false;
      }

      @Override
      public boolean enterBinaryExpression(BinaryExpression expression) {
        BinaryOperator operator = expression.getOperator();
        if (operator == BinaryOperator.ASSIGN) {
          return renderAssignment(expression.getLeftOperand(), expression.getRightOperand());
        }

        renderBinaryOperation(expression);
        return false;
      }

      private void renderBinaryOperation(BinaryExpression expression) {
        WasmBinaryOperation wasmOperation = WasmBinaryOperation.getOperation(expression);
        if (wasmOperation == null) {
          // TODO(dramaix): remove and checkArgument once every operator is implemented.
          enterExpression(expression);
          return;
        }

        sourceBuilder.append("(" + wasmOperation.getInstruction(expression) + " ");
        renderTypedExpression(
            wasmOperation.getOperandType(expression), expression.getLeftOperand());
        sourceBuilder.append(" ");
        renderTypedExpression(
            wasmOperation.getOperandType(expression), expression.getRightOperand());
        sourceBuilder.append(")");
      }

      private boolean renderAssignment(Expression left, Expression right) {
        sourceBuilder.append("(");
        renderAccessExpression(left, "set");
        sourceBuilder.append(" ");
        renderTypedExpression(left.getTypeDescriptor(), right);
        sourceBuilder.append(")");
        return false;
      }

      private void renderAccessExpression(Expression expression, String instruction) {
        if (expression instanceof VariableReference) {
          sourceBuilder.append(
              format(
                  "local.%s %s",
                  instruction,
                  environment.getDeclarationName(((VariableReference) expression).getTarget())));

        } else if (expression instanceof FieldAccess) {
          FieldDescriptor fieldDescriptor = ((FieldAccess) expression).getTarget();
          Expression qualifier = ((FieldAccess) expression).getQualifier();
          if (fieldDescriptor.isStatic()) {
            sourceBuilder.append(
                format("global.%s %s", instruction, environment.getFieldName(fieldDescriptor)));
          } else {
            sourceBuilder.append(
                format(
                    "struct.%s %s %s",
                    instruction,
                    environment.getWasmTypeName(fieldDescriptor.getEnclosingTypeDescriptor()),
                    environment.getFieldName(fieldDescriptor)));
            render(qualifier);
          }

        } else if (expression instanceof ArrayAccess) {
          ArrayAccess arrayAccess = (ArrayAccess) expression;
          Expression arrayExpression = arrayAccess.getArrayExpression();

          sourceBuilder.append(
              format(
                  "array.%s %s ",
                  instruction,
                  environment.getElementArrayTypeName(arrayExpression.getTypeDescriptor())));
          renderArrayReference(arrayExpression);
          sourceBuilder.append(" ");
          renderTypedExpression(PrimitiveTypes.INT, arrayAccess.getIndexExpression());
        }
      }

      private void renderArrayReference(Expression arrayExpression) {
        sourceBuilder.append(
            format(
                "(struct.get %s $elements ",
                environment.getWasmTypeName(arrayExpression.getTypeDescriptor())));
        render(arrayExpression);
        sourceBuilder.append(")");
      }

      @Override
      public boolean enterArrayAccess(ArrayAccess arrayAccess) {
        sourceBuilder.append("(");
        renderAccessExpression(arrayAccess, "get");
        sourceBuilder.append(")");
        return false;
      }

      @Override
      public boolean enterArrayLength(ArrayLength arrayLength) {
        sourceBuilder.append(
            format(
                "(array.len %s ",
                environment.getElementArrayTypeName(
                    arrayLength.getArrayExpression().getTypeDescriptor())));
        renderArrayReference(arrayLength.getArrayExpression());
        sourceBuilder.append(")");
        return false;
      }

      @Override
      public boolean enterCastExpression(CastExpression castExpression) {
        TypeDescriptor castTypeDescriptor =
            castExpression.getCastTypeDescriptor().toRawTypeDescriptor();
        if (castTypeDescriptor.isInterface()) {
          // TODO(rluble): implement interface casts.
          render(castExpression.getExpression());
          return false;
        } else if (castTypeDescriptor.isClass()
            || castTypeDescriptor.isEnum()
            || castTypeDescriptor.isArray()) {
          // TODO(b/180967010): implement full support of array cast.
          sourceBuilder.append("(ref.cast ");
          render(castExpression.getExpression());
          sourceBuilder.append(
              format(" (global.get %s))", environment.getRttGlobalName(castTypeDescriptor)));
          return false;
        }
        // TODO(b/170691713) : handle primitive conversions and coersions.
        return enterExpression(castExpression);
      }

      @Override
      public boolean enterConditionalExpression(ConditionalExpression conditionalExpression) {
        TypeDescriptor typeDescriptor = conditionalExpression.getTypeDescriptor();
        sourceBuilder.append("(if (result " + environment.getWasmType(typeDescriptor) + ") ");
        renderTypedExpression(
            PrimitiveTypes.BOOLEAN, conditionalExpression.getConditionExpression());
        sourceBuilder.append(" (then ");
        renderTypedExpression(typeDescriptor, conditionalExpression.getTrueExpression());
        sourceBuilder.append(") (else ");
        renderTypedExpression(typeDescriptor, conditionalExpression.getFalseExpression());
        sourceBuilder.append("))");
        return false;
      }

      @Override
      public boolean enterExpression(Expression expression) {
        // TODO(rluble): remove this method which is only a place holder until all expressions are
        // implemented.
        if (!returnsVoid(expression)) {
          // This is an unimplemented expression that returns a value (i.e. not a call to a
          // method returning void).
          // Emit the default value for the type as a place holder so that the module compiles.
          render(expression.getTypeDescriptor().getDefaultValue());
        }
        return false;
      }

      @Override
      public boolean enterFieldAccess(FieldAccess fieldAccess) {
        sourceBuilder.append("(");
        renderAccessExpression(fieldAccess, "get");
        sourceBuilder.append(")");
        return false;
      }

      @Override
      public boolean enterExpressionWithComment(ExpressionWithComment expressionWithComment) {
        sourceBuilder.append(";; " + expressionWithComment.getComment());
        render(expressionWithComment.getExpression());
        return false;
      }

      @Override
      public boolean enterInstanceOfExpression(InstanceOfExpression instanceOfExpression) {
        TypeDescriptor testTypeDescriptor = instanceOfExpression.getTestTypeDescriptor();
        if (testTypeDescriptor.isClass() || testTypeDescriptor.isEnum()) {
          sourceBuilder.append("(ref.test ");
          render(instanceOfExpression.getExpression());
          sourceBuilder.append(
              format(
                  " (global.get %s))",
                  environment.getRttGlobalName(
                      ((DeclaredTypeDescriptor) testTypeDescriptor).getTypeDeclaration())));
          return false;
        }
        // TODO(rluble): handle interface, primitive and array casts.
        return enterExpression(instanceOfExpression);
      }

      @Override
      public boolean enterMethodCall(MethodCall methodCall) {
        MethodDescriptor target = methodCall.getTarget();

        DeclaredTypeDescriptor enclosingTypeDescriptor = target.getEnclosingTypeDescriptor();
        if (methodCall.isPolymorphic()) {
          if (target.isClassDynamicDispatch()) {
            sourceBuilder.append("(call_ref ");

            // Pass the implicit parameter.
            Expression implicitParameter = methodCall.getQualifier();
            renderTypedExpression(enclosingTypeDescriptor, implicitParameter);

            // Pass the rest of the parameters.
            renderTypedExpressions(target.getParameterTypeDescriptors(), methodCall.getArguments());

            // Retrieve the method reference from the vtable to provide it to call_ref.
            sourceBuilder.append(
                format(
                    "(struct.get %s %s (struct.get %s $vtable",
                    environment.getWasmVtableTypeName(enclosingTypeDescriptor),
                    environment.getVtableSlot(target),
                    environment.getWasmTypeName(enclosingTypeDescriptor)));
            renderTypedExpression(enclosingTypeDescriptor, methodCall.getQualifier());

            sourceBuilder.append("))");
            sourceBuilder.append(")");
            return false;
          }
        } else {
          // Non polymorphic methods are called directly, regardless of whether they are
          // instance methods or not.

          String wasmInfo = methodCall.getTarget().getWasmInfo();
          if (wasmInfo == null) {
            sourceBuilder.append("(call " + environment.getMethodImplementationName(target) + " ");
          } else {
            sourceBuilder.append("(" + wasmInfo + " ");
          }

          if (target.isStatic()) {
            renderTypedExpressions(target.getParameterTypeDescriptors(), methodCall.getArguments());
          } else {
            // Constructors, non static private methods and super method calls receive the qualifier
            // as the first parameter, then the corresponding arguments.
            render(methodCall.getQualifier());
            renderTypedExpressions(target.getParameterTypeDescriptors(), methodCall.getArguments());
          }

          sourceBuilder.append(")");
          return false;
        }
        // TODO(rluble): remove once all method call types are implemented.
        return super.enterMethodCall(methodCall);
      }

      @Override
      public boolean enterMultiExpression(MultiExpression multiExpression) {
        List<Expression> expressions = multiExpression.getExpressions();
        Expression returnValue = Iterables.getLast(expressions);
        sourceBuilder.openParens("block ");
        sourceBuilder.append(
            "(result " + environment.getWasmType(returnValue.getDeclaredTypeDescriptor()) + ")");
        expressions.forEach(
            expression -> {
              sourceBuilder.newLine();
              if (expression == returnValue) {
                render(expression);
              } else {
                renderWithUnusedResult(expression, sourceBuilder, environment);
              }
            });
        sourceBuilder.closeParens();
        return false;
      }

      @Override
      public boolean enterNewArray(NewArray newArray) {
        Expression dimensionExpression = newArray.getDimensionExpressions().get(0);
        String arrayType = environment.getWasmTypeName(newArray.getTypeDescriptor());
        String elementArrayType = environment.getElementArrayTypeName(newArray.getTypeDescriptor());

        sourceBuilder.append(
            format(
                "(struct.new_with_rtt %s "
                    + "(global.get %s) (i32.const 0) (array.new_default_with_rtt %s ",
                arrayType,
                environment.getWasmVtableGlobalName(TypeDescriptors.get().javaLangObject),
                elementArrayType));
        renderTypedExpression(dimensionExpression.getTypeDescriptor(), dimensionExpression);
        sourceBuilder.append(
            format(" (global.get %s.rtt)) (global.get %s.rtt))", elementArrayType, arrayType));
        return false;
      }

      @Override
      public boolean enterNewInstance(NewInstance newInstance) {
        MethodDescriptor target = newInstance.getTarget();
        sourceBuilder.append("(call " + environment.getMethodImplementationName(target) + " ");
        sourceBuilder.append(
            format(
                "(struct.new_with_rtt %s (global.get %s)",
                environment.getWasmTypeName(newInstance.getTypeDescriptor()),
                environment.getWasmVtableGlobalName(newInstance.getTypeDescriptor())));

        // TODO(b/178728155): Go back to using struct.new_default_with_rtt once it supports
        // assigning immutable fields at construction. See b/178738025 for an alternative design
        // that might have better runtime tradeoffs.

        // Initialize instance fields to their default values. Note that struct.new_default_with_rtt
        // cannot be used here since the vtable needs to an immutable field to enable sub-typing
        // hence will need to be initialized at construction.
        environment
            .getWasmTypeLayout(newInstance.getTypeDescriptor().getTypeDeclaration())
            .getAllInstanceFields()
            .forEach(
                f -> {
                  sourceBuilder.append(" ");
                  render(f.getDescriptor().getTypeDescriptor().getDefaultValue());
                });

        sourceBuilder.append(
            format(
                " (global.get %s))",
                environment.getRttGlobalName(
                    newInstance.getTypeDescriptor().getTypeDeclaration())));
        renderTypedExpressions(target.getParameterTypeDescriptors(), newInstance.getArguments());
        sourceBuilder.append(")");
        return false;
      }

      @Override
      public boolean enterNullLiteral(NullLiteral nullLiteral) {
        sourceBuilder.append(
            "(ref.null " + environment.getWasmTypeName(nullLiteral.getTypeDescriptor()) + ")");
        return false;
      }

      @Override
      public boolean enterNumberLiteral(NumberLiteral numberLiteral) {
        PrimitiveTypeDescriptor typeDescriptor = numberLiteral.getTypeDescriptor();
        String wasmType = checkNotNull(environment.getWasmType(typeDescriptor));
        String value = convertToWasmLiteral(numberLiteral.getValue());
        sourceBuilder.append("(" + wasmType + ".const " + value + ")");
        return false;
      }

      private String convertToWasmLiteral(Number value) {
        // Handle NaN, Infinity, -Infinity as nan, inf and -inf.
        return value.toString().replace("NaN", "nan").replace("Infinity", "inf");
      }

      @Override
      public boolean enterThisReference(ThisReference thisReference) {
        if (thisReference.getTypeDescriptor().isInterface()) {
          return super.enterThisReference(thisReference);
        }
        sourceBuilder.append("(local.get $this)");
        return false;
      }

      @Override
      public boolean enterSuperReference(SuperReference superReference) {
        sourceBuilder.append("(local.get $this)");
        return false;
      }

      @Override
      public boolean enterUnaryExpression(UnaryExpression expression) {
        WasmUnaryOperation wasmUnaryOperation = WasmUnaryOperation.get(expression);

        sourceBuilder.append("(" + wasmUnaryOperation.getInstruction(expression) + " ");
        renderTypedExpression(
            wasmUnaryOperation.getOperandType(expression), expression.getOperand());

        sourceBuilder.append(")");
        return false;
      }

      @Override
      public boolean enterVariableDeclarationExpression(VariableDeclarationExpression expression) {
        expression.getFragments().forEach(this::renderVariableDeclarationFragment);
        return false;
      }

      private void renderVariableDeclarationFragment(VariableDeclarationFragment fragment) {
        if (fragment.getInitializer() != null) {
          sourceBuilder.newLine();
          renderAssignment(fragment.getVariable().createReference(), fragment.getInitializer());
        }
      }

      // TODO(dramaix): remove when coercions and casting are in place.
      private void renderTypedExpression(TypeDescriptor typeDescriptor, Expression expression) {
        ExpressionTranspiler.renderTypedExpression(
            typeDescriptor, expression, sourceBuilder, environment);
      }

      private void renderTypedExpressions(
          List<TypeDescriptor> typeDescriptors, List<Expression> expressions) {
        checkArgument(typeDescriptors.size() == expressions.size());
        for (int i = 0; i < typeDescriptors.size(); i++) {
          renderTypedExpression(typeDescriptors.get(i), expressions.get(i));
        }
      }

      @Override
      public boolean enterVariableReference(VariableReference variableReference) {
        sourceBuilder.append("(");
        renderAccessExpression(variableReference, "get");
        sourceBuilder.append(")");
        return false;
      }

      private void render(Expression expression) {
        expression.accept(this);
      }
    }.render(expression);
  }

  public static boolean returnsVoid(Expression expression) {
    if (expression instanceof MethodCall && ((MethodCall) expression).getTarget().isConstructor()) {
      // This is a super() or this() call and the generated constructor for WASM is actually returns
      // the instance (as opposed to how it is modeled in the AST where the return is void).
      return false;
    }
    // Even though per our Java based AST an assignment is an expression that returns the value of
    // its rhs, the AST is transformed so that the resulting value is never used and the assignment
    // can be safely considered not to produce a value.
    boolean isAssignmentExpression =
        expression instanceof BinaryExpression
            && ((BinaryExpression) expression).getOperator() == BinaryOperator.ASSIGN;
    return isPrimitiveVoid(expression.getTypeDescriptor()) || isAssignmentExpression;
  }

  // TODO(b/181829823,b/182436577): remove this method when shift operation on longs take the right
  //  type in their rhs and when NullLiterals have the correct type.
  public static void renderTypedExpression(
      TypeDescriptor typeDescriptor,
      Expression expression,
      SourceBuilder sourceBuilder,
      GenerationEnvironment environment) {

    if (typeDescriptor.isPrimitive()
        && expression.getTypeDescriptor().isPrimitive()
        && !getWasmTypeForPrimitive(typeDescriptor)
            .equals(getWasmTypeForPrimitive(expression.getTypeDescriptor()))) {
      render(typeDescriptor.getDefaultValue(), sourceBuilder, environment);
    } else if (expression instanceof NullLiteral) {
      render(typeDescriptor.getDefaultValue(), sourceBuilder, environment);
    } else {
      render(expression, sourceBuilder, environment);
    }
  }

  private ExpressionTranspiler() {}
}
