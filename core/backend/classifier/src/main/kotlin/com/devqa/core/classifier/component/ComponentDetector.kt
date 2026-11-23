package com.devqa.core.classifier.component

import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

interface ComponentDetector {
    fun detect(psiClass: PsiClass): List<ClassificationEvidence>
}
