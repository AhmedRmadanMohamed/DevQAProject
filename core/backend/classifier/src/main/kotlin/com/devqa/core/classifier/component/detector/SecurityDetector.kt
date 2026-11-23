package com.devqa.core.classifier.component.detector

import com.devqa.core.model.type.ComponentType
import com.devqa.core.classifier.component.ComponentDetector
import com.devqa.core.classifier.component.ComponentEvidenceResolver
import com.devqa.core.model.classification.ClassificationEvidence
import com.intellij.psi.PsiClass

class SecurityDetector : ComponentDetector {
    override fun detect(psiClass: PsiClass): List<ClassificationEvidence> =
        ComponentEvidenceResolver.annotations(
            psiClass,
            ComponentType.SECURITY,
            setOf(
                "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
                "org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity",
                "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity",
                "org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity",
                "org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity"
            )
        ) + ComponentEvidenceResolver.supertypes(
            psiClass,
            ComponentType.SECURITY,
            setOf(
                "org.springframework.security.config.annotation.web.WebSecurityConfigurer",
                "org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter",
                "org.springframework.security.core.userdetails.UserDetailsService",
                "org.springframework.security.core.userdetails.ReactiveUserDetailsService",
                "org.springframework.security.authentication.AuthenticationProvider",
                "org.springframework.security.authentication.ReactiveAuthenticationManager",
                "org.springframework.security.authorization.AuthorizationManager",
                "org.springframework.security.access.PermissionEvaluator"
            )
        ) + ComponentEvidenceResolver.classNameSuffixes(
            psiClass,
            ComponentType.SECURITY,
            setOf("SecurityConfig", "SecurityConfiguration")
        )
}
