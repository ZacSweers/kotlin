/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.extensions.predicate

import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.fir.extensions.AnnotationFqn

/**
 * For reference read KDoc to [AbstractPredicate]
 * @see [AbstractPredicate]
 */
sealed class LookupPredicate : AbstractPredicate<LookupPredicate> {
    abstract override val annotations: Set<AnnotationFqn>
    final override val metaAnnotations: Set<AnnotationFqn>
        get() = emptySet()

    abstract override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R

    class Or(
        override val a: LookupPredicate,
        override val b: LookupPredicate
    ) : LookupPredicate(), AbstractPredicate.Or<LookupPredicate> {
        override val annotations: Set<AnnotationFqn> = a.annotations + b.annotations

        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitOr(this, data)
        }
    }

    class And(
        override val a: LookupPredicate,
        override val b: LookupPredicate
    ) : LookupPredicate(), AbstractPredicate.And<LookupPredicate> {
        override val annotations: Set<AnnotationFqn> = a.annotations + b.annotations

        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitAnd(this, data)
        }
    }

    sealed class Annotated(
        final override val annotations: Set<AnnotationFqn>,
        final override val targets: Set<KotlinTarget>,
    ) : LookupPredicate(), AbstractPredicate.Annotated<LookupPredicate> {
        init {
            require(annotations.isNotEmpty()) {
                "Annotations should be not empty"
            }
            PredicateTargets.requireSupported(targets)
        }

        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitAnnotated(this, data)
        }
    }

    class AnnotatedWith @JvmOverloads constructor(
        annotations: Set<AnnotationFqn>,
        targets: Set<KotlinTarget> = emptySet(),
    ) : Annotated(annotations, targets), AbstractPredicate.AnnotatedWith<LookupPredicate> {
        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitAnnotatedWith(this, data)
        }
    }

    class AncestorAnnotatedWith @JvmOverloads constructor(
        annotations: Set<AnnotationFqn>,
        targets: Set<KotlinTarget> = emptySet(),
    ) : Annotated(annotations, targets), AbstractPredicate.AncestorAnnotatedWith<LookupPredicate> {
        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitAncestorAnnotatedWith(this, data)
        }
    }

    class ParentAnnotatedWith @JvmOverloads constructor(
        annotations: Set<AnnotationFqn>,
        targets: Set<KotlinTarget> = emptySet(),
    ) : Annotated(annotations, targets), AbstractPredicate.ParentAnnotatedWith<LookupPredicate> {
        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitParentAnnotatedWith(this, data)
        }
    }


    class HasAnnotatedWith @JvmOverloads constructor(
        annotations: Set<AnnotationFqn>,
        targets: Set<KotlinTarget> = emptySet(),
    ) : Annotated(annotations, targets), AbstractPredicate.HasAnnotatedWith<LookupPredicate> {
        override fun <R, D> accept(visitor: PredicateVisitor<LookupPredicate, R, D>, data: D): R {
            return visitor.visitHasAnnotatedWith(this, data)
        }
    }

    // -------------------------------------------- DSL --------------------------------------------

    object BuilderContext : AbstractPredicate.BuilderContext<LookupPredicate>() {
        override infix fun LookupPredicate.or(other: LookupPredicate): LookupPredicate = Or(this, other)
        override infix fun LookupPredicate.and(other: LookupPredicate): LookupPredicate = And(this, other)

        // ------------------- varargs -------------------
        override fun annotated(vararg annotations: AnnotationFqn): LookupPredicate = annotated(annotations.toList())
        override fun ancestorAnnotated(vararg annotations: AnnotationFqn): LookupPredicate = ancestorAnnotated(annotations.toList())
        override fun parentAnnotated(vararg annotations: AnnotationFqn): LookupPredicate = parentAnnotated(annotations.toList())
        override fun hasAnnotated(vararg annotations: AnnotationFqn): LookupPredicate = hasAnnotated(annotations.toList())

        override fun annotatedOrUnder(vararg annotations: AnnotationFqn): LookupPredicate =
            annotated(*annotations) or ancestorAnnotated(*annotations)

        // ------------------- collections -------------------
        override fun annotated(annotations: Collection<AnnotationFqn>): LookupPredicate = AnnotatedWith(annotations.toSet())
        override fun ancestorAnnotated(annotations: Collection<AnnotationFqn>): LookupPredicate = AncestorAnnotatedWith(annotations.toSet())
        override fun parentAnnotated(annotations: Collection<AnnotationFqn>): LookupPredicate = ParentAnnotatedWith(annotations.toSet())
        override fun hasAnnotated(annotations: Collection<AnnotationFqn>): LookupPredicate = HasAnnotatedWith(annotations.toSet())
        override fun annotatedOrUnder(annotations: Collection<AnnotationFqn>): LookupPredicate =
            annotated(annotations) or ancestorAnnotated(annotations)

        // ------------------- targets -------------------
        override fun annotated(annotations: Collection<AnnotationFqn>, targets: Set<KotlinTarget>): LookupPredicate =
            AnnotatedWith(annotations.toSet(), targets.toSet())

        override fun ancestorAnnotated(annotations: Collection<AnnotationFqn>, targets: Set<KotlinTarget>): LookupPredicate =
            AncestorAnnotatedWith(annotations.toSet(), targets.toSet())

        override fun parentAnnotated(annotations: Collection<AnnotationFqn>, targets: Set<KotlinTarget>): LookupPredicate =
            ParentAnnotatedWith(annotations.toSet(), targets.toSet())

        override fun hasAnnotated(annotations: Collection<AnnotationFqn>, targets: Set<KotlinTarget>): LookupPredicate =
            HasAnnotatedWith(annotations.toSet(), targets.toSet())

        override fun annotatedOrUnder(annotations: Collection<AnnotationFqn>, targets: Set<KotlinTarget>): LookupPredicate =
            annotated(annotations, targets) or ancestorAnnotated(annotations, targets)
    }

    companion object {
        inline fun create(init: BuilderContext.() -> LookupPredicate): LookupPredicate = BuilderContext.init()
    }
}
