package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class RepositoryDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.REPOSITORY,
            setOf("org.springframework.stereotype.Repository")
        ) + ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.REPOSITORY,
            setOf(
                "org.springframework.data.repository.Repository",
                "org.springframework.data.repository.CrudRepository",
                "org.springframework.data.repository.ListCrudRepository",
                "org.springframework.data.repository.PagingAndSortingRepository",
                "org.springframework.data.repository.ListPagingAndSortingRepository",
                "org.springframework.data.jpa.repository.JpaRepository"
            )
        ) + ComponentEvidenceResolver.packageSegments(
            psiClass,
            ComponentType.REPOSITORY,
            setOf("repository")
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.REPOSITORY,
            setOf("Repository")
        )
}
