// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.intentions

import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KtAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.lifetime.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.AbstractKotlinApplicableIntentionWithContext
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.KotlinApplicabilityRange
import org.jetbrains.kotlin.idea.codeinsight.utils.ChooseStringExpression
import org.jetbrains.kotlin.idea.codeinsight.utils.isFalseConstant
import org.jetbrains.kotlin.idea.codeinsight.utils.isTrueConstant
import org.jetbrains.kotlin.idea.codeinsights.impl.base.applicators.ApplicabilityRanges
import org.jetbrains.kotlin.idea.k2.codeinsight.branchedTransformations.combineWhenConditions
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty

/**
 * An intention to change a when-expression to an if-expression. For example,
 *
 *   // Before
 *   when (n) {
 *     1 -> "One"
 *     2 -> "Two"
 *     else -> "More"
 *   }
 *
 *   // After
 *   if (n == 1) "One"
 *   else if (n == 2) "Two"
 *   else "More"
 */
internal class WhenToIfIntention :
    AbstractKotlinApplicableIntentionWithContext<KtWhenExpression, WhenToIfIntention.Context>(KtWhenExpression::class), LowPriorityAction {
    class Context(val nameCandidatesForWhenSubject: List<String> = emptyList())

    context(KtAnalysisSession)
    private fun KtWhenExpression.hasNoElseButUsedAsExpression(): Boolean {
        val lastEntry = entries.last()
        return !lastEntry.isElse && isUsedAsExpression()
    }

    /**
     * Returns true if the subject is used by a condition of a single or no entry e.g.,
     *   when (expr) {
     *     value -> result0  // it is used only by `expr == value` condition.
     *     else -> result1
     *   }
     */
    private fun KtWhenExpression.isSubjectUsedByOneOrZeroBranch(): Boolean {
        val entries = entries
        val lastEntry = entries.last()
        if (lastEntry.isElse) {
            return entries.size <= 2
        }
        return entries.size <= 1
    }

    context(KtAnalysisSession)
    override fun prepareContext(element: KtWhenExpression): Context? {
        if (element.hasNoElseButUsedAsExpression()) return null

        /**
         * When we generate conditions of the if/else-if expressions, we have to use the subject of [element].
         * Since simply duplicating the subject for conditions can cause side effects, we create a new variable for the subject of [element].
         *
         * For example,
         *
         *   // Before
         *   when (foo.bar()) {
         *     1 -> "One"
         *     2 -> "Two"
         *     else -> "More"
         *   }
         *
         *   // After (with side effects)
         *   if (foo.bar() == 1) "One"
         *   else if (foo.bar() == 2) "Two"   // foo.bar() can have different value!
         *   else "More"
         *
         *   // After (without side effects)
         *   val subject = foo.bar()
         *   if (subject == 1) "One"
         *   else if (subject == 2) "Two"
         *   else "More"
         *
         * However, if the value of [KtWhenExpression] is used, introduce a new variable can cause side effects as well. For example,
         *
         *   // Before
         *   foo.bar() == 4 && when (foo.anotherFunction()) { .. }
         *
         *   // After (with side effects)
         *   val subject = foo.anotherFunction()
         *   foo.bar() == 4 && when (subject) { .. }   // side effect: the execution order of foo.bar() and foo.anotherFunction() is changed!
         *
         * We skip creating a new variable for the subject when the value of [KtWhenExpression] is used.
         * In addition, we also skip creating a new variable if the subject is a reference to a variable. For example,
         *
         *   when (n) {
         *     1 -> "One"
         *     2 -> "Two"
         *     else -> "More"
         *   }
         */
        if (element.isTrueOrFalseCondition()) return Context()
        if (element.isSubjectUsedByOneOrZeroBranch()) return Context()
        val subject = element.subjectExpression
        if (subject != null && subject !is KtNameReferenceExpression && !element.isUsedAsExpression()) {
            return Context(getNewNameForExpression(subject))
        }
        return Context()
    }

    /**
     * Note that the analysis can freeze the IDE, but it is challenging to decouple analysis for this case.
     * The main issue is to separate the type analysis from KtExpression.negate().
     */
    @OptIn(KtAllowAnalysisOnEdt::class)
    override fun apply(element: KtWhenExpression, context: Context, project: Project, editor: Editor?) {
        val subject = element.subjectExpression
        val temporaryNameForWhenSubject =
            context.nameCandidatesForWhenSubject.ifNotEmpty { context.nameCandidatesForWhenSubject.last() } ?: ""
        val propertyForWhenSubject = if (temporaryNameForWhenSubject.isNotEmpty() && subject != null) {
            buildPropertyForWhenSubject(
                subject, temporaryNameForWhenSubject
            )
        } else {
            null
        }

        val ifExpressionToReplaceWhen = allowAnalysisOnEdt {
            buildIfExpressionForWhen(element, propertyForWhenSubject?.referenceToProperty ?: subject) as? KtIfExpression ?: return
        }

        val commentSaver = CommentSaver(element)
        val result = element.replace(ifExpressionToReplaceWhen)
        var newProperty: PsiElement? = null
        var needNameSelection = false
        propertyForWhenSubject?.property?.let {
            newProperty = result.parent.addBefore(it, result)
            result.parent.addBefore(KtPsiFactory(element.project).createNewLine(), result)
            needNameSelection = true
        }
        commentSaver.restore(result)

        if (!needNameSelection) return

        // Select name of temporary variable for the subject of when-expression.
        val newPropertyNeededNameSelection = newProperty as? KtProperty ?: return
        editor?.let {
            updateVariableNameBasedOnSelection(
                newPropertyNeededNameSelection, it, context.nameCandidatesForWhenSubject, temporaryNameForWhenSubject, result
            )
        }
    }

    private fun updateVariableNameBasedOnSelection(
        property: KtProperty,
        editor: Editor,
        nameCandidates: List<String>,
        currentName: String,
        expressionContainingReferencesToProperty: PsiElement
    ) {
        val id = property.identifyingElement ?: return
        PsiDocumentManager.getInstance(property.project).doPostponedOperationsAndUnblockDocument(editor.document)
        val templateBuilder = TemplateBuilderImpl(property)
        templateBuilder.replaceElement(
            id, ChooseStringExpression(
                nameCandidates,
                default = currentName,
                advertisementText = null
            ) { expressionContainingReferencesToProperty.collectTextRangesReferencingProperty(currentName) }
        )
        templateBuilder.run(editor, true)
    }

    private fun PsiElement.collectTextRangesReferencingProperty(propertyName: String): List<TextRange> {
        val textRanges = mutableListOf<TextRange>()
        children.forEach { textRanges.addAll(it.collectTextRangesReferencingProperty(propertyName)) }
        if (this is KtNameReferenceExpression && mainReference.value == propertyName) {
            textRange?.let { textRanges.add(it) }
        }
        return textRanges
    }

    override fun getActionName(element: KtWhenExpression, context: Context) = familyName

    override fun getFamilyName(): String = KotlinBundle.message("replace.when.with.if")

    override fun getApplicabilityRange(): KotlinApplicabilityRange<KtWhenExpression> = ApplicabilityRanges.SELF

    override fun isApplicableByPsi(element: KtWhenExpression): Boolean {
        val entries = element.entries
        if (entries.isEmpty()) return false
        val lastEntry = entries.last()
        if (entries.any { it != lastEntry && it.isElse }) return false
        if (entries.size == 1 && lastEntry.isElse) return false // 'when' with only 'else' branch is not supported
        return element.subjectExpression !is KtProperty
    }

    private fun getNewNameForExpression(expression: KtExpression): List<String> {
        return analyze(expression) {
            with(KotlinNameSuggester(KotlinNameSuggester.Case.CAMEL)) {
                suggestExpressionNames(expression).toList()
            }
        }
    }

    data class PropertyForWhenSubject(val property: KtExpression, val referenceToProperty: KtExpression)

    private fun buildPropertyForWhenSubject(subject: KtExpression, varNameForSubject: String): PropertyForWhenSubject {
        val psiFactory = KtPsiFactory(subject.project)
        return PropertyForWhenSubject(
            psiFactory.buildExpression {
                val property = psiFactory.createProperty(varNameForSubject, type = null, isVar = false)
                property.initializer = subject
                appendExpression(property)
            },
            psiFactory.createExpressionByPattern(varNameForSubject)
        )
    }

    private fun buildIfExpressionForWhen(element: KtWhenExpression, subject: KtExpression?): KtExpression {
        val psiFactory = KtPsiFactory(element.project)

        val isTrueOrFalseCondition = element.isTrueOrFalseCondition()
        return psiFactory.buildExpression {
            val entries = element.entries
            for ((i, entry) in entries.withIndex()) {
                if (i > 0) {
                    appendFixedText("else ")
                }
                val branch = entry.expression
                if (entry.isElse || (isTrueOrFalseCondition && i == 1)) {
                    appendExpression(branch)
                } else {
                    val condition = psiFactory.combineWhenConditions(entry.conditions, subject)
                    appendFixedText("if (")
                    appendExpression(condition)
                    appendFixedText(")")
                    if (branch is KtIfExpression) {
                        appendFixedText("{ ")
                    }

                    appendExpression(branch)
                    if (branch is KtIfExpression) {
                        appendFixedText(" }")
                    }
                }

                if (i != entries.lastIndex) {
                    appendFixedText("\n")
                }
            }
        }
    }

    private fun KtWhenExpression.isTrueOrFalseCondition(): Boolean {
        val entries = this.entries
        if (entries.size != 2) return false
        val first = entries[0]?.conditionExpression() ?: return false
        val second = entries[1]?.conditionExpression() ?: return false
        return first.isTrueConstant() && second.isFalseConstant() || first.isFalseConstant() && second.isTrueConstant()
    }

    private fun KtWhenEntry.conditionExpression(): KtExpression? {
        return (conditions.singleOrNull() as? KtWhenConditionWithExpression)?.expression
    }
}