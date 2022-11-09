/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.resolve.calls.NewCommonSuperTypeCalculator
import org.jetbrains.kotlin.resolve.calls.inference.components.TypeVariableDirectionCalculator.ResolveDirection
import org.jetbrains.kotlin.resolve.calls.inference.extractTypeForGivenRecursiveTypeParameter
import org.jetbrains.kotlin.resolve.calls.inference.model.*
import org.jetbrains.kotlin.types.AbstractTypeApproximator
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.jetbrains.kotlin.types.model.*

class ResultTypeResolver(
    val typeApproximator: AbstractTypeApproximator,
    val trivialConstraintTypeInferenceOracle: TrivialConstraintTypeInferenceOracle,
    private val languageVersionSettings: LanguageVersionSettings
) {
    interface Context : TypeSystemInferenceExtensionContext {
        fun isProperType(type: KotlinTypeMarker): Boolean
        fun buildNotFixedVariablesToStubTypesSubstitutor(): TypeSubstitutorMarker
        fun isReified(variable: TypeVariableMarker): Boolean
    }

    private val isTypeInferenceForSelfTypesSupported: Boolean
        get() = languageVersionSettings.supportsFeature(LanguageFeature.TypeInferenceOnCallsWithSelfTypes)

    private fun Context.getDefaultTypeForSelfType(
        constraints: List<Constraint>,
        typeVariable: TypeVariableMarker
    ): KotlinTypeMarker? {
        val typeVariableConstructor = typeVariable.freshTypeConstructor() as TypeVariableTypeConstructorMarker
        val typesForRecursiveTypeParameters = constraints.mapNotNull { constraint ->
            if (constraint.position.from !is DeclaredUpperBoundConstraintPosition<*>) return@mapNotNull null
            val typeParameter = typeVariableConstructor.typeParameter ?: return@mapNotNull null
            extractTypeForGivenRecursiveTypeParameter(constraint.type, typeParameter)
        }.takeIf { it.isNotEmpty() } ?: return null

        return createCapturedStarProjectionForSelfType(typeVariableConstructor, typesForRecursiveTypeParameters)
    }

    private fun Context.getDefaultType(
        direction: ResolveDirection,
        constraints: List<Constraint>,
        typeVariable: TypeVariableMarker
    ): KotlinTypeMarker {
        if (isTypeInferenceForSelfTypesSupported) {
            getDefaultTypeForSelfType(constraints, typeVariable)?.let { return it }
        }

        return if (direction == ResolveDirection.TO_SUBTYPE) nothingType() else nullableAnyType()
    }

    fun findResultType(c: Context, variableWithConstraints: VariableWithConstraints, direction: ResolveDirection): KotlinTypeMarker {
        findResultTypeOrNull(c, variableWithConstraints, direction)?.let { return it }

        // no proper constraints
        return c.getDefaultType(direction, variableWithConstraints.constraints, variableWithConstraints.typeVariable)
    }

    private fun findResultTypeOrNull(
        c: Context,
        variableWithConstraints: VariableWithConstraints,
        direction: ResolveDirection
    ): KotlinTypeMarker? {
        findResultIfThereIsEqualsConstraint(c, variableWithConstraints)?.let { return it }

        val subType = c.findSubType(variableWithConstraints)
        // Super type should be the most flexible, sub type should be the least one
        val superType = c.findSuperType(variableWithConstraints).makeFlexibleIfNecessary(c, variableWithConstraints.constraints)

        return if (direction == ResolveDirection.TO_SUBTYPE || direction == ResolveDirection.UNKNOWN) {
            c.resultType(subType, superType, variableWithConstraints)
        } else {
            c.resultType(superType, subType, variableWithConstraints)
        }
    }

    /*
     * We propagate nullness flexibility into the result type from type variables in other constraints
     * to prevent variable fixation into less flexible type.
     *  Constraints:
     *      UPPER(TypeVariable(T)..TypeVariable(T)?)
     *      UPPER(Foo?)
     *  Result type = makeFlexibleIfNecessary(Foo?) = Foo!
     *
     * We don't propagate nullness flexibility in depth as it's non-determined for now (see KT-35534):
     *  CST(Bar<Foo>, Bar<Foo!>) = Bar<Foo!>
     *  CST(Bar<Foo!>, Bar<Foo>) = Bar<Foo>
     * But: CST(Foo, Foo!) = CST(Foo!, Foo) = Foo!
     */
    private fun KotlinTypeMarker?.makeFlexibleIfNecessary(c: Context, constraints: List<Constraint>) = with(c) {
        when (val type = this@makeFlexibleIfNecessary) {
            is SimpleTypeMarker -> {
                if (constraints.any { it.type.typeConstructor().isTypeVariable() && it.type.hasFlexibleNullability() }) {
                    createFlexibleType(type.makeSimpleTypeDefinitelyNotNullOrNotNull(), type.withNullability(true))
                } else type
            }
            else -> type
        }
    }

    private fun Context.resultType(
        firstCandidate: KotlinTypeMarker?,
        secondCandidate: KotlinTypeMarker?,
        variableWithConstraints: VariableWithConstraints
    ): KotlinTypeMarker? {
        if (firstCandidate == null || secondCandidate == null) return firstCandidate ?: secondCandidate

        specialResultForIntersectionType(firstCandidate, secondCandidate)?.let { intersectionWithAlternative ->
            return intersectionWithAlternative
        }

        if (isSuitableType(firstCandidate, variableWithConstraints)) return firstCandidate

        return if (isSuitableType(secondCandidate, variableWithConstraints)) {
            secondCandidate
        } else {
            firstCandidate
        }
    }

    private fun Context.specialResultForIntersectionType(
        firstCandidate: KotlinTypeMarker,
        secondCandidate: KotlinTypeMarker,
    ): KotlinTypeMarker? {
        if (firstCandidate.typeConstructor().isIntersection()) {
            if (!AbstractTypeChecker.isSubtypeOf(this, firstCandidate.toPublicType(), secondCandidate.toPublicType())) {
                return createTypeWithAlternativeForIntersectionResult(firstCandidate, secondCandidate)
            }
        }

        return null
    }

    private fun KotlinTypeMarker.toPublicType(): KotlinTypeMarker =
        typeApproximator.approximateToSuperType(this, TypeApproximatorConfiguration.PublicDeclaration.SaveAnonymousTypes) ?: this

    private fun Context.isSuitableType(resultType: KotlinTypeMarker, variableWithConstraints: VariableWithConstraints): Boolean {
        val filteredConstraints = variableWithConstraints.constraints.filter { isProperTypeForFixation(it.type) }
        for (constraint in filteredConstraints) {
            if (!checkConstraint(this, constraint.type, constraint.kind, resultType)) return false
        }
        if (!trivialConstraintTypeInferenceOracle.isSuitableResultedType(resultType)) {
            if (resultType.isNullableType() && checkSingleLowerNullabilityConstraint(filteredConstraints)) return false
            if (isReified(variableWithConstraints.typeVariable)) return false
        }

        return true
    }

    private fun checkSingleLowerNullabilityConstraint(constraints: List<Constraint>): Boolean {
        return constraints.singleOrNull { it.kind.isLower() }?.isNullabilityConstraint ?: false
    }

    private fun Context.findSubType(variableWithConstraints: VariableWithConstraints): KotlinTypeMarker? {
        val lowerConstraintTypes = prepareLowerConstraints(variableWithConstraints.constraints)

        if (lowerConstraintTypes.isNotEmpty()) {
            val types = sinkIntegerLiteralTypes(lowerConstraintTypes)
            var commonSuperType = computeCommonSuperType(types)

            if (commonSuperType.contains { it.asSimpleType()?.isStubTypeForVariableInSubtyping() == true }) {
                val typesWithoutStubs = types.filter { lowerType ->
                    !lowerType.contains { it.asSimpleType()?.isStubTypeForVariableInSubtyping() == true }
                }

                if (typesWithoutStubs.isNotEmpty()) {
                    commonSuperType = computeCommonSuperType(typesWithoutStubs)
                }
            }

            /**
             *
             * fun <T> Array<out T>.intersect(other: Iterable<T>) {
             *      val set = toMutableSet()
             *      set.retainAll(other)
             * }
             * fun <X> Array<out X>.toMutableSet(): MutableSet<X> = ...
             * fun <Y> MutableCollection<in Y>.retainAll(elements: Iterable<Y>) {}
             *
             * Here, when we solve type system for `toMutableSet` we have the following constrains:
             * Array<C(out T)> <: Array<out X> => C(out X) <: T.
             * If we fix it to T = C(out X) then return type of `toMutableSet()` will be `MutableSet<C(out X)>`
             * and type of variable `set` will be `MutableSet<out T>` and the following line will have contradiction.
             *
             * To fix this problem when we fix variable, we will approximate captured types before fixation.
             *
             */

            return typeApproximator.approximateToSuperType(
                commonSuperType,
                TypeApproximatorConfiguration.InternalTypesApproximation
            ) ?: commonSuperType
        }

        return null
    }

    private fun Context.computeCommonSuperType(types: List<KotlinTypeMarker>): KotlinTypeMarker =
        with(NewCommonSuperTypeCalculator) { commonSuperType(types) }

    private fun Context.prepareLowerConstraints(constraints: List<Constraint>): List<KotlinTypeMarker> {
        var atLeastOneProper = false
        var atLeastOneNonProper = false

        val lowerConstraintTypes = mutableListOf<KotlinTypeMarker>()

        for (constraint in constraints) {
            if (constraint.kind != ConstraintKind.LOWER) continue

            val type = constraint.type
            lowerConstraintTypes.add(type)

            if (isProperTypeForFixation(type)) {
                atLeastOneProper = true
            } else {
                atLeastOneNonProper = true
            }
        }

        if (!atLeastOneProper) return emptyList()
        if (!atLeastOneNonProper) return lowerConstraintTypes

        val notFixedToStubTypesSubstitutor = buildNotFixedVariablesToStubTypesSubstitutor()

        return lowerConstraintTypes.map { if (isProperTypeForFixation(it)) it else notFixedToStubTypesSubstitutor.safeSubstitute(it) }
    }

    private fun Context.sinkIntegerLiteralTypes(types: List<KotlinTypeMarker>): List<KotlinTypeMarker> {
        return types.sortedBy { type ->

            val containsILT = type.contains { it.asSimpleType()?.isIntegerLiteralType() ?: false }
            if (containsILT) 1 else 0
        }
    }

    private fun Context.computeUpperType(upperConstraints: List<Constraint>): KotlinTypeMarker {
        val isInferringIntoEmptyIntersectionEnabled =
            languageVersionSettings.supportsFeature(LanguageFeature.ForbidInferringTypeVariablesIntoEmptyIntersection)

        // TODO: Remove this after stopping support of disabling `ForbidInferringTypeVariablesIntoEmptyIntersection`
        // If `ForbidInferringTypeVariablesIntoEmptyIntersection` is enabled, we do the corresponding checks during resolution and completion
        return if (!isInferringIntoEmptyIntersectionEnabled) {
            val intersectionUpperType = intersectTypes(upperConstraints.map { it.type })
            val resultIsActuallyIntersection = intersectionUpperType.typeConstructor().isIntersection()

            val isThereUnwantedIntersectedTypes = if (resultIsActuallyIntersection) {
                val intersectionSupertypes = intersectionUpperType.typeConstructor().supertypes()
                val intersectionClasses = intersectionSupertypes.count {
                    it.typeConstructor().isClassTypeConstructor() && !it.typeConstructor().isInterface()
                }
                val areThereIntersectionFinalClasses = intersectionSupertypes.any { it.typeConstructor().isCommonFinalClassConstructor() }
                intersectionClasses > 1 || areThereIntersectionFinalClasses
            } else false

            val upperType = if (isThereUnwantedIntersectedTypes) {
                /*
                 * We shouldn't infer a type variable into the intersection type if there is an explicit expected type,
                 * otherwise it can lead to something like this:
                 *
                 * fun <T : String> materialize(): T = null as T
                 * val bar: Int = materialize() // no errors, T is inferred into String & Int
                 */
                val filteredUpperConstraints = upperConstraints.filterNot { it.isExpectedTypePosition() }.map { it.type }
                if (filteredUpperConstraints.isNotEmpty()) intersectTypes(filteredUpperConstraints) else intersectionUpperType
            } else intersectionUpperType
            upperType
        } else {
            intersectTypes(upperConstraints.map { it.type })
        }
    }

    private fun Context.findSuperType(variableWithConstraints: VariableWithConstraints): KotlinTypeMarker? {
        val upperConstraints =
            variableWithConstraints.constraints.filter { it.kind == ConstraintKind.UPPER && this@findSuperType.isProperTypeForFixation(it.type) }

        if (upperConstraints.isNotEmpty()) {
            val upperType = computeUpperType(upperConstraints)

            return typeApproximator.approximateToSubType(
                upperType,
                TypeApproximatorConfiguration.InternalTypesApproximation
            ) ?: upperType
        }

        return null
    }

    private fun Context.isProperTypeForFixation(type: KotlinTypeMarker): Boolean =
        isProperTypeForFixation(type) { isProperType(it) }

    private fun findResultIfThereIsEqualsConstraint(c: Context, variableWithConstraints: VariableWithConstraints): KotlinTypeMarker? =
        with(c) {
            val properEqualityConstraints = variableWithConstraints.constraints.filter {
                it.kind == ConstraintKind.EQUALITY && c.isProperTypeForFixation(it.type)
            }

            return c.representativeFromEqualityConstraints(properEqualityConstraints)
        }

    // Discriminate integer literal types as they are less specific than separate integer types (Int, Short...)
    private fun Context.representativeFromEqualityConstraints(constraints: List<Constraint>): KotlinTypeMarker? {
        if (constraints.isEmpty()) return null

        val constraintTypes = constraints.map { it.type }
        val nonLiteralTypes = constraintTypes.filter { !it.typeConstructor().isIntegerLiteralTypeConstructor() }
        return nonLiteralTypes.singleBestRepresentative()
            ?: constraintTypes.singleBestRepresentative()
            ?: constraintTypes.first() // seems like constraint system has contradiction
    }
}
