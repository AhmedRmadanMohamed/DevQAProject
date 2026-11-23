package com.devqa.core.classifier.component

import com.devqa.core.model.classification.ComponentClassification
import com.devqa.core.classifier.component.detector.AspectDetector
import com.devqa.core.classifier.component.detector.ConfigurationDetector
import com.devqa.core.classifier.component.detector.ControllerDetector
import com.devqa.core.classifier.component.detector.ConverterDetector
import com.devqa.core.classifier.component.detector.DtoDetector
import com.devqa.core.classifier.component.detector.EntityDetector
import com.devqa.core.classifier.component.detector.EventDetector
import com.devqa.core.classifier.component.detector.ExceptionDetector
import com.devqa.core.classifier.component.detector.FilterDetector
import com.devqa.core.classifier.component.detector.InterceptorDetector
import com.devqa.core.classifier.component.detector.ListenerDetector
import com.devqa.core.classifier.component.detector.MapperDetector
import com.devqa.core.classifier.component.detector.RepositoryDetector
import com.devqa.core.classifier.component.detector.SchedulerDetector
import com.devqa.core.classifier.component.detector.SecurityDetector
import com.devqa.core.classifier.component.detector.ServiceDetector
import com.devqa.core.classifier.component.detector.SpecificationDetector
import com.devqa.core.classifier.component.detector.UtilityDetector
import com.devqa.core.classifier.component.detector.ValidatorDetector
import com.devqa.core.classifier.component.detector.BatchComponentDetector
import com.intellij.psi.PsiClass

class ComponentClassifier(
    private val detectors: List<ComponentDetector> = listOf(
        ControllerDetector(),
        ServiceDetector(),
        RepositoryDetector(),
        EntityDetector(),
        DtoDetector(),
        MapperDetector(),
        ConfigurationDetector(),
        SecurityDetector(),
        FilterDetector(),
        InterceptorDetector(),
        ValidatorDetector(),
        ListenerDetector(),
        SchedulerDetector(),
        AspectDetector(),
        ExceptionDetector(),
        ConverterDetector(),
        SpecificationDetector(),
        UtilityDetector(),
        EventDetector(),
        BatchComponentDetector()
    )
) {

    fun classify(psiClass: PsiClass): ComponentClassification =
        ComponentClassificationPolicy.classify(
            detectors.flatMap { detector -> detector.detect(psiClass) }
        )
}
