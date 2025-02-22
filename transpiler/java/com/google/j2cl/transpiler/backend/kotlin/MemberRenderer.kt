/*
 * Copyright 2021 Google Inc.
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
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.common.InternalCompilerError
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor
import com.google.j2cl.transpiler.ast.AstUtils.getConstructorInvocation
import com.google.j2cl.transpiler.ast.AstUtils.isConstructorInvocationStatement
import com.google.j2cl.transpiler.ast.AstUtils.needsVisibilityBridge
import com.google.j2cl.transpiler.ast.Field
import com.google.j2cl.transpiler.ast.InitializerBlock
import com.google.j2cl.transpiler.ast.Member as JavaMember
import com.google.j2cl.transpiler.ast.Method
import com.google.j2cl.transpiler.ast.MethodDescriptor
import com.google.j2cl.transpiler.ast.MethodDescriptor.ParameterDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypes
import com.google.j2cl.transpiler.ast.ReturnStatement
import com.google.j2cl.transpiler.ast.Statement
import com.google.j2cl.transpiler.ast.TypeDescriptors
import com.google.j2cl.transpiler.ast.Variable
import com.google.j2cl.transpiler.backend.kotlin.ast.CompanionObject
import com.google.j2cl.transpiler.backend.kotlin.ast.Member
import com.google.j2cl.transpiler.backend.kotlin.source.Source
import com.google.j2cl.transpiler.backend.kotlin.source.block
import com.google.j2cl.transpiler.backend.kotlin.source.colonSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.commaSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.emptyLineSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.emptySource
import com.google.j2cl.transpiler.backend.kotlin.source.ifNotNullSource
import com.google.j2cl.transpiler.backend.kotlin.source.inRoundBrackets
import com.google.j2cl.transpiler.backend.kotlin.source.indentedIf
import com.google.j2cl.transpiler.backend.kotlin.source.join
import com.google.j2cl.transpiler.backend.kotlin.source.newLineSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.source
import com.google.j2cl.transpiler.backend.kotlin.source.sourceIf
import com.google.j2cl.transpiler.backend.kotlin.source.spaceSeparated

internal fun Renderer.source(member: Member): Source =
  when (member) {
    is Member.WithCompanionObject -> source(member.companionObject)
    is Member.WithJavaMember -> memberSource(member.javaMember)
    is Member.WithType -> typeSource(member.type)
  }

private fun Renderer.source(companionObject: CompanionObject): Source =
  spaceSeparated(
    source("companion"),
    source("object"),
    block(emptyLineSeparated(companionObject.members.map { source(it) }))
  )

private fun Renderer.memberSource(member: JavaMember): Source =
  when (member) {
    is Method -> methodSource(member)
    is Field -> fieldSource(member)
    is InitializerBlock -> initializerBlockSource(member)
    else -> throw InternalCompilerError("Unhandled ${member::class}")
  }

private fun Renderer.methodSource(method: Method): Source =
  spaceSeparated(
    methodHeaderSource(method),
    sourceIf(!method.isAbstract && !method.isNative) {
      val statements = method.renderedStatements

      // Constructors with no statements can be rendered without curly braces.
      sourceIf(!method.isConstructor || statements.isNotEmpty()) {
        spaceSeparated(
          sourceIf(method.descriptor.isKtProperty) { source("get()") },
          copy(currentReturnLabelIdentifier = null).run {
            block(
              if (method.descriptor.isTodo) todo(literalSource("J2KT: not yet supported"))
              else statementsSource(statements)
            )
          }
        )
      }
    }
  )

private val Method.renderedStatements: List<Statement>
  get() {
    if (!descriptor.isKtDisabled) {
      return body.statements.filter { !isConstructorInvocationStatement(it) }
    }

    if (TypeDescriptors.isPrimitiveVoid(descriptor.returnTypeDescriptor)) {
      return listOf()
    }

    return listOf(
      ReturnStatement.newBuilder()
        .setSourcePosition(sourcePosition)
        .setExpression(descriptor.returnTypeDescriptor.defaultValue)
        .build()
    )
  }

private fun Renderer.fieldSource(field: Field): Source {
  val isFinal = field.descriptor.isFinal
  val typeDescriptor = field.descriptor.typeDescriptor
  val isConst = field.isCompileTimeConstant && field.isStatic
  return newLineSeparated(
    sourceIf(!isConst) { jvmFieldAnnotationSource() },
    objCAnnotationSource(field.descriptor),
    spaceSeparated(
      sourceIf(isConst) { source("const") },
      if (isFinal) source("val") else source("var"),
      assignment(
        colonSeparated(
          identifierSource(field.descriptor.ktMangledName),
          typeDescriptorSource(typeDescriptor)
        ),
        field.initializer.ifNotNullSource(::expressionSource)
      )
    )
  )
}

private fun Renderer.jvmFieldAnnotationSource(): Source =
  at(topLevelQualifiedNameSource("kotlin.jvm.JvmField"))

private fun Renderer.jvmStaticAnnotationSource(): Source =
  at(topLevelQualifiedNameSource("kotlin.jvm.JvmStatic"))

private fun Renderer.initializerBlockSource(initializerBlock: InitializerBlock): Source =
  spaceSeparated(source("init"), statementSource(initializerBlock.block))

private fun Renderer.methodHeaderSource(method: Method): Source {
  val methodDescriptor = method.descriptor
  val methodObjCNames = method.toObjCNames()
  return newLineSeparated(
    sourceIf(method.isStatic) { jvmStaticAnnotationSource() },
    objCAnnotationSource(methodDescriptor, methodObjCNames),
    spaceSeparated(
      methodModifiersSource(methodDescriptor),
      colonSeparated(
        join(
          methodKindAndNameSource(methodDescriptor),
          methodParametersSource(method, methodObjCNames?.parameterNames)
        ),
        if (methodDescriptor.isConstructor) constructorInvocationSource(method)
        else methodReturnTypeSource(methodDescriptor)
      ),
      whereClauseSource(methodDescriptor.typeParameterTypeDescriptors)
    )
  )
}

private fun Renderer.methodKindAndNameSource(methodDescriptor: MethodDescriptor): Source =
  if (methodDescriptor.isConstructor) source("constructor")
  else
    spaceSeparated(
      if (methodDescriptor.isKtProperty) source("val") else source("fun"),
      typeParametersSource(methodDescriptor.typeParameterTypeDescriptors),
      identifierSource(methodDescriptor.ktMangledName)
    )

private fun methodModifiersSource(methodDescriptor: MethodDescriptor): Source =
  spaceSeparated(
    sourceIf(!methodDescriptor.enclosingTypeDescriptor.typeDeclaration.isInterface) {
      spaceSeparated(
        sourceIf(methodDescriptor.isNative) { source("external") },
        if (methodDescriptor.isAbstract) source("abstract")
        else if (
          !methodDescriptor.isFinal &&
            !methodDescriptor.isConstructor &&
            !methodDescriptor.isStatic &&
            !methodDescriptor.visibility.isPrivate
        )
          source("open")
        else emptySource
      )
    },
    sourceIf(methodDescriptor.isKtOverride) { source("override") }
  )

private fun Renderer.methodParametersSource(
  method: Method,
  objCParameterNames: List<String>?
): Source {
  val methodDescriptor = method.descriptor
  val parameterDescriptors = methodDescriptor.parameterDescriptors
  val parameters = method.parameters
  val renderWithNewLines = objCParameterNames != null && parameters.isNotEmpty()
  val optionalNewLineSource = sourceIf(renderWithNewLines) { source("\n") }
  return sourceIf(!methodDescriptor.isKtProperty) {
    inRoundBrackets(
      join(
        indentedIf(
          renderWithNewLines,
          commaSeparated(
            0.until(parameters.size).map { index ->
              join(
                optionalNewLineSource,
                parameterSource(
                  parameterDescriptors[index],
                  parameters[index],
                  objCParameterNames?.get(index)
                )
              )
            }
          )
        ),
        optionalNewLineSource
      )
    )
  }
}

private fun Renderer.parameterSource(
  parameterDescriptor: ParameterDescriptor,
  parameter: Variable,
  objCParameterName: String? = null
): Source {
  val parameterTypeDescriptor = parameterDescriptor.typeDescriptor
  val renderedTypeDescriptor =
    if (!parameterDescriptor.isVarargs) parameterTypeDescriptor
    else (parameterTypeDescriptor as ArrayTypeDescriptor).componentTypeDescriptor!!
  return spaceSeparated(
    sourceIf(parameterDescriptor.isVarargs) { source("vararg") },
    objCParameterName.ifNotNullSource { objCNameAnnotationSource(it) },
    colonSeparated(nameSource(parameter), typeDescriptorSource(renderedTypeDescriptor))
  )
}

internal fun Renderer.methodReturnTypeSource(methodDescriptor: MethodDescriptor): Source =
  methodDescriptor.returnTypeDescriptor
    .takeIf { it != PrimitiveTypes.VOID }
    .ifNotNullSource { typeDescriptorSource(it) }

private fun Renderer.constructorInvocationSource(method: Method) =
  getConstructorInvocation(method).ifNotNullSource { constructorInvocation ->
    join(
      if (constructorInvocation.target.inSameTypeAs(method.descriptor)) source("this")
      else source("super"),
      invocationSource(constructorInvocation)
    )
  }

internal val MethodDescriptor.isKtOverride
  get() =
    isJavaOverride &&
      (javaOverriddenMethodDescriptors.any { it.enclosingTypeDescriptor.isInterface } ||
        !needsVisibilityBridge(this))
