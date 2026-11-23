package com.devqa.feature.projectbrain.presentation.web

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton

class FrontendUnavailablePanel(
    message: String,
    onRetry: () -> Unit
) : JBPanel<FrontendUnavailablePanel>() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(48)

        add(JBLabel("DevQA frontend unavailable").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.BOLD, 18f)
        })
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(JBLabel(message).apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = UIUtil.getContextHelpForeground()
        })
        add(Box.createVerticalStrut(JBUI.scale(18)))
        add(JButton("Retry frontend").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener { onRetry() }
        })
    }
}
