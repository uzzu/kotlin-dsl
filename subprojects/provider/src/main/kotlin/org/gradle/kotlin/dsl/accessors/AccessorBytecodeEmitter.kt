/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.accessors

import kotlinx.metadata.Flag
import kotlinx.metadata.KmFunctionVisitor
import kotlinx.metadata.KmTypeVisitor
import kotlinx.metadata.KmVariance
import kotlinx.metadata.flagsOf
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.KotlinClassMetadata

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.reflect.TypeOf
import org.gradle.api.tasks.TaskProvider

import org.gradle.internal.hash.HashUtil

import org.gradle.kotlin.dsl.concurrent.WriterThread
import org.gradle.kotlin.dsl.concurrent.unorderedParallelMap
import org.gradle.kotlin.dsl.support.bytecode.ACONST_NULL
import org.gradle.kotlin.dsl.support.bytecode.ALOAD
import org.gradle.kotlin.dsl.support.bytecode.ARETURN
import org.gradle.kotlin.dsl.support.bytecode.CHECKCAST
import org.gradle.kotlin.dsl.support.bytecode.INVOKEINTERFACE
import org.gradle.kotlin.dsl.support.bytecode.INVOKESTATIC
import org.gradle.kotlin.dsl.support.bytecode.InternalName
import org.gradle.kotlin.dsl.support.bytecode.InternalNameOf
import org.gradle.kotlin.dsl.support.bytecode.KmTypeBuilder
import org.gradle.kotlin.dsl.support.bytecode.LDC
import org.gradle.kotlin.dsl.support.bytecode.RETURN
import org.gradle.kotlin.dsl.support.bytecode.actionTypeOf
import org.gradle.kotlin.dsl.support.bytecode.inlineFunctionFlags
import org.gradle.kotlin.dsl.support.bytecode.internalName
import org.gradle.kotlin.dsl.support.bytecode.jvmGetterSignatureFor
import org.gradle.kotlin.dsl.support.bytecode.method
import org.gradle.kotlin.dsl.support.bytecode.moduleFileFor
import org.gradle.kotlin.dsl.support.bytecode.moduleMetadataBytesFor
import org.gradle.kotlin.dsl.support.bytecode.publicKotlinClass
import org.gradle.kotlin.dsl.support.bytecode.publicStaticMethod
import org.gradle.kotlin.dsl.support.bytecode.visitParameter
import org.gradle.kotlin.dsl.support.bytecode.visitSignature
import org.gradle.kotlin.dsl.support.bytecode.with
import org.gradle.kotlin.dsl.support.bytecode.writeFileFacadeClassHeader
import org.gradle.kotlin.dsl.support.bytecode.writeFunctionOf
import org.gradle.kotlin.dsl.support.bytecode.writePropertyOf

import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

import java.io.File


internal
object AccessorBytecodeEmitter {

    fun emitAccessorsFor(
        projectSchema: ProjectSchema<TypeAccessibility>,
        srcDir: File,
        binDir: File
    ): List<InternalName> = WriterThread().use { writer ->

        val internalClassNames = accessorsFor(projectSchema).unorderedParallelMap { accessor ->

            val (internalClassName, classBytes) =
                when (accessor) {
                    is Accessor.ForConfiguration -> emitAccessorsForConfiguration(accessor)
                    is Accessor.ForExtension -> emitAccessorForExtension(accessor)
                    is Accessor.ForContainerElement -> emitAccessorForContainerElement(accessor)
                    is Accessor.ForTask -> emitAccessorForTask(accessor)
                    is Accessor.ForConvention -> emitAccessorForConvention(accessor)
                }

            writer.writeFile(
                binDir.resolve("$internalClassName.class"),
                classBytes
            )

            internalClassName
        }.toList()

        writer.writeFile(
            moduleFileFor(binDir),
            moduleMetadataBytesFor(internalClassNames)
        )

        internalClassNames
    }

    private
    fun emitAccessorForConvention(accessor: Accessor.ForConvention): Pair<InternalName, ByteArray> {

        val accessorSpec = accessor.spec
        val className = internalNameForAccessorClassOf(accessorSpec)
        val (accessibleReceiverType, name, returnType) = accessorSpec
        val propertyName = name.kotlinIdentifier
        val receiverTypeName = accessibleReceiverType.internalName()
        val (kotlinReturnType, jvmReturnType) = when (returnType) {
            is TypeAccessibility.Accessible -> returnType.type.builder to returnType.internalName()
            is TypeAccessibility.Inaccessible -> KotlinType.any to InternalNameOf.Object
        }
        val getterSignature = jvmGetterSignatureFor(
            propertyName,
            accessorDescriptorFor(receiverTypeName, jvmReturnType)
        )
        val configureSignature = JvmMethodSignature(
            propertyName,
            "(L$receiverTypeName;Lorg/gradle/api/Action;)V"
        )

        val header = writeFileFacadeClassHeader {
            writePropertyOf(
                receiverType = receiverTypeName.builder,
                returnType = kotlinReturnType,
                propertyName = propertyName,
                getterSignature = getterSignature
            )
            writeFunctionOf(
                receiverType = receiverTypeName.builder,
                returnType = KotlinType.unit,
                parameters = {
                    visitParameter("configure", actionTypeOf(kotlinReturnType))
                },
                name = propertyName,
                signature = configureSignature
            )
        }

        val classBytes =
            publicKotlinClass(className, header) {
                publicStaticMethod(getterSignature) {
                    loadConventionOf(name, returnType, jvmReturnType)
                    ARETURN()
                }
                publicStaticMethod(configureSignature) {
                    ALOAD(1)
                    loadConventionOf(name, returnType, jvmReturnType)
                    invokeAction()
                    RETURN()
                }
            }

        return className to classBytes
    }

    private
    fun MethodVisitor.invokeAction() {
        INVOKEINTERFACE(Action::class.internalName, "execute", "(Ljava/lang/Object;)V")
    }

    private
    fun MethodVisitor.loadConventionOf(name: AccessorNameSpec, returnType: TypeAccessibility, jvmReturnType: InternalName) {
        ALOAD(0)
        LDC(name.original)
        invokeRuntime(
            "conventionPluginOf",
            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"
        )
        if (returnType is TypeAccessibility.Accessible)
            CHECKCAST(jvmReturnType)
    }

    private
    fun emitAccessorForTask(accessor: Accessor.ForTask): Pair<InternalName, ByteArray> =
        emitContainerElementAccessorFor(
            accessor.spec,
            taskProviderTypeName,
            namedTaskWithTypeMethodDescriptor
        )

    private
    fun emitAccessorForContainerElement(accessor: Accessor.ForContainerElement): Pair<InternalName, ByteArray> =
        emitContainerElementAccessorFor(
            accessor.spec,
            namedDomainObjectProviderTypeName,
            namedWithTypeMethodDescriptor
        )

    private
    fun emitContainerElementAccessorFor(
        accessorSpec: TypedAccessorSpec,
        providerType: InternalName,
        namedMethodDescriptor: String
    ): Pair<InternalName, ByteArray> {

        // val $receiverType.$name: $providerType<$returnType>
        //   get() = named("$name", $returnType::class.java)

        val className = internalNameForAccessorClassOf(accessorSpec)
        val (accessibleReceiverType, name, returnType) = accessorSpec
        val propertyName = name.kotlinIdentifier
        val receiverTypeName = accessibleReceiverType.internalName()
        val getterSignature = jvmGetterSignatureFor(
            propertyName,
            accessorDescriptorFor(receiverTypeName, providerType)
        )
        val (kotlinReturnType, jvmReturnType) = accessibleReturnTypeFor(returnType)

        val header = writeFileFacadeClassHeader {
            writeElementAccessorMetadataFor(
                receiverTypeName,
                providerType,
                kotlinReturnType,
                propertyName,
                getterSignature
            )
        }

        val classBytes =
            publicKotlinClass(className, header) {
                publicStaticMethod(getterSignature.name, getterSignature.desc) {
                    ALOAD(0)
                    LDC(propertyName)
                    LDC(jvmReturnType)
                    INVOKEINTERFACE(receiverTypeName, "named", namedMethodDescriptor)
                    ARETURN()
                }
            }

        return className to classBytes
    }

    private
    fun emitAccessorForExtension(accessor: Accessor.ForExtension): Pair<InternalName, ByteArray> {

        val accessorSpec = accessor.spec
        val className = internalNameForAccessorClassOf(accessorSpec)
        val (accessibleReceiverType, name, returnType) = accessorSpec
        val propertyName = name.kotlinIdentifier
        val receiverTypeName = accessibleReceiverType.internalName()
        val (kotlinReturnType, jvmReturnType) = when (returnType) {
            is TypeAccessibility.Accessible -> returnType.type.builder to returnType.internalName()
            is TypeAccessibility.Inaccessible -> KotlinType.any to InternalNameOf.Object
        }
        val getterSignature = jvmGetterSignatureFor(
            propertyName,
            accessorDescriptorFor(receiverTypeName, jvmReturnType)
        )
        val configureSignature = JvmMethodSignature(
            propertyName,
            "(L$receiverTypeName;Lorg/gradle/api/Action;)V"
        )

        val header = writeFileFacadeClassHeader {
            writePropertyOf(
                receiverType = accessibleReceiverType.type.builder,
                returnType = kotlinReturnType,
                propertyName = propertyName,
                getterSignature = getterSignature
            )
            writeFunctionOf(
                receiverType = accessibleReceiverType.type.builder,
                returnType = KotlinType.unit,
                parameters = {
                    visitParameter("configure", actionTypeOf(kotlinReturnType))
                },
                name = propertyName,
                signature = configureSignature
            )
        }

        val classBytes =
            publicKotlinClass(className, header) {
                publicStaticMethod(getterSignature) {
                    ALOAD(0)
                    LDC(name.original)
                    invokeRuntime(
                        "extensionOf",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"
                    )
                    if (returnType is TypeAccessibility.Accessible)
                        CHECKCAST(jvmReturnType)
                    ARETURN()
                }
                publicStaticMethod(configureSignature) {
                    ALOAD(0)
                    CHECKCAST(ExtensionAware::class.internalName)
                    INVOKEINTERFACE(ExtensionAware::class.internalName, "getExtensions", "()Lorg/gradle/api/plugins/ExtensionContainer;")
                    LDC(name.original)
                    ALOAD(1)
                    INVOKEINTERFACE(ExtensionContainer::class.internalName, "configure", "(Ljava/lang/String;Lorg/gradle/api/Action;)V")
                    RETURN()
                }
            }

        return className to classBytes
    }

    private
    val SchemaType.builder: KmTypeBuilder
        get() = value.builder

    private
    val TypeOf<*>.builder: KmTypeBuilder
        get() = when {
            isParameterized -> genericTypeOf(
                parameterizedTypeDefinition.builder,
                actualTypeArguments[0].builder
            )
            else -> concreteClass.builder()
        }

    private
    fun Class<out Any>.builder() = internalName.builder

    private
    val InternalName.builder: KmTypeBuilder
        get() = { visitClass(value.replace('$', '.')) }

    private
    fun internalNameForAccessorClassOf(accessorSpec: TypedAccessorSpec): InternalName =
        InternalName("org/gradle/kotlin/dsl/Accessors${hashOf(accessorSpec)}Kt")

    private
    fun emitAccessorsForConfiguration(accessor: Accessor.ForConfiguration): Pair<InternalName, ByteArray> {

        val propertyName = accessor.name
        val className = InternalName("org/gradle/kotlin/dsl/${propertyName.capitalize()}ConfigurationAccessorsKt")

        val getterSignature = jvmGetterSignatureFor(accessor.name, configurationAccessorMethodSignature)
        val overload1 = JvmMethodSignature(
            propertyName,
            "(Lorg/gradle/api/artifacts/dsl/DependencyHandler;Ljava/lang/Object;)Lorg/gradle/api/artifacts/Dependency;"
        )
        val overload2 = JvmMethodSignature(
            propertyName,
            "(Lorg/gradle/api/artifacts/dsl/DependencyHandler;Ljava/lang/String;Lorg/gradle/api/Action;)Lorg/gradle/api/artifacts/ExternalModuleDependency;"
        )
        val overload3 = JvmMethodSignature(
            propertyName,
            "(Lorg/gradle/api/artifacts/dsl/DependencyHandler;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lorg/gradle/api/artifacts/ExternalModuleDependency;"
        )
        val overload3Defaults = JvmMethodSignature(
            "$propertyName\$default",
            "(" +
                "Lorg/gradle/api/artifacts/dsl/DependencyHandler;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                "ILjava/lang/Object;" +
                ")Lorg/gradle/api/artifacts/ExternalModuleDependency;"
        )
        val genericOverload = JvmMethodSignature(
            propertyName,
            "(" +
                "Lorg/gradle/api/artifacts/dsl/DependencyHandler;" +
                "Lorg/gradle/api/artifacts/Dependency;" +
                "Lorg/gradle/api/Action;" +
                ")Lorg/gradle/api/artifacts/Dependency;"
        )

        val constraintHandlerOverload1 = JvmMethodSignature(
            propertyName,
            "(Lorg/gradle/api/artifacts/dsl/DependencyConstraintHandler;Ljava/lang/Object;)Lorg/gradle/api/artifacts/DependencyConstraint;"
        )
        val constraintHandlerOverload2 = JvmMethodSignature(
            propertyName,
            "(Lorg/gradle/api/artifacts/dsl/DependencyConstraintHandler;Ljava/lang/Object;Lorg/gradle/api/Action;)Lorg/gradle/api/artifacts/DependencyConstraint;"
        )

        val dependencyHandler: KmTypeBuilder = DependencyHandler::class.internalName.builder
        val dependency: KmTypeBuilder = Dependency::class.internalName.builder

        val header = writeFileFacadeClassHeader {

            writeConfigurationAccessorMetadataFor(propertyName, getterSignature)

            writeFunctionOf(
                receiverType = dependencyHandler,
                nullableReturnType = dependency,
                name = propertyName,
                parameterName = "dependencyNotation",
                parameterType = { visitClass(InternalNameOf.Any) },
                signature = overload1
            )

            writeFunctionOf(
                receiverType = dependencyHandler,
                returnType = { visitClass(ExternalModuleDependency::class.internalName) },
                name = propertyName,
                parameters = {
                    visitParameter("dependencyNotation", KotlinType.string)
                    visitParameter("configurationAction", actionTypeOf {
                        visitClass(ExternalModuleDependency::class.internalName)
                    })
                },
                signature = overload2
            )

            writeFunctionOf(
                receiverType = dependencyHandler,
                returnType = { visitClass(ExternalModuleDependency::class.internalName) },
                name = propertyName,
                parameters = {
                    visitParameter("group", KotlinType.string)
                    visitParameter("name", KotlinType.string)
                    visitOptionalParameter("version", KotlinType.string)
                    visitOptionalParameter("configuration", KotlinType.string)
                    visitOptionalParameter("classifier", KotlinType.string)
                    visitOptionalParameter("ext", KotlinType.string)
                },
                signature = overload3
            )

            val typeParameter: KmTypeBuilder = { visitTypeParameter(0) }
            visitFunction(inlineFunctionFlags, propertyName)!!.run {
                visitTypeParameter(0, "T", 0, KmVariance.INVARIANT)!!.run {
                    visitUpperBound(0).with(dependency)
                    visitEnd()
                }
                visitReceiverParameterType(0).with(dependencyHandler)
                visitParameter("dependency", typeParameter)
                visitParameter("action", actionTypeOf(typeParameter))
                visitReturnType(0).with(typeParameter)
                visitSignature(genericOverload)
                visitEnd()
            }

            writeFunctionOf(
                receiverType = { visitClass(DependencyConstraintHandler::class.internalName) },
                nullableReturnType = { visitClass(DependencyConstraint::class.internalName) },
                name = propertyName,
                parameterName = "constraintNotation",
                parameterType = { visitClass(InternalNameOf.Any) },
                signature = constraintHandlerOverload1
            )

            writeFunctionOf(
                receiverType = { visitClass(DependencyConstraintHandler::class.internalName) },
                returnType = { visitClass(DependencyConstraint::class.internalName) },
                name = propertyName,
                parameters = {
                    visitParameter("constraintNotation", {
                        visitClass(InternalNameOf.Any)
                    })
                    visitParameter("configurationAction", actionTypeOf {
                        visitClass(DependencyConstraint::class.internalName)
                    })
                },
                signature = constraintHandlerOverload2
            )
        }

        val classBytes =
            publicKotlinClass(className, header) {

                emitConfigurationAccessorFor(propertyName, getterSignature)

                publicStaticMethod(overload1) {
                    ALOAD(0)
                    LDC(propertyName)
                    ALOAD(1)
                    INVOKEINTERFACE(DependencyHandler::class.internalName, "add", "(Ljava/lang/String;Ljava/lang/Object;)Lorg/gradle/api/artifacts/Dependency;")
                    ARETURN()
                }

                publicStaticMethod(overload2) {
                    ALOAD(0)
                    LDC(propertyName)
                    ALOAD(1)
                    ALOAD(2)
                    invokeRuntime("addDependencyTo",
                        "(L${DependencyHandler::class.internalName};Ljava/lang/String;Ljava/lang/Object;Lorg/gradle/api/Action;)Lorg/gradle/api/artifacts/Dependency;"
                    )
                    CHECKCAST(ExternalModuleDependency::class.internalName)
                    ARETURN()
                }

                publicStaticMethod(overload3) {
                    ACONST_NULL()
                    ARETURN()
                }

                method(
                    Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
                    overload3Defaults.name,
                    overload3Defaults.desc) {
                    ACONST_NULL()
                    ARETURN()
                }

                publicStaticMethod(genericOverload) {
                    ALOAD(2)
                    ALOAD(1)
                    invokeAction()
                    ALOAD(0)
                    LDC(propertyName)
                    ALOAD(1)
                    INVOKEINTERFACE(DependencyHandler::class.internalName, "add", "(Ljava/lang/String;Ljava/lang/Object;)Lorg/gradle/api/artifacts/Dependency;")
                    ARETURN()
                }

                publicStaticMethod(constraintHandlerOverload1) {
                    ALOAD(0)
                    LDC(propertyName)
                    ALOAD(1)
                    INVOKEINTERFACE(DependencyConstraintHandler::class.internalName, "add", "(Ljava/lang/String;Ljava/lang/Object;)Lorg/gradle/api/artifacts/DependencyConstraint;")
                    ARETURN()
                }

                publicStaticMethod(constraintHandlerOverload2) {
                    ALOAD(0)
                    LDC(propertyName)
                    ALOAD(1)
                    ALOAD(2)
                    INVOKEINTERFACE(DependencyConstraintHandler::class.internalName, "add", "(Ljava/lang/String;Ljava/lang/Object;Lorg/gradle/api/Action;)Lorg/gradle/api/artifacts/DependencyConstraint;")
                    ARETURN()
                }
            }

        return className to classBytes
    }

    private
    fun KmFunctionVisitor.visitOptionalParameter(parameterName: String, stringType: KmTypeBuilder) {
        visitParameter(
            parameterName,
            stringType,
            parameterFlags = flagsOf(Flag.ValueParameter.DECLARES_DEFAULT_VALUE),
            parameterTypeFlags = flagsOf(Flag.Type.IS_NULLABLE)
        )
    }

    private
    fun MethodVisitor.invokeRuntime(function: String, desc: String) {
        INVOKESTATIC(InternalName("org/gradle/kotlin/dsl/accessors/runtime/RuntimeKt"), function, desc)
    }

    private
    fun hashOf(accessorSpec: TypedAccessorSpec) =
        HashUtil.createCompactMD5(accessorSpec.toString())

    private
    fun accessibleReturnTypeFor(returnType: TypeAccessibility): Pair<InternalName, InternalName> =
        when (returnType) {
            is TypeAccessibility.Accessible -> returnType.internalName().let { it to it }
            is TypeAccessibility.Inaccessible -> InternalNameOf.Any to InternalNameOf.Object
        }

    private
    fun TypeAccessibility.Accessible.internalName() = type.value.concreteClass.internalName

    private
    fun ClassWriter.emitConfigurationAccessorFor(name: String, signature: JvmMethodSignature) {
        emitContainerElementAccessorFor(name, signature)
    }

    private
    fun ClassWriter.emitContainerElementAccessorFor(
        elementName: String,
        signature: JvmMethodSignature
    ) {
        publicStaticMethod(signature.name, signature.desc) {
            ALOAD(0)
            LDC(elementName)
            INVOKEINTERFACE(namedDomainObjectContainerTypeName, "named", namedMethodDescriptor)
            ARETURN()
        }
    }

    private
    fun KotlinClassMetadata.FileFacade.Writer.writeConfigurationAccessorMetadataFor(
        configurationName: String,
        getterSignature: JvmMethodSignature
    ) {
        writePropertyOf(
            receiverType = genericTypeOf(namedDomainObjectContainerTypeName.builder, configurationTypeName.builder),
            returnType = genericTypeOf(namedDomainObjectProviderTypeName.builder, configurationTypeName.builder),
            propertyName = configurationName,
            getterSignature = getterSignature
        )
    }

    private
    fun genericTypeOf(genericType: KmTypeBuilder, genericArgument: KmTypeBuilder): KmTypeVisitor.() -> Unit = {
        genericType()
        visitArgument(0, KmVariance.INVARIANT)!!.run {
            genericArgument()
            visitEnd()
        }
    }

    private
    fun KotlinClassMetadata.FileFacade.Writer.writeElementAccessorMetadataFor(
        containerType: InternalName,
        providerType: InternalName,
        elementType: InternalName,
        propertyName: String,
        getterSignature: JvmMethodSignature
    ) {
        writePropertyOf(
            receiverType = containerType.builder,
            returnType = genericTypeOf(providerType.builder, elementType.builder),
            propertyName = propertyName,
            getterSignature = getterSignature
        )
    }

    private
    val configurationContainerInternalName = ConfigurationContainer::class.internalName

    private
    val configurationTypeName = Configuration::class.internalName

    private
    val namedDomainObjectProviderTypeName = NamedDomainObjectProvider::class.internalName

    private
    val namedMethodDescriptor = "(Ljava/lang/String;)L$namedDomainObjectProviderTypeName;"

    private
    val namedWithTypeMethodDescriptor = "(Ljava/lang/String;Ljava/lang/Class;)L$namedDomainObjectProviderTypeName;"

    private
    val taskProviderTypeName = TaskProvider::class.internalName

    private
    val namedTaskWithTypeMethodDescriptor = "(Ljava/lang/String;Ljava/lang/Class;)L$taskProviderTypeName;"

    private
    val namedDomainObjectContainerTypeName = NamedDomainObjectContainer::class.internalName

    private
    val configurationAccessorMethodSignature = accessorDescriptorFor(namedDomainObjectContainerTypeName, namedDomainObjectProviderTypeName)

    private
    fun accessorDescriptorFor(receiverType: InternalName, returnType: InternalName) =
        "(L$receiverType;)L$returnType;"
}


private
object KotlinType {

    val string: KmTypeBuilder = { visitClass("kotlin/String") }

    val unit: KmTypeBuilder = { visitClass("kotlin/Unit") }

    val any: KmTypeBuilder = { visitClass("kotlin/Any") }
}


internal
sealed class Accessor {

    data class ForConfiguration(val name: String) : Accessor()

    data class ForExtension(val spec: TypedAccessorSpec) : Accessor()

    data class ForConvention(val spec: TypedAccessorSpec) : Accessor()

    data class ForContainerElement(val spec: TypedAccessorSpec) : Accessor()

    data class ForTask(val spec: TypedAccessorSpec) : Accessor()
}


internal
fun accessorsFor(schema: ProjectSchema<TypeAccessibility>): Sequence<Accessor> = sequence {
    schema.run {
        AccessorScope().run {
            yieldAll(uniqueAccessorsFor(extensions).map(Accessor::ForExtension))
            yieldAll(uniqueAccessorsFor(conventions).map(Accessor::ForConvention))
            yieldAll(uniqueAccessorsFor(tasks).map(Accessor::ForTask))
            yieldAll(uniqueAccessorsFor(containerElements).map(Accessor::ForContainerElement))
            yieldAll(accessorsForConfigurationsOf(schema))
        }
    }
}


internal
fun accessorsForConfigurationsOf(projectSchema: ProjectSchema<*>) =
    projectSchema.configurations.asSequence().map { Accessor.ForConfiguration(it) }


private
fun ClassVisitor.publicStaticMethod(
    jvmMethodSignature: JvmMethodSignature,
    signature: String? = null,
    exceptions: Array<String>? = null,
    methodBody: MethodVisitor.() -> Unit
) = jvmMethodSignature.run {
    publicStaticMethod(name, desc, signature, exceptions, methodBody)
}