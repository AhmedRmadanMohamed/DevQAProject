package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class InterceptorDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.INTERCEPTOR,
            setOf(
                "org.springframework.web.servlet.HandlerInterceptor",
                "org.aopalliance.intercept.MethodInterceptor"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.INTERCEPTOR,
            setOf("Interceptor")
        )
}
