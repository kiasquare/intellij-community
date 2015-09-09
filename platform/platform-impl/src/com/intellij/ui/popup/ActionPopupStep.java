/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ui.popup;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Getter;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

public class ActionPopupStep implements ListPopupStepEx<PopupFactoryImpl.ActionItem>, MnemonicNavigationFilter<PopupFactoryImpl.ActionItem>,
                                 SpeedSearchFilter<PopupFactoryImpl.ActionItem> {
  public final List<PopupFactoryImpl.ActionItem> myItems;
  private final String myTitle;
  private final Getter<DataContext> myContext;
  private final boolean myEnableMnemonics;
  private final int myDefaultOptionIndex;
  private final boolean myAutoSelectionEnabled;
  private final boolean myShowDisabledActions;
  private Runnable myFinalRunnable;
  @Nullable private final Condition<AnAction> myPreselectActionCondition;

  public ActionPopupStep(@NotNull final List<PopupFactoryImpl.ActionItem> items,
                         final String title,
                         Getter<DataContext> context,
                         boolean enableMnemonics,
                         @Nullable Condition<AnAction> preselectActionCondition,
                         final boolean autoSelection,
                         boolean showDisabledActions) {
    myItems = items;
    myTitle = title;
    myContext = context;
    myEnableMnemonics = enableMnemonics;
    myDefaultOptionIndex = getDefaultOptionIndexFromSelectCondition(preselectActionCondition, items);
    myPreselectActionCondition = preselectActionCondition;
    myAutoSelectionEnabled = autoSelection;
    myShowDisabledActions = showDisabledActions;
  }

  private static int getDefaultOptionIndexFromSelectCondition(@Nullable Condition<AnAction> preselectActionCondition,
                                                              @NotNull List<PopupFactoryImpl.ActionItem> items) {
    int defaultOptionIndex = 0;
    if (preselectActionCondition != null) {
      for (int i = 0; i < items.size(); i++) {
        final AnAction action = items.get(i).getAction();
        if (preselectActionCondition.value(action)) {
          defaultOptionIndex = i;
          break;
        }
      }
    }
    return defaultOptionIndex;
  }

  @Override
  @NotNull
  public List<PopupFactoryImpl.ActionItem> getValues() {
    return myItems;
  }

  @Override
  public boolean isSelectable(final PopupFactoryImpl.ActionItem value) {
    return value.isEnabled();
  }

  @Override
  public int getMnemonicPos(final PopupFactoryImpl.ActionItem value) {
    final String text = getTextFor(value);
    int i = text.indexOf(UIUtil.MNEMONIC);
    if (i < 0) {
      i = text.indexOf('&');
    }
    if (i < 0) {
      i = text.indexOf('_');
    }
    return i;
  }

  @Override
  public Icon getIconFor(final PopupFactoryImpl.ActionItem aValue) {
    return aValue.getIcon();
  }

  @Nullable
  @Override
  public String getTooltipTextFor(PopupFactoryImpl.ActionItem value) {
    return value.getDescription();
  }

  @Override
  @NotNull
  public String getTextFor(final PopupFactoryImpl.ActionItem value) {
    return value.getText();
  }

  @Override
  public ListSeparator getSeparatorAbove(final PopupFactoryImpl.ActionItem value) {
    return value.isPrependWithSeparator() ? new ListSeparator(value.getSeparatorText()) : null;
  }

  @Override
  public int getDefaultOptionIndex() {
    return myDefaultOptionIndex;
  }

  @Override
  public String getTitle() {
    return myTitle;
  }

  @Override
  public PopupStep onChosen(final PopupFactoryImpl.ActionItem actionChoice, final boolean finalChoice) {
    return onChosen(actionChoice, finalChoice, 0);
  }

  @Override
  public PopupStep onChosen(PopupFactoryImpl.ActionItem actionChoice, boolean finalChoice, final int eventModifiers) {
    if (!actionChoice.isEnabled()) return FINAL_CHOICE;
    final AnAction action = actionChoice.getAction();

    if (action instanceof ActionGroup && (!finalChoice || !((ActionGroup)action).canBePerformed(myContext.get()))) {
        return PopupFactoryImpl
          .createActionsStep((ActionGroup)action, myContext.get(), myEnableMnemonics, true, myShowDisabledActions, null, null, false,
                             myPreselectActionCondition, false);
    }
    else {
      myFinalRunnable = new Runnable() {
        @Override
        public void run() {
          performAction(action, eventModifiers);
        }
      };
      return FINAL_CHOICE;
    }
  }

  public void performAction(@NotNull AnAction action, int modifiers) {
    final DataManager mgr = DataManager.getInstance();
    final DataContext dataContext = myContext != null ? myContext.get() : mgr.getDataContext();
    final AnActionEvent event =
      new AnActionEvent(null, dataContext, ActionPlaces.UNKNOWN, action.getTemplatePresentation().clone(), ActionManager.getInstance(),
                        modifiers);
    event.setInjectedContext(action.isInInjectedContext());
    if (ActionUtil.lastUpdateAndCheckDumb(action, event, false)) {
      action.actionPerformed(event);
    }
  }

  @Override
  public Runnable getFinalRunnable() {
    return myFinalRunnable;
  }

  @Override
  public boolean hasSubstep(final PopupFactoryImpl.ActionItem selectedValue) {
    return selectedValue != null && selectedValue.isEnabled() && selectedValue.getAction() instanceof ActionGroup;
  }

  @Override
  public void canceled() {
  }

  @Override
  public boolean isMnemonicsNavigationEnabled() {
    return myEnableMnemonics;
  }

  @Override
  public MnemonicNavigationFilter<PopupFactoryImpl.ActionItem> getMnemonicNavigationFilter() {
    return this;
  }

  @Override
  public boolean canBeHidden(final PopupFactoryImpl.ActionItem value) {
    return true;
  }

  @Override
  public String getIndexedString(final PopupFactoryImpl.ActionItem value) {
    return getTextFor(value);
  }

  @Override
  public boolean isSpeedSearchEnabled() {
    return true;
  }

  @Override
  public boolean isAutoSelectionEnabled() {
    return myAutoSelectionEnabled;
  }

  @Override
  public SpeedSearchFilter<PopupFactoryImpl.ActionItem> getSpeedSearchFilter() {
    return this;
  }
}
