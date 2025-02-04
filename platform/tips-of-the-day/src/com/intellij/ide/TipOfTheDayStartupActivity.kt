// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide

import com.intellij.ide.util.TipAndTrickManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectPostStartupActivity
import com.intellij.util.PlatformUtils

private class TipOfTheDayStartupActivity : ProjectPostStartupActivity {
  init {
    if (ApplicationManager.getApplication().isHeadlessEnvironment || PlatformUtils.isRider() || !GeneralSettings.getInstance().isShowTipsOnStartup) {
      throw ExtensionNotApplicableException.create()
    }
  }

  override suspend fun execute(project: Project) {
    val tipManager = TipAndTrickManager.getInstance()
    if (tipManager.canShowDialogAutomaticallyNow(project)) {
      TipsOfTheDayUsagesCollector.triggerDialogShown(TipsOfTheDayUsagesCollector.DialogType.automatically)
      tipManager.showTipDialog(project)
    }
  }
}