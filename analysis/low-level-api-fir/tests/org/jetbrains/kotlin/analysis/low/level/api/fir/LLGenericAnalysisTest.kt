/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.descendantsOfType
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolver
import org.jetbrains.kotlin.analysis.api.platform.declarations.createAnnotationResolver
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFir
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFirFile
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFirOfType
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.resolveToFirSymbolOfType
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLPartialBodyElementMapper
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.bodyBlock
import org.jetbrains.kotlin.analysis.low.level.api.fir.providers.LLFirIdeRegisteredPluginAnnotations
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.llFirResolvableSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.test.configurators.LLSourceLikeTestConfigurator
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiExecutionTest
import org.jetbrains.kotlin.analysis.test.framework.utils.executeOnPooledThreadInReadAction
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.resolvePhase
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.extensions.FirPredicateBasedProvider
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.extensions.registeredPluginAnnotations
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.utils.findIsInstanceAnd
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LLGenericAnalysisTest : AbstractAnalysisApiExecutionTest("testData/genericAnalysis") {
    override val configurator = LLSourceLikeTestConfigurator()

    @Test
    fun simple(ktFile: KtFile) {
        withResolutionFacade(ktFile) { resolutionFacade ->
            val ktSecondCall = ktFile.descendantsOfType<KtCallExpression>().first { it.text == "consume(2)" }
            ktSecondCall.getOrBuildFir(resolutionFacade)

            val firFile = ktFile.getOrBuildFirFile(resolutionFacade)
            val firFunction = firFile.declarations.findIsInstanceAnd<FirFunction> { it.name() == "test" }!!

            // The function is only partially resolved
            assert(firFunction.resolvePhase == FirResolvePhase.ANNOTATION_ARGUMENTS)
        }
    }

    @Test
    fun bodyAlreadyAnalyzed(ktFile: KtFile) {
        withResolutionFacade(ktFile) { resolutionFacade ->
            val firFile = ktFile.getOrBuildFirFile(resolutionFacade)
            val firFunction = firFile.declarations.findIsInstanceAnd<FirFunction> { it.name() == "test" }!!
            assert(firFunction.resolvePhase == FirResolvePhase.RAW_FIR)

            // Do not trigger 'getOrBuildFir()'
            firFunction.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
            assert(firFunction.resolvePhase == FirResolvePhase.BODY_RESOLVE)

            val ktFunction = ktFile.declarations.findIsInstanceAnd<KtFunction> { it.name == "test" }!!
            val ktBlock = ktFunction.bodyBlockExpression!!
            val ktStatements = ktBlock.statements

            // Simulate data race between BODY_RESOLVE and LLElementMapper computation
            val mapper = LLPartialBodyElementMapper(firFunction, ktFunction, ktBlock, ktStatements, firFunction.llFirResolvableSession!!)
            assertEquals(firFunction, mapper.invoke(ktFunction))

            val ktSecondCall = ktFile.descendantsOfType<KtCallExpression>().first { it.text == "consume(2)" }
            val firSecondCall = ktSecondCall.getOrBuildFirOfType<FirFunctionCall>(resolutionFacade)
            assertEquals(ktSecondCall, firSecondCall.psi)
        }
    }

    @Test
    fun abstractFunctionWithDefault(ktFile: KtFile, testServices: TestServices) {
        withResolutionFacade(ktFile) { resolutionFacade ->
            val targetPsiClass = ktFile.declarations.last() as KtClass
            val targetPsiFunction = targetPsiClass.declarations.last() as KtNamedFunction
            val targetFirFunction = targetPsiFunction.resolveToFirSymbolOfType<FirNamedFunctionSymbol>(resolutionFacade)
            val psiStatements = targetPsiFunction.bodyBlock?.statements.orEmpty()
            psiStatements.first().getOrBuildFirOfType<FirStatement>(resolutionFacade)

            fun renderTarget(element: FirElement) = lazyResolveRenderer(StringBuilder()).renderElementAsString(element).trim()

            testServices.assertions.assertEquals(
                """
                    public final [ResolvedTo(ANNOTATION_ARGUMENTS)] [PartialBodyAnalysisStateKey=1(1/2) #1] fun g(): R|kotlin/collections/List<kotlin/Int>| {
                        [ResolvedTo(BODY_RESOLVE)] lval e: R|kotlin/collections/List<T>| = Null(null)!!
                        ^g e#.myMap#(::f#)
                    }
                """.trimIndent(),
                renderTarget(targetFirFunction.fir),
            )

            val lastFirElement = executeOnPooledThreadInReadAction {
                psiStatements.last().getOrBuildFirOfType<FirElement>(resolutionFacade)
            }

            testServices.assertions.assertEquals(
                """
                        ^g R|<local>/e|.R|/myMap|<R|T|, R|kotlin/Int|>(::R|SubstitutionOverride</G.f: R|kotlin/Int|>|)
                    """.trimIndent(),
                renderTarget(lastFirElement),
            )
        }
    }

    /**
     * A regression test for KT-88945.
     *
     * [KotlinAnnotationsResolver] is the only annotation source for [FirPredicateBasedProvider] as long as the FIR annotations
     * of a declaration are not resolved yet, so a gap between the two makes plugin-generated declarations
     * depend on the amount of the performed resolution.
     */
    @Test
    fun redeclaredPluginAnnotation(ktFile: KtFile, testServices: TestServices) {
        assertAnnotationsOnMyClass(ktFile, testServices, "org.jetbrains.kotlin.plugin.sandbox.CompanionWithFoo")
    }

    /**
     * A regression test for KT-88945.
     *
     * @see redeclaredPluginAnnotation
     */
    @Test
    fun redeclaredAnnotationTypeAlias(ktFile: KtFile, testServices: TestServices) {
        assertAnnotationsOnMyClass(ktFile, testServices, "test.MyAnnotation")
    }

    /**
     * A regression test for KT-88945.
     *
     * @see redeclaredPluginAnnotation
     */
    @Test
    fun qualifiedAnnotationTypeAlias(ktFile: KtFile, testServices: TestServices) {
        assertAnnotationsOnMyClass(ktFile, testServices, "other.MyAnnotation")
    }

    /**
     * A regression test for KT-88945.
     *
     * @see redeclaredPluginAnnotation
     */
    @Test
    fun cyclicAnnotationTypeAlias(ktFile: KtFile, testServices: TestServices) {
        assertAnnotationsOnMyClass(ktFile, testServices, "test.MyAnnotation")
    }

    private fun assertAnnotationsOnMyClass(ktFile: KtFile, testServices: TestServices, vararg expected: String) {
        val project = ktFile.project
        val ktClass = ktFile.declarations.single { it.name == "MyClass" } as KtClass
        val annotationsResolver = project.createAnnotationResolver(GlobalSearchScope.allScope(project))

        testServices.assertions.assertEquals(
            expected = expected.mapTo(mutableSetOf()) { ClassId.topLevel(FqName(it)) },
            actual = annotationsResolver.annotationsOnDeclaration(ktClass),
        )
    }

    @Test
    fun predicateTargets(ktFile: KtFile) {
        withResolutionFacade(ktFile) { resolutionFacade ->
            val provider = resolutionFacade.useSiteFirSession.predicateBasedProvider
            val annotation = FqName("org.jetbrains.kotlin.plugin.sandbox.TestTopLevelPrivateSuspendFun")

            fun lookup(vararg targets: KotlinTarget): Set<String> {
                val predicate = LookupPredicate.create { annotated(listOf(annotation), targets.toSet()) }
                return provider.getSymbolsByPredicate(predicate).mapTo(mutableSetOf()) { it.predicateTestName() }
            }

            assertEquals(
                provider.getSymbolsByPredicate(LookupPredicate.create { annotated(annotation) }).mapTo(mutableSetOf()) {
                    it.predicateTestName()
                },
                lookup(),
            )
            assertEquals(setOf("topLevelFunction"), lookup(KotlinTarget.TOP_LEVEL_FUNCTION))
            assertEquals(setOf("Outer.memberFunction"), lookup(KotlinTarget.MEMBER_FUNCTION))
            assertEquals(setOf("topLevelFunction", "Outer.memberFunction"), lookup(KotlinTarget.FUNCTION))
            assertEquals(
                setOf("TopClass", "TopInterface", "TopObject", "TopEnum", "TopAnnotation", "Outer", "Outer.Nested"),
                lookup(KotlinTarget.CLASS),
            )
            assertEquals(setOf("TopClass", "Outer", "Outer.Nested"), lookup(KotlinTarget.CLASS_ONLY))
            assertEquals(setOf("TopInterface", "TopObject"), lookup(KotlinTarget.INTERFACE, KotlinTarget.OBJECT))
            assertEquals(setOf("TopEnum.ENTRY"), lookup(KotlinTarget.ENUM_ENTRY))
            assertEquals(setOf("Alias"), lookup(KotlinTarget.TYPEALIAS))
            assertEquals(setOf("ConstructorOnly.ConstructorOnly"), lookup(KotlinTarget.CONSTRUCTOR))
            assertEquals(setOf("topLevelProperty"), lookup(KotlinTarget.TOP_LEVEL_PROPERTY))
            // Enum entries are property targets too, like with `@Target`. Getter-only annotations don't make the property match.
            assertEquals(setOf("topLevelProperty", "Outer.memberProperty", "TopEnum.ENTRY"), lookup(KotlinTarget.PROPERTY))

            val topLevelFunctions = DeclarationPredicate.create {
                annotated(listOf(annotation), setOf(KotlinTarget.TOP_LEVEL_FUNCTION))
            }
            val functions = provider.getSymbolsByPredicate(
                LookupPredicate.create { annotated(listOf(annotation), setOf(KotlinTarget.FUNCTION)) }
            )
            assertEquals(
                setOf("topLevelFunction"),
                functions.filter { provider.matches(topLevelFunctions, it) }.mapTo(mutableSetOf()) { it.predicateTestName() },
            )

            assertFailsWith<IllegalArgumentException> {
                LookupPredicate.create { annotated(listOf(annotation), setOf(KotlinTarget.FIELD)) }
            }
        }
    }

    @Test
    fun rootCompilerPluginAnnotation(ktFile: KtFile, testServices: TestServices) {
        withResolutionFacade(ktFile) { resolutionFacade ->
            val pluginAnnotations = resolutionFacade.useSiteFirSession.registeredPluginAnnotations
            val assertions = testServices.assertions
            assertions.assertTrue(pluginAnnotations is LLFirIdeRegisteredPluginAnnotations) {
                "The IDE implementation is expected"
            }

            assertions.assertEquals(
                expected = emptySet<FqName>(),
                actual = pluginAnnotations.getAnnotationsWithMetaAnnotation(FqName.ROOT),
            )
        }
    }
}

private fun FirBasedSymbol<*>.predicateTestName(): String = when (this) {
    is FirClassLikeSymbol<*> -> classId.relativeClassName.asString()
    is FirCallableSymbol<*> -> {
        val id = callableId ?: error("Unexpected callable without an id: $this")
        listOfNotNull(id.classId?.relativeClassName?.asString(), id.callableName.asString()).joinToString(".")
    }
    else -> error("Unexpected symbol $this")
}
